using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace AniSub.Windows {
 public sealed class SubtitleCue {
  public readonly double StartMs, EndMs; public readonly string Text;
  public SubtitleCue(double start,double end,string text) { StartMs=start;EndMs=end;Text=text; }
 }
 public static class SubtitleParser {
  public static IList<SubtitleCue> Load(string path) {
   if(new FileInfo(path).Length>2097152) throw new FormatException("Subtitle file exceeds 2 MiB.");
   return Parse(File.ReadAllText(path,new UTF8Encoding(false,true)));
  }
  static double Time(string value) {
   string[] parts=value.Replace(',', '.').Split(':'); double secs=0;
   if(parts.Length<2 || parts.Length>3) throw new FormatException("Invalid subtitle timestamp.");
   for(int i=0;i<parts.Length;i++) { double n; if(!double.TryParse(parts[i],NumberStyles.AllowDecimalPoint,CultureInfo.InvariantCulture,out n)||n<0||(i>0&&n>=60)) throw new FormatException("Invalid subtitle timestamp."); secs=secs*60+n; }
   return secs*1000;
  }
  public static IList<SubtitleCue> Parse(string input) {
   if(Encoding.UTF8.GetByteCount(input)>2097152) throw new FormatException("Subtitle file exceeds 2 MiB.");
   var cues=new List<SubtitleCue>(); string normalized=input.TrimStart('\uFEFF').Replace("\r\n","\n").Replace('\r','\n');
   foreach(string block in Regex.Split(normalized,"\n[ \t]*\n")) {
    var lines=block.Split('\n'); int timing=Array.FindIndex(lines,x=>x.Contains("-->"));
    if(timing<0) continue;
    if(lines[0].StartsWith("NOTE",StringComparison.Ordinal)||lines[0]=="STYLE"||lines[0]=="REGION") continue;
    var match=Regex.Match(lines[timing],@"^\s*(\d{1,3}:\d{2}(?::\d{2})?[.,]\d{3})\s*-->\s*(\d{1,3}:\d{2}(?::\d{2})?[.,]\d{3})(?:\s+.*)?$");
    if(!match.Success) throw new FormatException("Invalid subtitle timing line.");
    double start=Time(match.Groups[1].Value),end=Time(match.Groups[2].Value);
    if(end<=start) throw new FormatException("Subtitle end must follow start.");
    string text=Regex.Replace(string.Join("\n",lines.Skip(timing+1)),"<[^>]*>","");
    text=System.Net.WebUtility.HtmlDecode(text).Trim();
    if(text.Length>4096) throw new FormatException("Subtitle text exceeds 4096 characters.");
    if(text.Length>0) cues.Add(new SubtitleCue(start,end,text));
    if(cues.Count>10000) throw new FormatException("Subtitle file exceeds 10000 cues.");
   }
   return cues.OrderBy(x=>x.StartMs).ToList();
  }
 }
 public sealed class AppWindow : Window {
 readonly MediaElement video=new MediaElement(); readonly NarrationCoordinator narration;
  readonly SourceSelectionPanel externalSources=new SourceSelectionPanel();
  readonly TextBlock original=new TextBlock(),translated=new TextBlock(),status=new TextBlock();
  readonly Slider seek=new Slider(); readonly CheckBox translate=new CheckBox(),narrate=new CheckBox(),subtitles=new CheckBox();
  readonly ComboBox language=new ComboBox(); readonly TextBox offset=new TextBox();readonly CheckBox completeLines=new CheckBox();
  readonly VoiceSelection voiceControls=new VoiceSelection();
  readonly DispatcherTimer timer=new DispatcherTimer(); readonly TranslationBridge bridge;
  IList<SubtitleCue> cues=new List<SubtitleCue>(); bool playing,closed,busy,settingSeek,dragSeeking,mediaOpened;
  long revision; string currentKey=""; bool translationBusy;
  int starts { get { return narration.Starts; } } int finishes { get { return narration.Completed+narration.Cancelled+narration.Failed; } }bool speaking { get { return narration.Speaking; } }
  int cursor; double lastPosition=-1;
  public AppWindow() : this(null,false) { }
  public AppWindow(TranslationBridge provider=null,bool smokeMode=false) {
   bridge=provider;Title="AniSub — Windows local video prototype";Width=1060;Height=760;Background=Brushes.Black;
   narration=new NarrationCoordinator(bridge,delegate{return video.Position.TotalMilliseconds;},delegate{return video.SpeedRatio;},delegate{video.Pause();},delegate{video.Play();},delegate{return video.Volume;},delegate(double value){video.Volume=value;},delegate(string message){status.Text=message;},delegate(string text){if(language.SelectedIndex==0)translated.Text=text;});
   var shell=new DockPanel();Content=shell;
   DockPanel.SetDock(externalSources,Dock.Top);shell.Children.Add(externalSources);
   externalSources.SelectionChanged+=delegate {
    playing=false;video.Pause();Invalidate();
    status.Text=externalSources.SelectedSource==null?"Nguồn bên ngoài đã bỏ chọn; chưa thu hình/âm thanh.":"Đã chọn nguồn bên ngoài; adapter phụ đề/âm thanh chưa kết nối. Player bên dưới chỉ dùng thử nghiệm local.";
   };
   var controls=new WrapPanel { Background=Brushes.WhiteSmoke };DockPanel.SetDock(controls,Dock.Top);shell.Children.Add(controls);
   AddButton(controls,"Open video",delegate { var d=new OpenFileDialog { Filter="Video|*.mp4;*.wmv;*.avi;*.mkv|All files|*.*" };if(d.ShowDialog()==true) OpenVideo(d.FileName); });
   AddButton(controls,"Open SRT/VTT",delegate { var d=new OpenFileDialog { Filter="Subtitles|*.srt;*.vtt" };if(d.ShowDialog()==true) { try { var loaded=SubtitleParser.Load(d.FileName);cues=loaded;Invalidate();cursor=0;lastPosition=-1;status.Text="Loaded "+cues.Count+" subtitle cues."; } catch(Exception e) { status.Text="Subtitle import failed: "+e.GetType().Name; } } });
   AddButton(controls,"Play / pause",delegate { TogglePlayback(); });
   AddButton(controls,"Stop",delegate { playing=false;video.Stop();Invalidate();cursor=0;lastPosition=-1; });
   AddButton(controls,"Prepare offline models",delegate { PrepareModels(); });
   subtitles.Content="Subtitles";subtitles.IsChecked=true;controls.Children.Add(subtitles);subtitles.Checked+=delegate { Invalidate();cursor=0;lastPosition=-1; };subtitles.Unchecked+=delegate { Invalidate(); };
   language.Items.Add("en → vi");language.Items.Add("vi → vi (no translation)");language.SelectedIndex=0;controls.Children.Add(language);language.SelectionChanged+=delegate { Invalidate(); };
   translate.Content="Translate EN→VI offline";controls.Children.Add(translate);translate.Checked+=delegate { Invalidate(); };translate.Unchecked+=delegate { Invalidate(); };
   narrate.Content="Vietnamese narration";controls.Children.Add(narrate);narrate.Checked+=delegate { Invalidate(); };narrate.Unchecked+=delegate { Invalidate(); };
   completeLines.Content="Read complete / sync wait";completeLines.IsChecked=true;controls.Children.Add(completeLines);completeLines.Checked+=delegate{Invalidate();};completeLines.Unchecked+=delegate{Invalidate();};
   controls.Children.Add(voiceControls);voiceControls.Changed+=VoiceChanged;
   AddButton(controls,"Nạp giọng Bắc / Nam, nam / nữ",delegate { LoadVoices(); });
   controls.Children.Add(new TextBlock { Text=" Speed ",VerticalAlignment=VerticalAlignment.Center });
   var speed=new ComboBox { Width=65 };foreach(double x in new double[]{0.5,0.75,1,1.25,1.5,2}) speed.Items.Add(x);speed.SelectedItem=1.0;controls.Children.Add(speed);
   speed.SelectionChanged+=delegate { Invalidate();video.SpeedRatio=(double)speed.SelectedItem; };
   controls.Children.Add(new TextBlock { Text=" Offset ms ",VerticalAlignment=VerticalAlignment.Center });offset.Text="0";offset.Width=65;controls.Children.Add(offset);offset.TextChanged+=delegate { Invalidate();cursor=0;lastPosition=-1; };
   var bottom=new StackPanel { Background=Brushes.WhiteSmoke };DockPanel.SetDock(bottom,Dock.Bottom);shell.Children.Add(bottom);bottom.Children.Add(seek);bottom.Children.Add(status);
   seek.PreviewMouseLeftButtonDown+=delegate { dragSeeking=true;Invalidate(); };
   seek.PreviewMouseLeftButtonUp+=delegate { if(dragSeeking) { dragSeeking=false;SeekTo(seek.Value); } };
   seek.ValueChanged+=delegate { if(!settingSeek&&!dragSeeking&&mediaOpened) SeekTo(seek.Value); };
   var visual=new Grid();shell.Children.Add(visual);video.LoadedBehavior=MediaState.Manual;video.UnloadedBehavior=MediaState.Close;visual.Children.Add(video);
   var captions=new StackPanel { VerticalAlignment=VerticalAlignment.Bottom,Margin=new Thickness(30,0,30,24),Background=new SolidColorBrush(Color.FromArgb(185,0,0,0)),IsHitTestVisible=false };
   original.Foreground=Brushes.White;translated.Foreground=Brushes.Yellow;foreach(var text in new[]{original,translated}) { text.FontSize=25;text.TextWrapping=TextWrapping.Wrap;text.TextAlignment=TextAlignment.Center;captions.Children.Add(text); }visual.Children.Add(captions);
   video.MediaOpened+=delegate { mediaOpened=true;seek.Maximum=video.NaturalDuration.HasTimeSpan?video.NaturalDuration.TimeSpan.TotalMilliseconds:0;status.Text="Video ready. Translation and narration require explicit enablement."; };
   video.MediaEnded+=delegate { playing=false;narration.EndInput();status.Text="Video ended; accepted narration may finish naturally."; };
   video.MediaFailed+=delegate { playing=false;Invalidate();status.Text="Video cannot play with installed Windows codecs."; };
   timer.Interval=TimeSpan.FromMilliseconds(20);timer.Tick+=delegate { Tick(); };timer.Start();
   Closed+=delegate { closed=true;timer.Stop();externalSources.Dispose();revision++;narration.Close();video.Close(); };
   status.Text=bridge==null?"Offline provider unavailable. Original subtitles remain usable.":"Offline provider configured. No cloud fallback.";
  }
  static void AddButton(Panel parent,string text,Action action) { var b=new Button { Content=text,Margin=new Thickness(3),Padding=new Thickness(8,4,8,4) };b.Click+=delegate { action(); };parent.Children.Add(b); }
  public void VoiceChanged(string voice) {
   if(bridge==null)return;
   if(voice==null) {narrate.IsChecked=false;Invalidate();status.Text="VOICE_UNAVAILABLE: chọn bộ lọc hoặc giọng khác.";return;}
   if(bridge.SelectedVoice==voice)return;
   bridge.SelectedVoice=voice;Invalidate();status.Text="Đã đổi giọng; câu đang đọc/hàng đợi cũ đã được hủy mềm.";
  }
  async void LoadVoices() {
   if(bridge==null){status.Text="MODEL_MISSING: offline provider not configured.";return;}
   if(busy||narration.Busy||translationBusy){status.Text="Chờ xử lý câu hiện tại rồi nạp danh sách giọng.";return;}
   busy=true;Invalidate();long stamp=revision;
   try {var reply=await bridge.RequestAsync("voice-catalog","");if(closed||stamp!=revision)return;if(!reply.Ok)throw new InvalidDataException("Voice catalog unavailable");voiceControls.SetCatalog(reply.Voices,reply.DefaultVoice);status.Text="Giọng vùng miền theo metadata model. Tiếng Anh/tên riêng cần nghe kiểm tra; Trung chưa có ở Nano hiện tại.";}
   catch(Exception e){if(!closed&&stamp==revision)status.Text="VOICE_UNAVAILABLE: "+e.GetType().Name;}
   finally {busy=false;if(!closed)Invalidate();}
  }
  public void OpenVideo(string path) { if(externalSources.SelectedSource!=null)externalSources.ClearSelection();playing=false;cues=new List<SubtitleCue>();Invalidate();video.Close();mediaOpened=false;cursor=0;lastPosition=-1;video.Source=new Uri(Path.GetFullPath(path));video.Play();video.Pause(); }
  public void TogglePlayback() { if(externalSources.SelectedSource!=null){status.Text="Nguồn bên ngoài chưa có adapter. Bỏ chọn nguồn hoặc Open video để thử thuyết minh local.";return;}if(playing&&!narration.Faulted) { playing=false;revision++;narration.SetPlaying(false);video.Pause(); } else { playing=true;video.Play();narration.SetPlaying(true); } }
  public void SeekTo(double milliseconds) { video.Position=TimeSpan.FromMilliseconds(Math.Max(0,milliseconds));Invalidate();cursor=0;lastPosition=-1; }
  double OffsetMs() { double value;if(!double.TryParse(offset.Text,NumberStyles.Integer,CultureInfo.InvariantCulture,out value)||Math.Abs(value)>3600000)value=0;return value; }
  void Invalidate() { revision++;currentKey="";original.Text="";translated.Text="";if(playing)video.Play();if(narration!=null)narration.Reset(cues,OffsetMs(),externalSources.SelectedSource==null&&!busy&&subtitles.IsChecked==true&&narrate.IsChecked==true&&(language.SelectedIndex==1||translate.IsChecked==true),language.SelectedIndex==0,completeLines.IsChecked==true,playing); }
  void Tick() {
   if(closed)return;narration.Tick();if(externalSources.SelectedSource!=null||!mediaOpened)return; double position=video.Position.TotalMilliseconds;settingSeek=true;seek.Value=position;settingSeek=false;
   if(subtitles.IsChecked!=true) return;
   double shift;if(!double.TryParse(offset.Text,NumberStyles.Integer,CultureInfo.InvariantCulture,out shift)||Math.Abs(shift)>3600000) shift=0;
   double at=position-shift;if(at<lastPosition) cursor=0;lastPosition=at;
   while(cursor<cues.Count&&cues[cursor].EndMs<=at) cursor++;
   var active=new List<SubtitleCue>();for(int i=cursor;i<cues.Count&&cues[i].StartMs<=at&&active.Count<32;i++) if(cues[i].EndMs>at) active.Add(cues[i]);
   string text=string.Join("\n",active.Select(x=>x.Text));string key=string.Join(";",active.Select(x=>x.StartMs.ToString(CultureInfo.InvariantCulture)+":"+x.EndMs.ToString(CultureInfo.InvariantCulture)));
   if(key==currentKey) return;currentKey=key;original.Text=text;translated.Text="";
   if(text.Length==0)return;
   if(!playing||!(translate.IsChecked==true||narrate.IsChecked==true)) return;
   if(language.SelectedIndex==0&&translate.IsChecked!=true) { status.Text="Enable EN→VI translation before Vietnamese narration of English captions.";return; }
   if(text.Length>4096) { status.Text="Active cue text exceeds bounded provider payload.";return; }
   if(bridge==null) { status.Text="MODEL_MISSING: offline provider not configured.";return; }
   if(narrate.IsChecked!=true&&!busy&&!translationBusy)ProcessCaption(text,key,revision);
  }
  async void ProcessCaption(string text,string key,long generation) {
   translationBusy=true;
   try {
    if(language.SelectedIndex==0) { var reply=await bridge.RequestAsync("translate",text);if(closed||generation!=revision||key!=currentKey)return;if(!reply.Ok){status.Text="Translation unavailable: "+reply.Error;return;}translated.Text=reply.Text; }
   } catch(Exception e) { if(!closed&&generation==revision) status.Text="Provider failed: "+e.GetType().Name+(e is InvalidDataException?" ("+e.Message+")":""); }
   finally { translationBusy=false; }
  }
  async void PrepareModels() {
   if(bridge==null) { status.Text="MODEL_MISSING: offline provider not configured.";return; }
   if(busy||narration.Busy||translationBusy) { status.Text="Provider busy. Preparation waits until idle.";return; }
   busy=true;narration.Reset(cues,OffsetMs(),false,false,true,playing);status.Text="Preparing local models; playback remains responsive.";
   try { long stamp=revision;while(narration.Busy){await Task.Delay(25);if(closed||stamp!=revision)return;}var reply=await bridge.RequestAsync("probe","");if(!closed&&stamp==revision) {status.Text=reply.Ok?"Offline EN→VI translation and Vietnamese narration ready.":"Provider unavailable: "+reply.Error;if(reply.Ok){var catalog=await bridge.RequestAsync("voice-catalog","");if(!closed&&stamp==revision&&catalog.Ok)voiceControls.SetCatalog(catalog.Voices,catalog.DefaultVoice);}} }
   catch(Exception e) { if(!closed) status.Text="Preparation failed: "+e.GetType().Name; }
   finally { busy=false;if(!closed)Invalidate(); }
  }
  public string NarrationDiagnostic {
   get { return "cues="+cues.Count+" positionMs="+video.Position.TotalMilliseconds.ToString("F0",CultureInfo.InvariantCulture)+" playing="+playing+" "+narration.Diagnostic+" closed="+closed; }
  }
  void RequireNarrationRevision(long generation) {
   if(closed||generation!=revision) throw new OperationCanceledException("Narration launch cancelled by playback/input change or window close.");
  }
  public async Task<string> StartVietnameseNarration(string videoPath,string subtitlePath,double startMs) {
   Dispatcher.VerifyAccess();
   if(closed) throw new OperationCanceledException("Narration window is closed.");
   if(bridge==null) throw new InvalidOperationException("Offline provider is not configured.");
   if(busy) throw new InvalidOperationException("Offline provider is already busy.");
   if(double.IsNaN(startMs)||double.IsInfinity(startMs)||startMs<0) throw new ArgumentOutOfRangeException("startMs");
   if(!File.Exists(videoPath)) throw new FileNotFoundException("Narration video is missing.");
   var loaded=SubtitleParser.Load(subtitlePath);
   if(loaded.Count==0) throw new InvalidDataException("Narration subtitle file contains no cues.");
   playing=false;cues=new List<SubtitleCue>();Invalidate();video.Pause();
   long generation=revision;busy=true;status.Text="Preparing offline Vietnamese narration for local subtitles.";
   try {
    var warm=bridge.RequestAsync("probe-voice","");
    DateTime warmDeadline=DateTime.UtcNow.AddSeconds(110);
    while(!warm.IsCompleted&&DateTime.UtcNow<warmDeadline) { await Task.Delay(100);RequireNarrationRevision(generation); }
    RequireNarrationRevision(generation);
    if(!warm.IsCompleted) throw new TimeoutException("Offline model preparation exceeded launch budget.");
    var ready=await warm;RequireNarrationRevision(generation);
    if(!ready.Ok) throw new InvalidOperationException("Offline model preparation failed: "+ready.Error);
   } finally { busy=false; }
   RequireNarrationRevision(generation);
   language.SelectedIndex=1;translate.IsChecked=false;narrate.IsChecked=true;subtitles.IsChecked=true;offset.Text="0";
   OpenVideo(videoPath);generation=revision;
   for(int i=0;i<100&&!mediaOpened;i++) { await Task.Delay(100);RequireNarrationRevision(generation); }
   RequireNarrationRevision(generation);
   if(!mediaOpened) throw new InvalidOperationException("Narration video MediaOpened was not observed within 10 seconds.");
   if(video.NaturalDuration.HasTimeSpan&&startMs>=video.NaturalDuration.TimeSpan.TotalMilliseconds) throw new ArgumentOutOfRangeException("startMs","Narration start must be before video end.");
   // OpenVideo clears prior captions: import the verified real track only after it is ready.
   cues=loaded;cursor=0;lastPosition=-1;SeekTo(startMs);video.SpeedRatio=1;
   Invalidate();generation=revision;await narration.PrimeAsync();RequireNarrationRevision(generation);
   int baseline=starts;TogglePlayback();generation=revision;
   DateTime deadline=DateTime.UtcNow.AddSeconds(60);
   while(starts<=baseline&&DateTime.UtcNow<deadline) { await Task.Delay(100);RequireNarrationRevision(generation); }
   RequireNarrationRevision(generation);
   if(starts<=baseline) throw new TimeoutException("No Vietnamese speech output request observed within 60 seconds. "+NarrationDiagnostic);
   return "PASS real Vietnamese subtitle narration output playback requested; "+NarrationDiagnostic;
  }
  public async Task<string> RunSmoke(string path) {
   var run=RunSmokeCore(path);
   if(await Task.WhenAny(run,Task.Delay(120000))!=run) throw new TimeoutException("Windows smoke exceeded 120-second budget.");
   return await run;
  }
  async Task<string> RunSmokeCore(string path) {
   DateTime deadline=DateTime.UtcNow.AddSeconds(120);
   if(bridge!=null) {
    var warm=bridge.RequestAsync("probe","");
    if(await Task.WhenAny(warm,Task.Delay(110000))!=warm) throw new TimeoutException("Model warm-up exceeded smoke budget.");
    var ready=await warm;if(!ready.Ok) throw new InvalidOperationException("Offline model probe failed: "+ready.Error);
   }
   OpenVideo(path);for(int i=0;i<100&&!mediaOpened;i++) await Task.Delay(100);
   if(!mediaOpened) throw new InvalidOperationException("MediaOpened not observed.");
   double baselineVolume=video.Volume;
   if(bridge!=null) { cues=new List<SubtitleCue> { new SubtitleCue(0,12000,"Hello. This is a test of offline video translation.") };translate.IsChecked=true;narrate.IsChecked=true; }
   if(bridge!=null) { Invalidate();await narration.PrimeAsync(); }
   TogglePlayback();await Task.Delay(1000);double advanced=video.Position.TotalMilliseconds;
   if(advanced<100) throw new InvalidOperationException("Media clock did not advance.");TogglePlayback();double paused=video.Position.TotalMilliseconds;await Task.Delay(250);if(Math.Abs(paused-video.Position.TotalMilliseconds)>100) throw new InvalidOperationException("Pause failed.");
   SeekTo(500);video.SpeedRatio=1.25;TogglePlayback();await Task.Delay(300);TogglePlayback();
   if(video.Position.TotalMilliseconds<500) throw new InvalidOperationException("Seek failed.");
   if(bridge!=null) {
    SeekTo(0);video.SpeedRatio=1;TogglePlayback();
    while((translated.Text.Length==0||starts==0)&&DateTime.UtcNow<deadline&&video.Position.TotalMilliseconds<12000) await Task.Delay(100);
    if(translated.Text.Length==0||starts==0) throw new InvalidOperationException("Real translation/speech output not observed before cue deadline; translated="+(translated.Text.Length>0)+" starts="+starts+" busy="+busy+" status="+status.Text);
    TogglePlayback();await Task.Delay(450);if(speaking||Math.Abs(video.Volume-baselineVolume)>0.001||starts!=finishes) throw new InvalidOperationException("Pause did not stop speech/restore ducking.");
   }
   return "PASS MediaOpened/play/pause/seek/speed"+(bridge==null?"; caption-only":"; real EN→VI overlay and Vietnamese WAV output playback; no acoustic hearing assertion")+"; speech starts="+starts+" finishes="+finishes;
  }
 }
}
