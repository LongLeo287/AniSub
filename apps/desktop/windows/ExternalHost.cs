using System;
using System.IO;
using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace AniSub.Windows {
 public sealed class ExternalHost : Window {
  readonly ExternalMedia media; readonly TextBlock state=new TextBlock {TextWrapping=TextWrapping.Wrap};
  readonly TextBlock caption=new TextBlock {TextWrapping=TextWrapping.Wrap,MaxHeight=100};
  readonly SourceSelectionPanel sources=new SourceSelectionPanel(); readonly VoiceSelection voices=new VoiceSelection();
  readonly CapturePanel capture;
  readonly TextBlock captureState=new TextBlock{TextWrapping=TextWrapping.Wrap};
  readonly JavaScriptSerializer json=new JavaScriptSerializer {MaxJsonLength=65536,RecursionLimit=16};
  volatile bool closed; bool inputEnabled=true; NamedPipeServerStream pipe; readonly object pipeLock=new object();
  public ExternalMedia Media {get{return media;}}
  public SourceCandidate SelectedSource {get{return sources.SelectedSource;}}
  public SourceSelectionPanel SourceSelector {get{return sources;}}
  public bool CaptureActive {get;set;}
  public bool AcceptRecognition(string text,string language){return inputEnabled&&CaptureActive&&media.AcceptRecognizedText(text,language);}
  public void AddCapturePanel(UIElement element){((StackPanel)((ScrollViewer)Content).Content).Children.Insert(3,element);Height=650;}
  public ExternalHost(string python,string worker,string modelDir,string ttsDir) {
   Title="AniSub • Thuyết minh nền";Width=600;Height=390;Topmost=true;ResizeMode=ResizeMode.CanMinimize;
   Background=new SolidColorBrush(Color.FromRgb(24,27,34));Foreground=Brushes.White;
   var panel=new StackPanel {Margin=new Thickness(14)};Content=new ScrollViewer{Content=panel,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
   panel.Children.Add(new TextBlock {Text="AniSub • video ở ứng dụng riêng",FontSize=20});
   panel.Children.Add(new TextBlock {Text="Chrome: bật companion trên video. Cửa sổ khác: chọn nguồn, rồi bật OCR/ASR riêng; không tự thu toàn màn hình.",TextWrapping=TextWrapping.Wrap});
   panel.Children.Add(sources);panel.Children.Add(voices);
   var buttons=new WrapPanel();panel.Children.Add(buttons);
   var load=new Button {Content="Nạp danh sách giọng",Margin=new Thickness(4)};buttons.Children.Add(load);
   var stop=new Button {Content="Dừng thuyết minh",Margin=new Thickness(4)};buttons.Children.Add(stop);
   var resume=new Button {Content="Cho phép nguồn",Margin=new Thickness(4)};buttons.Children.Add(resume);
   var minimize=new Button {Content="Thu gọn",Margin=new Thickness(4)};buttons.Children.Add(minimize);
   panel.Children.Add(state);panel.Children.Add(caption);
   media=new ExternalMedia(new TranslationBridge(python,worker,modelDir,ttsDir));
   var contentPanel=new StackPanel();panel.Children.Add(contentPanel);
   contentPanel.Children.Add(new TextBlock{Text="Hồ sơ nội dung (chọn thủ công, không tự suy đoán từ ứng dụng)",TextWrapping=TextWrapping.Wrap});
   var profile=new ComboBox{ItemsSource=new[]{"Chung","Công nghệ","Phim / hội thoại","Game PC"},SelectedIndex=0};contentPanel.Children.Add(profile);
   contentPanel.Children.Add(new TextBlock{Text="Thuật ngữ: tiếng gốc | cách đọc Việt | bản dịch cần sửa (ngăn bằng ;). Mỗi dòng một mục, tối đa 32. Chỉ sửa khi cả từ gốc và bản dịch khớp; không tự đoán nghĩa.",TextWrapping=TextWrapping.Wrap});
   var terms=new TextBox{AcceptsReturn=true,Height=70,MaxLength=12000,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};contentPanel.Children.Add(terms);
   var applyTerms=new Button{Content="Áp dụng hồ sơ / thuật ngữ (hủy lời đang chờ)",Margin=new Thickness(4)};contentPanel.Children.Add(applyTerms);
   var policyState=new TextBlock{Text="Dịch từng câu offline; chưa có model hiểu ngữ cảnh dài. Thuật ngữ chỉ giữ trong lần mở app này.",TextWrapping=TextWrapping.Wrap};contentPanel.Children.Add(policyState);
   applyTerms.Click+=async delegate{applyTerms.IsEnabled=false;try{if(profile.SelectedIndex<0)throw new ArgumentException();string chosen=new[]{"general","technology","film","pc-game"}[profile.SelectedIndex], glossary=terms.Text;await media.ChangeContentPolicyAsync(chosen,glossary);if(closed)return;policyState.Text="Đã áp dụng. Hồ sơ không tự thay đổi khả năng hiểu ngữ cảnh của model.";}catch(ArgumentException){policyState.Text="Chưa áp dụng: kiểm tra định dạng, mục trùng/chồng lấn và giới hạn thuật ngữ.";}catch{policyState.Text="Chưa áp dụng: model đang bận hoặc worker chưa sẵn sàng. Thử lại khi hết dịch.";}finally{applyTerms.IsEnabled=true;}};
   media.Changed+=delegate{policyState.Text="Dịch từng câu • thuật ngữ khớp/xác nhận: "+media.GlossaryApplied+" • chưa xác nhận: "+media.GlossaryUnresolved+". Chưa có ngữ cảnh hội thoại dài; hồ sơ lưu trong phiên app.";};
   media.Changed+=delegate {var status=media.Status();state.Text=(status.speaking?"Đang đọc • "+media.Started+" câu":media.Processing?"Đang dịch / tạo giọng":status.error==""?"Sẵn sàng nhận lời • EN→VI / VI":"Trạng thái: "+status.error)+" • chờ đọc: "+media.PendingLive+(media.PreparedAhead?" • câu tiếp đã sẵn sàng":"")+" • lượt bỏ/từ chối: "+status.dropped+(status.error=="BACKPRESSURE"?" • QUÁ TẢI: giữ lời đã nhận, độ trễ tăng":"");caption.Text=status.translated;};
   stop.Click+=delegate{inputEnabled=false;capture.StopCapture();media.Stop("");state.Text="Đã dừng nhận phụ đề; bấm Cho phép nguồn để tiếp tục.";};
   resume.Click+=delegate{inputEnabled=true;state.Text="Đã cho phép nguồn đã chọn.";};
   sources.SelectionChanged+=delegate{media.Stop("");};
   voices.Changed+=delegate(string name){if(name!=null)media.ChangeVoice(name);};
   load.Click+=async delegate {load.IsEnabled=false;try{var catalog=await media.Catalog();if(!catalog.Ok)throw new InvalidDataException();voices.SetCatalog(catalog.Voices,catalog.DefaultVoice,catalog.VoiceProfile);}catch{state.Text="Không nạp được giọng; kiểm tra model/worker.";}finally{load.IsEnabled=true;}};
   var prepare=new Button {Content="Chuẩn bị model trước khi xem",Margin=new Thickness(4)};buttons.Children.Add(prepare);
   prepare.Click+=async delegate {prepare.IsEnabled=false;state.Text="Đang chuẩn bị model local; app vẫn phản hồi.";try{var reply=await media.Prepare();state.Text=reply.Ok?"Model local sẵn sàng.":"Không chuẩn bị được model: "+reply.Error;}catch{state.Text="Model đang bận hoặc chưa sẵn sàng.";}finally{prepare.IsEnabled=true;}};
   string root=Directory.GetParent(Path.GetDirectoryName(Path.GetFullPath(worker))).Parent.FullName;
   capture=new CapturePanel(python,Path.Combine(root,"providers","capture","windows_capture_worker.py"),Path.Combine(root,"models","whisper-small-536b066"),sources,AcceptRecognition,delegate{media.Stop("");},delegate(bool active){CaptureActive=active;});AddCapturePanel(capture);
   panel.Children.Add(captureState);capture.MetricsChanged+=delegate{captureState.Text=capture.MetricsSummary;};
   var chromeSetup=new ChromeSetupPanel(root,python);panel.Children.Insert(4,chromeSetup);
   bool compact=false;minimize.Click+=delegate{compact=!compact;var visibility=compact?Visibility.Collapsed:Visibility.Visible;sources.Visibility=visibility;voices.Visibility=visibility;capture.Visibility=visibility;chromeSetup.Visibility=visibility;contentPanel.Visibility=visibility;panel.Children[1].Visibility=visibility;load.Visibility=visibility;prepare.Visibility=visibility;resume.Visibility=visibility;minimize.Content=compact?"Mở cài đặt":"Thu gọn";Width=compact?460:600;Height=compact?270:650;caption.MaxHeight=compact?60:100;};
   Closed+=delegate {closed=true;lock(pipeLock){if(pipe!=null)pipe.Dispose();}capture.Dispose();sources.Dispose();media.Dispose();};
   state.Text="Sẵn sàng • chưa thu hình/âm thanh. Nạp giọng khi cần.";
   var thread=new Thread(Serve){IsBackground=true,Name="AniSub local pipe"};thread.Start();
  }
  static string ReadLine(Stream stream) {
   var bytes=new MemoryStream();int b;
   while((b=stream.ReadByte())!=-1){if(b==10)return new UTF8Encoding(false,true).GetString(bytes.ToArray()).TrimEnd('\r');if(bytes.Length>=65536)throw new InvalidDataException("MESSAGE_TOO_LARGE");bytes.WriteByte((byte)b);}
   return null;
  }
  void Serve() {
   while(!closed) {
    try {
     var security=new PipeSecurity();security.SetAccessRuleProtection(true,false);
     security.AddAccessRule(new PipeAccessRule(WindowsIdentity.GetCurrent().User,PipeAccessRights.FullControl,AccessControlType.Allow));
     NamedPipeServerStream connection;
     lock(pipeLock){if(closed)return;pipe=new NamedPipeServerStream("AniSub.Desktop.v1",PipeDirection.InOut,1,PipeTransmissionMode.Byte,PipeOptions.None,65536,65536,security);connection=pipe;}
     connection.WaitForConnection();
     using(var writer=new StreamWriter(connection,new UTF8Encoding(false),4096,true)){writer.AutoFlush=true;
      string line;while(!closed&&(line=ReadLine(connection))!=null){
       ExternalStatus result=null;
       try {
        var message=json.Deserialize<WireSnapshot>(line);
        if(message==null)throw new InvalidDataException();
        Dispatcher.Invoke(new Action(delegate {
         if(message.type=="snapshot")result=(!inputEnabled||CaptureActive||sources.SelectedSource!=null)?new ExternalStatus {ok=false,error=CaptureActive?"CAPTURE_SOURCE_ACTIVE":sources.SelectedSource!=null?"MANUAL_SOURCE_SELECTED":"INPUT_STOPPED",translated=""}:media.AcceptSnapshot(message);
         else if(message.type=="close"||message.type=="status"){result=CaptureActive?new ExternalStatus{ok=false,error="CAPTURE_SOURCE_ACTIVE",translated=""}:media.Control(message);}
         else result=new ExternalStatus {ok=false,error="UNSUPPORTED_MESSAGE",translated=""};
        }));
       }catch{result=new ExternalStatus {ok=false,error="INVALID_MESSAGE",translated=""};}
       writer.WriteLine(json.Serialize(result));
      }
     }
    }catch(Exception){if(!closed)Dispatcher.BeginInvoke(new Action(delegate{state.Text="Nguồn ngắt kết nối hoặc pipe không sẵn sàng.";}));}
    finally {
     lock(pipeLock){if(pipe!=null){pipe.Dispose();pipe=null;}}
     // Native messaging opens one connection per request. Playback expires via the heartbeat TTL,
     // not via this transport frame boundary; explicit close retires output immediately.
    }
    // A second host must not spin indefinitely against the occupied singleton pipe.
    if(!closed)Thread.Sleep(200);
   }
  }
  public static void Run(string python,string worker,string modelDir,string ttsDir){var app=new Application();app.Run(new ExternalHost(python,worker,modelDir,ttsDir));}
 }
}
