using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace AniSub.Windows {
    public sealed class ContentTerm {
        public string source {get;set;} public string target {get;set;} public string[] aliases {get;set;}
    }
    public sealed class ContentPolicy {
        public string profile {get;set;} public ContentTerm[] terms {get;set;}
        public static ContentPolicy Parse(string profile, string lines) {
            if (profile!="general" && profile!="technology" && profile!="film" && profile!="pc-game") throw new ArgumentException("Invalid profile");
            if (lines==null || lines.Length>12000) throw new ArgumentException("Glossary exceeds bound");
            var result=new System.Collections.Generic.List<ContentTerm>();
            var names=new System.Collections.Generic.HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var outputs=new System.Collections.Generic.HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (string line in lines.Replace("\r", "").Split('\n')) {
                if (string.IsNullOrWhiteSpace(line)) continue;
                string[] parts=line.Split('|');
                if(parts.Length!=3 || result.Count>=32) throw new ArgumentException("Use source | target | output aliases");
                string source=parts[0].Trim(), target=parts[1].Trim();
                ValidateTerm(source);ValidateTerm(target);
                if(!names.Add(source)) throw new ArgumentException("Duplicate source");
                var aliases=new System.Collections.Generic.List<string>();
                if(!string.IsNullOrWhiteSpace(parts[2])) foreach(string raw in parts[2].Split(';')) {
                    string alias=raw.Trim();ValidateTerm(alias);
                    if(aliases.Count>=4 || !outputs.Add(alias)) throw new ArgumentException("Duplicate/excess aliases");
                    aliases.Add(alias);
                }
                result.Add(new ContentTerm {source=source,target=target,aliases=aliases.ToArray()});
            }
            return new ContentPolicy {profile=profile,terms=result.ToArray()};
        }
        static void ValidateTerm(string value) {
            if(string.IsNullOrWhiteSpace(value)||value.Length>80) throw new ArgumentException("Invalid term length");
            foreach(char c in value) if(char.IsControl(c)) throw new ArgumentException("Control character in term");
        }
    }
    public sealed class VoicePreset {
        public string name { get; set; }
        public string gender { get; set; }
        public string region { get; set; }
        public string description { get; set; }
        public override string ToString() { return name; }
    }
    public sealed class ProviderReply {
        public bool Ok { get; set; }
        public string Text { get; set; }
        public string AudioPath { get; set; }
        public string Error { get; set; }
        public double LatencyMs { get; set; }
        public double AudioDurationMs { get; set; }
        public string VoiceName { get; set; }
        public VoicePreset[] Voices { get; set; }
        public string DefaultVoice { get; set; }
        public string VoiceProfile { get; set; }
        public int GlossaryApplied {get;set;} public int GlossaryUnresolved {get;set;}
    }
    // One persistent, offline worker per window; UI owns revision/cue cancellation.
    public sealed class TranslationBridge : IDisposable {
        private readonly Process process;
        private readonly StreamWriter input;
        private readonly string outputDirectory;
        private readonly SemaphoreSlim gate = new SemaphoreSlim(1, 1);
        private bool disposed;
        private int sequence;
        private string selectedVoice = "Minh Quân";
        public string SelectedVoice {
            get { return selectedVoice; }
            set { if (string.IsNullOrWhiteSpace(value) || value.Length > 80) throw new ArgumentException("Invalid voice selection"); selectedVoice = value; }
        }
        private async Task<string> ReadBoundedLine() {
            var builder = new StringBuilder();
            char[] character = new char[1];
            while (await process.StandardOutput.ReadAsync(character, 0, 1) != 0) {
                if (character[0] == '\n') return builder.ToString().TrimEnd('\r');
                if (builder.Length >= 65536) throw new InvalidDataException("PROVIDER_REPLY_OVERSIZE");
                builder.Append(character[0]);
            }
            return null;
        }
        private static string Quote(string value) {
            if (value.IndexOf('"') >= 0 || value.EndsWith("\\")) throw new ArgumentException("Invalid provider path");
            return "\"" + value + "\"";
        }
        public TranslationBridge(string python, string worker, string modelDir, string ttsDir) {
            foreach (string path in new [] {python, worker}) if (!File.Exists(path)) throw new FileNotFoundException("Provider executable missing");
            string session = Guid.NewGuid().ToString("N");
            string root = Directory.GetParent(Path.GetDirectoryName(Path.GetFullPath(worker))).Parent.FullName;
            string ownedRoot = Path.GetFullPath(Path.Combine(root, "work", "speech-session"));
            outputDirectory = Path.GetFullPath(Path.Combine(ownedRoot, session));
            if (Directory.GetParent(outputDirectory).FullName != ownedRoot) throw new ArgumentException("Invalid owned cache path");
            var start = new ProcessStartInfo(python, "-u " + Quote(worker) + " --model " + Quote(modelDir) + " --tts " + Quote(ttsDir) + " --session " + session);
            start.UseShellExecute = false; start.CreateNoWindow = true;
            start.RedirectStandardInput = true; start.RedirectStandardOutput = true; start.RedirectStandardError = true;
            start.StandardOutputEncoding = Encoding.UTF8; start.StandardErrorEncoding = Encoding.UTF8;
            start.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8";
            start.EnvironmentVariables["HF_HUB_OFFLINE"] = "1";
            start.EnvironmentVariables["TRANSFORMERS_OFFLINE"] = "1";
            process = new Process {StartInfo = start};
            process.ErrorDataReceived += delegate { }; // Never echo upstream subtitle diagnostics.
            process.Start(); process.BeginErrorReadLine();
            input = new StreamWriter(process.StandardInput.BaseStream, new UTF8Encoding(false));
        }
        public async Task<ProviderReply> RequestAsync(string operation, string text, double targetMs = 0, ContentPolicy contentPolicy = null) {
            if (operation != "validate-policy" && operation != "probe" && operation != "probe-voice" && operation != "voice-catalog" && operation != "release" && operation != "translate" && operation != "synthesize") throw new ArgumentException("Unsupported operation");
            if (text == null || text.Length > 4096) throw new ArgumentException("Text exceeds bound");
            if (double.IsNaN(targetMs) || double.IsInfinity(targetMs) || targetMs < 0 || targetMs > 30000) throw new ArgumentException("Invalid speech budget");
            if (!await gate.WaitAsync(0)) throw new InvalidOperationException("Provider busy");
            try {
                if (disposed || process.HasExited) throw new InvalidOperationException("Provider unavailable");
                var serializer = new JavaScriptSerializer {MaxJsonLength = 65536};
                int id = ++sequence;
                string request = serializer.Serialize(new { id = id, operation = operation, text = text, targetMs = targetMs, voiceName = selectedVoice, contentPolicy = contentPolicy });
                await input.WriteLineAsync(request);
                await input.FlushAsync();
                Task<string> read = ReadBoundedLine();
                if (await Task.WhenAny(read, Task.Delay(120000)) != read) {
                    Dispose(); throw new TimeoutException("Offline provider timed out");
                }
                string line = await read;
                if (line == null) throw new InvalidDataException("PROVIDER_EOF");
                if (line.Length > 65536) throw new InvalidDataException("PROVIDER_REPLY_OVERSIZE");
                var reply = serializer.Deserialize<WireReply>(line);
                if (reply.id != id) throw new InvalidDataException("PROVIDER_ID_MISMATCH expected=" + id + " got=" + reply.id);
                return new ProviderReply {Ok = reply.ok, Text = reply.text, AudioPath = reply.audioPath, Error = reply.error, LatencyMs = reply.latencyMs, AudioDurationMs = reply.audioDurationMs, VoiceName = reply.voiceName, Voices = reply.voices, DefaultVoice = reply.defaultVoice, VoiceProfile=reply.voiceProfile,GlossaryApplied=reply.glossaryApplied,GlossaryUnresolved=reply.glossaryUnresolved};
            } finally { gate.Release(); }
        }
        public sealed class WireReply {
            public int id { get; set; } public bool ok { get; set; }
            public string text { get; set; } public string audioPath { get; set; }
            public string error { get; set; } public double latencyMs { get; set; }
            public double audioDurationMs { get; set; }
            public string voiceName { get; set; }
            public VoicePreset[] voices { get; set; }
            public string defaultVoice { get; set; }
            public string voiceProfile { get; set; }
            public int glossaryApplied {get;set;} public int glossaryUnresolved {get;set;}
        }
        public void Dispose() {
            if (disposed) return; disposed = true;
            try { if (!process.HasExited) { input.Close(); if (!process.WaitForExit(1000)) { process.Kill(); process.WaitForExit(1000); } } } catch (InvalidOperationException) { }
            process.Dispose();
            try {
                if (Directory.Exists(outputDirectory)) {
                    foreach (string file in Directory.GetFiles(outputDirectory, "*.wav", SearchOption.TopDirectoryOnly)) File.Delete(file);
                    Directory.Delete(outputDirectory, false);
                }
            } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
}
