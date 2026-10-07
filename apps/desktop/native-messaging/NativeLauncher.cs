using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;

public static class AniSubNativeLauncher {
    public static int Main(string[] args) {
        if (args.Length < 1 || !System.Text.RegularExpressions.Regex.IsMatch(args[0], @"^chrome-extension://[a-p]{32}/?$")) return 1;
        Process worker = null;
        try {
            string folder = AppDomain.CurrentDomain.BaseDirectory;
            string python = File.ReadAllText(Path.Combine(folder, "python-path.txt"), Encoding.UTF8).Trim();
            string script = Path.GetFullPath(Path.Combine(folder, "..", "native_host.py"));
            if (!File.Exists(python) || !File.Exists(script)) return 1;
            worker = new Process();
            worker.StartInfo = new ProcessStartInfo(python, "-I -u \"" + script + "\" \"" + args[0] + "\"") {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            worker.Start();
            Process owned = worker;
            Task.Run(delegate { try { Console.OpenStandardInput().CopyTo(owned.StandardInput.BaseStream); } catch {} finally { try { owned.StandardInput.Close(); } catch {} } });
            Task.Run(delegate { try { owned.StandardError.BaseStream.CopyTo(Stream.Null); } catch {} });
            Stream output = Console.OpenStandardOutput();
            worker.StandardOutput.BaseStream.CopyTo(output); output.Flush();
            worker.WaitForExit(); return worker.ExitCode;
        } catch { return 1; }
        finally { if (worker != null) { try { if (!worker.HasExited) worker.Kill(); } catch {} worker.Dispose(); } }
    }
}
