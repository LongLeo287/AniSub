using System;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace AniSub.Windows {
 public sealed class CapturePanel : StackPanel,IDisposable {
  readonly SourceSelectionPanel sources;readonly Func<string,string,bool> output;readonly Action stopOutput;readonly Action<bool> activity;
  readonly string python,worker,model;readonly CheckBox consent=new CheckBox();readonly ComboBox mode=new ComboBox(),language=new ComboBox();readonly TextBlock status=new TextBlock();
  readonly DispatcherTimer timer=new DispatcherTimer();CaptureBridge bridge;long revision;bool enabled,busy,closed;string previous="";double lastTextMs;readonly Stopwatch clock=Stopwatch.StartNew();
  CaptureReply pending;long pendingRevision;
  public event Action MetricsChanged;
  public string MetricsSummary {get;private set;}
  public bool Active {get{return enabled;}}
  public CapturePanel(string pythonPath,string workerPath,string asrModel,SourceSelectionPanel selector,Func<string,string,bool> onText,Action onStop,Action<bool> onActivity) {
   python=pythonPath;worker=workerPath;model=asrModel;sources=selector;output=onText;stopOutput=onStop;activity=onActivity;Margin=new Thickness(8);
   Children.Add(new TextBlock{Text="Đọc nguồn đã chọn — xử lý local, có độ trễ",FontWeight=FontWeights.SemiBold});
   mode.Items.Add("OCR phụ đề trong hình");mode.Items.Add("ASR âm thanh ứng dụng");mode.SelectedIndex=0;Children.Add(mode);
   language.Items.Add("Auto Việt / Anh (OCR thử nghiệm)");language.Items.Add("Tiếng Việt");language.Items.Add("English");language.Items.Add("日本語 / Japanese");language.SelectedIndex=0;Children.Add(language);
   consent.Content="Cho phép đọc vùng phụ đề / âm thanh CHỈ ứng dụng đã chọn";Children.Add(consent);
   var actions=new WrapPanel();var start=new Button{Content="Bắt đầu đọc nguồn",Margin=new Thickness(3)};var stop=new Button{Content="Dừng đọc nguồn",Margin=new Thickness(3)};actions.Children.Add(start);actions.Children.Add(stop);Children.Add(actions);
   status.Text="Chưa thu hình/âm thanh. OCR dùng vùng 35% phía dưới cửa sổ; ASR không thu toàn hệ thống.";status.TextWrapping=TextWrapping.Wrap;Children.Add(status);
   start.Click+=delegate{StartCapture();};stop.Click+=delegate{StopCapture();};consent.Unchecked+=delegate{StopCapture();};mode.SelectionChanged+=delegate{StopCapture();language.Items[0]=mode.SelectedIndex==0?"Auto Việt / Anh (OCR thử nghiệm)":"Auto nhận diện ngôn ngữ";};language.SelectionChanged+=delegate{StopCapture();};sources.SelectionChanged+=delegate{StopCapture();};
   timer.Interval=TimeSpan.FromMilliseconds(200);timer.Tick+=delegate{Poll();};Unloaded+=delegate{StopCapture();};
  }
  void StartCapture() {
   if(closed||enabled)return;if(consent.IsChecked!=true){status.Text="Cần xác nhận quyền đọc nguồn trước.";return;}
   var source=sources.SelectedSource;if(source==null||!SourceDiscovery.Revalidate(source)){status.Text="Chọn ứng dụng còn hoạt động trước.";return;}
   try{bridge=new CaptureBridge(python,worker,model);revision++;enabled=true;previous="";stopOutput();activity(true);timer.Start();Poll();}catch(Exception e){status.Text="Capture chưa sẵn sàng: "+e.GetType().Name;StopCapture();}
  }
  async void Poll() {
   if(closed||!enabled||busy)return;var source=sources.SelectedSource;if(source==null||!SourceDiscovery.Revalidate(source)){StopCapture();status.Text="Nguồn đã đóng/thay đổi.";return;}
   if(pending!=null){if(pendingRevision!=revision){pending=null;}else if(!output(pending.text,pending.language)){status.Text="Đang chờ đọc đủ lời đã nhận; máy chưa theo kịp. Âm thanh đang chờ có giới hạn, quá tải kéo dài có thể mất đoạn.";return;}else{ShowMetrics(pending);pending=null;}}
   busy=true;long stamp=revision;var owned=bridge;string operation=mode.SelectedIndex==0?"ocr":"asr";string lang=new[]{"auto","vi","en","ja"}[language.SelectedIndex];
   try {
    var reply=await owned.ReadAsync(source,operation,lang);
    if(closed||!enabled||stamp!=revision||!source.MatchesIdentity(sources.SelectedSource)||!SourceDiscovery.Revalidate(source))return;
    if(!reply.ok){status.Text="Không đọc được nguồn: "+reply.error;StopCapture();return;}
    MetricsSummary="Nghe "+(reply.backend=="cuda-float16"?"GPU":reply.backend=="cpu-int8-fallback"?"CPU (GPU không sẵn sàng)":reply.backend=="cpu-int8"?"CPU":"")+": "+reply.inferenceMs.ToString("0")+" ms • chờ nghe: "+reply.queuedChunks+" • mất đoạn âm thanh: "+reply.droppedChunks;
    if(MetricsChanged!=null)MetricsChanged();
    string text=(reply.text??"").Trim();double now=clock.Elapsed.TotalMilliseconds;
    if(text.Length>0&&(reply.origin=="asr"||text!=previous||now-lastTextMs>10000)){previous=text;lastTextMs=now;if(!output(text,reply.language)){pending=reply;pending.text=text;pendingRevision=stamp;status.Text="Đang chờ hàng đợi thuyết minh; giữ lại lời đã nhận, không tự bỏ sau 5 giây.";}else ShowMetrics(reply);}
    else if(reply.droppedChunks>0)status.Text="ASR quá tải; đã bỏ "+reply.droppedChunks+" chunk cũ. Không bảo đảm đọc đủ lời thoại.";
   }catch(Exception e){if(!closed&&stamp==revision){status.Text="Capture dừng: "+e.GetType().Name;StopCapture();}}
   finally{busy=false;}
  }
  void ShowMetrics(CaptureReply reply){status.Text="Đã nhận "+reply.origin+" • nhận dạng "+reply.inferenceMs.ToString("0")+" ms / "+reply.audioMs.ToString("0")+" ms âm thanh • chờ nghe: "+reply.queuedChunks+" • đoạn âm thanh mất do quá tải: "+reply.droppedChunks+".";}
  public void StopCapture(){revision++;enabled=false;timer.Stop();previous="";pending=null;if(activity!=null)activity(false);if(bridge!=null){bridge.Dispose();bridge=null;}if(stopOutput!=null)stopOutput();}
  public void Dispose(){if(closed)return;closed=true;StopCapture();}
 }
}
