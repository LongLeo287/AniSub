using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace AniSub.Windows
{
    // Discovery is deliberately read-only. A selected window is NOT evidence of video or capture consent.
    public sealed class SourceCandidate
    {
        public int ProcessId { get; private set; }
        public long StartTimeUtcTicks { get; private set; }
        public IntPtr WindowHandle { get; private set; }
        public string ProcessName { get; private set; }
        public string WindowTitle { get; private set; }
        public bool IsBrowser { get { return SourceDiscovery.IsBrowserName(ProcessName); } }
        public SourceCandidate(int pid, long startTimeUtcTicks, IntPtr window, string name, string title)
        {
            if (pid <= 0 || startTimeUtcTicks <= 0 || window == IntPtr.Zero) throw new ArgumentException("Invalid source identity.");
            ProcessId = pid; StartTimeUtcTicks = startTimeUtcTicks; WindowHandle = window;
            ProcessName = name ?? ""; WindowTitle = (title ?? "").Substring(0, Math.Min(120, (title ?? "").Length));
        }
        public bool MatchesIdentity(SourceCandidate other)
        {
            return other != null && ProcessId == other.ProcessId && StartTimeUtcTicks == other.StartTimeUtcTicks && WindowHandle == other.WindowHandle;
        }
        public override string ToString() { return ProcessName + " [" + ProcessId + "] — " + WindowTitle; }
    }

    public static class SourceDiscovery
    {
        public const int MaximumWindows = 512;
        public const int MaximumCandidates = 128;
        private delegate bool EnumWindowsCallback(IntPtr window, IntPtr state);
        [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsCallback callback, IntPtr state);
        [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr window);
        [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr window);
        [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr window, StringBuilder text, int maximum);
        [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
        public static bool IsBrowserName(string name)
        {
            return string.Equals(name, "chrome", StringComparison.OrdinalIgnoreCase) || string.Equals(name, "msedge", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(name, "firefox", StringComparison.OrdinalIgnoreCase) || string.Equals(name, "brave", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(name, "opera", StringComparison.OrdinalIgnoreCase) || string.Equals(name, "vivaldi", StringComparison.OrdinalIgnoreCase);
        }
        private static SourceCandidate ReadWindow(IntPtr window, int excludedPid)
        {
            try
            {
                if (window == IntPtr.Zero || !IsWindow(window) || !IsWindowVisible(window)) return null;
                uint pid; GetWindowThreadProcessId(window, out pid);
                if (pid == 0 || pid > int.MaxValue || pid == excludedPid) return null;
                StringBuilder title = new StringBuilder(121);
                if (GetWindowText(window, title, title.Capacity) <= 0) return null;
                using (Process process = Process.GetProcessById((int)pid))
                {
                    if (process.HasExited) return null;
                    return new SourceCandidate((int)pid, process.StartTime.ToUniversalTime().Ticks, window, process.ProcessName, title.ToString());
                }
            }
            catch (ArgumentException) { return null; }
            catch (InvalidOperationException) { return null; }
            catch (System.ComponentModel.Win32Exception) { return null; }
            catch (NotSupportedException) { return null; }
        }
        public static IList<SourceCandidate> Discover(int excludedPid)
        {
            List<SourceCandidate> candidates = new List<SourceCandidate>(); int visited = 0;
            EnumWindows(delegate(IntPtr window, IntPtr state)
            {
                if (++visited > MaximumWindows || candidates.Count >= MaximumCandidates) return false;
                SourceCandidate candidate = ReadWindow(window, excludedPid);
                if (candidate != null) candidates.Add(candidate);
                return true;
            }, IntPtr.Zero);
            return candidates.AsReadOnly();
        }
        public static SourceCandidate Foreground(int excludedPid) { return ReadWindow(GetForegroundWindow(), excludedPid); }
        public static bool Revalidate(SourceCandidate source)
        {
            return source != null && source.MatchesIdentity(ReadWindow(source.WindowHandle, -1));
        }
    }

    public sealed class SourceSelectionPanel : UserControl, IDisposable
    {
        private readonly ComboBox choices;
        private readonly CheckBox auto;
        private readonly TextBlock status;
        private readonly DispatcherTimer timer;
        private readonly int ownPid;
        private SourceCandidate recommendation;
        private bool disposed;
        public SourceCandidate SelectedSource { get; private set; }
        public event EventHandler SelectionChanged;
        public SourceSelectionPanel()
        {
            using (Process process = Process.GetCurrentProcess()) ownPid = process.Id;
            StackPanel layout = new StackPanel { Margin = new Thickness(8) };
            layout.Children.Add(new TextBlock { Text = "Nguồn bên ngoài (chưa thu hình/âm thanh)", FontWeight = FontWeights.SemiBold });
            auto = new CheckBox { Content = "Auto: gợi ý cửa sổ đang dùng", IsChecked = true, Margin = new Thickness(0, 5, 0, 5) };
            layout.Children.Add(auto);
            choices = new ComboBox { MinWidth = 260, MaxWidth = 640, Margin = new Thickness(0, 0, 0, 5) };
            layout.Children.Add(choices);
            StackPanel actions = new StackPanel { Orientation = Orientation.Horizontal };
            Button refresh = new Button { Content = "Làm mới", Margin = new Thickness(0, 0, 6, 0) };
            Button use = new Button { Content = "Chọn nguồn" };
            Button clear = new Button { Content = "Bỏ chọn", Margin = new Thickness(6, 0, 0, 0) };
            refresh.Click += delegate { RefreshSources(); };
            use.Click += delegate { Bind(choices.SelectedItem as SourceCandidate); };
            clear.Click += delegate { ClearSelection(); };
            actions.Children.Add(refresh); actions.Children.Add(use); actions.Children.Add(clear); layout.Children.Add(actions);
            status = new TextBlock { Text = "Chọn app/cửa sổ; việc chọn chưa bật thuyết minh.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 5, 0, 0), MaxWidth = 640 };
            layout.Children.Add(status); Content = layout;
            timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            timer.Tick += delegate { Poll(); };
            Loaded += delegate { if (!disposed) { RefreshSources(); timer.Start(); } };
            Unloaded += delegate { timer.Stop(); };
        }
        public void RefreshSources()
        {
            if (disposed) return;
            SourceCandidate previous = choices.SelectedItem as SourceCandidate;
            IList<SourceCandidate> sources = SourceDiscovery.Discover(ownPid);
            choices.ItemsSource = sources;
            foreach (SourceCandidate candidate in sources)
                if (candidate.MatchesIdentity(previous)) { choices.SelectedItem = candidate; break; }
            if (choices.SelectedItem == null && sources.Count > 0) choices.SelectedIndex = 0;
            Poll();
        }
        private void Poll()
        {
            if (disposed) return;
            if (SelectedSource != null && !SourceDiscovery.Revalidate(SelectedSource))
            {
                SelectedSource = null; RaiseChanged();
                status.Text = "Nguồn đã đóng/thay đổi. Chọn lại để tránh nhầm ứng dụng.";
                return;
            }
            if (auto.IsChecked != true || SelectedSource != null) return;
            SourceCandidate foreground = SourceDiscovery.Foreground(ownPid);
            if (foreground == null || foreground.MatchesIdentity(recommendation)) return;
            recommendation = foreground;
            // Only recommend; never switch an explicitly bound source on foreground changes.
            List<SourceCandidate> sources = new List<SourceCandidate>(SourceDiscovery.Discover(ownPid));
            foreach (SourceCandidate candidate in sources)
                if (candidate.MatchesIdentity(foreground)) { choices.ItemsSource = sources; choices.SelectedItem = candidate; break; }
            status.Text = "Gợi ý: " + foreground.ProcessName + ". Chưa xác minh có video; bấm Chọn nguồn.";
        }
        public bool Bind(SourceCandidate source)
        {
            if (disposed || source == null || source.ProcessId == ownPid || !SourceDiscovery.Revalidate(source))
            {
                status.Text = "Không thể xác nhận nguồn. Làm mới và chọn lại."; return false;
            }
            SelectedSource = source;
            status.Text = source.IsBrowser ? "Đã chọn trình duyệt, chưa chọn tab/URL. Cần companion để đọc phụ đề tab; chưa thu hình/âm thanh." :
                "Đã chọn cửa sổ. Chưa xác minh video/phụ đề và chưa thu hình/âm thanh.";
            RaiseChanged(); return true;
        }
        public void ClearSelection()
        {
            SelectedSource = null; recommendation = null; status.Text = "Đã bỏ chọn nguồn; chưa thu hình/âm thanh."; RaiseChanged();
        }
        private void RaiseChanged() { EventHandler handler = SelectionChanged; if (handler != null) handler(this, EventArgs.Empty); }
        public void Dispose() { if (disposed) return; disposed = true; timer.Stop(); SelectedSource = null; }
    }
}
