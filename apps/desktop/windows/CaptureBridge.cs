using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace AniSub.Windows {
 public sealed class CaptureReply { public int id; public bool ok; public string text,language,origin,error,backend; public int droppedChunks; public double inferenceMs,audioMs; public int queuedChunks; }
 public sealed class CaptureBridge : IDisposable {
  readonly Process process;readonly StreamWriter input;int sequence;bool disposed,busy;
  static string Quote(string path) { if(path==null||path.IndexOf('"')>=0||path.EndsWith("\\"))throw new ArgumentException("Invalid capture path");return "\""+path+"\""; }
  public CaptureBridge(string python,string worker,string asrModel) {
   if(!File.Exists(python)||!File.Exists(worker))throw new FileNotFoundException("Capture runtime unavailable");
   var start=new ProcessStartInfo(python,"-u "+Quote(worker)+(string.IsNullOrEmpty(asrModel)?"":" --asr-model "+Quote(asrModel)));
   start.UseShellExecute=false;start.CreateNoWindow=true;start.RedirectStandardInput=true;start.RedirectStandardOutput=true;start.RedirectStandardError=true;start.StandardOutputEncoding=Encoding.UTF8;start.StandardErrorEncoding=Encoding.UTF8;
   start.EnvironmentVariables["PYTHONIOENCODING"]="utf-8";start.EnvironmentVariables["HF_HUB_OFFLINE"]="1";
   process=new Process {StartInfo=start};process.ErrorDataReceived+=delegate{};process.Start();process.BeginErrorReadLine();input=new StreamWriter(process.StandardInput.BaseStream,new UTF8Encoding(false));
  }
  async Task<string> ReadLine() {
   var result=new StringBuilder();var character=new char[1];
   while(await process.StandardOutput.ReadAsync(character,0,1)!=0){if(character[0]=='\n')return result.ToString().TrimEnd('\r');if(result.Length>=8192)throw new InvalidDataException("Capture reply too large");result.Append(character[0]);}throw new EndOfStreamException();
  }
  public async Task<CaptureReply> ReadAsync(SourceCandidate source,string mode,string language) {
   if(disposed||busy)throw new InvalidOperationException("Capture unavailable/busy");if(mode!="ocr"&&mode!="asr")throw new ArgumentException("Unknown capture mode");if(!SourceDiscovery.Revalidate(source))throw new InvalidOperationException("Source changed");
   busy=true;
   try {
    var serializer=new JavaScriptSerializer {MaxJsonLength=8192};int id=++sequence;
    string request=serializer.Serialize(new {id=id,operation=mode,consent=true,pid=source.ProcessId,startTicks=source.StartTimeUtcTicks,hwnd=source.WindowHandle.ToInt64(),language=language,roi=new double[]{0,0.65,1,0.35},seconds=2});
    await input.WriteLineAsync(request);await input.FlushAsync();var read=ReadLine();if(await Task.WhenAny(read,Task.Delay(45000))!=read){Dispose();throw new TimeoutException("Capture timeout");}
    var reply=serializer.Deserialize<CaptureReply>(await read);
    if(reply==null||reply.id!=id||reply.text!=null&&reply.text.Length>512||reply.error!=null&&reply.error.Length>160)throw new InvalidDataException("Invalid capture reply");
    if(double.IsNaN(reply.inferenceMs)||double.IsInfinity(reply.inferenceMs)||reply.inferenceMs<0||reply.inferenceMs>120000||double.IsNaN(reply.audioMs)||double.IsInfinity(reply.audioMs)||reply.audioMs<0||reply.audioMs>30000||reply.queuedChunks<0||reply.queuedChunks>32||reply.droppedChunks<0)throw new InvalidDataException("Invalid capture metrics");
    if(reply.backend!=null&&reply.backend!="cpu-int8"&&reply.backend!="cuda-float16"&&reply.backend!="cpu-int8-fallback")throw new InvalidDataException("Invalid capture backend");
    if(reply.ok&&reply.language!="vi"&&reply.language!="en"&&reply.text!=null&&reply.text.Length>0){reply.ok=false;reply.error="UNSUPPORTED_TRANSLATION_LANGUAGE";reply.text=null;}
    return reply;
   } finally {busy=false;}
  }
  public void Dispose() {if(disposed)return;disposed=true;try{if(!process.HasExited)process.Kill();}catch(InvalidOperationException){}finally{input.Dispose();process.Dispose();}}
 }
}
