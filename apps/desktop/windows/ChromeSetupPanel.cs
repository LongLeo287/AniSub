using System;
using System.Diagnostics;
using System.IO;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;

namespace AniSub.Windows {
 public sealed class ChromeSetupPanel : Expander {
  readonly string root,python;readonly TextBox id=new TextBox();readonly TextBlock status=new TextBlock();readonly Button install=new Button();
  public ChromeSetupPanel(string workspace,string pythonPath) {
   root=workspace;python=pythonPath;Header="Kết nối Chrome (cài một lần)";Margin=new Thickness(8);IsExpanded=false;
   var panel=new StackPanel();Content=panel;
   panel.Children.Add(new TextBlock{Text="Chrome → Extensions → Developer mode → Load unpacked. Chọn folder extension, rồi dán ID bên dưới. Chỉ extension có ID này được kết nối.",TextWrapping=TextWrapping.Wrap});
   var folder=new Button{Content="Copy đường dẫn folder extension",Margin=new Thickness(3)};panel.Children.Add(folder);folder.Click+=delegate{Clipboard.SetText(Path.Combine(root,"clients","chrome-extension"));status.Text="Đã copy đường dẫn. Dán khi Chrome yêu cầu chọn folder.";};
   id.MaxLength=32;id.ToolTip="ID extension: 32 chữ a–p";panel.Children.Add(id);
   install.Content="Cho phép extension này kết nối local";install.Margin=new Thickness(3);panel.Children.Add(install);install.Click+=async delegate{await Install();};
   status.TextWrapping=TextWrapping.Wrap;panel.Children.Add(status);
  }
  async Task Install() {
   string extension=id.Text.Trim();if(!Regex.IsMatch(extension,"^[a-p]{32}$")){status.Text="Dán đúng ID 32 ký tự từ trang Extensions.";return;}
   install.IsEnabled=false;
   try {
    string script=Path.Combine(root,"apps","desktop","native-messaging","Register-NativeHost.ps1");
    if(script.IndexOf('"')>=0||python.IndexOf('"')>=0)throw new ArgumentException();
    var start=new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"WindowsPowerShell","v1.0","powershell.exe"),"-NoProfile -ExecutionPolicy Bypass -File \""+script+"\" -ExtensionId "+extension+" -PythonPath \""+python+"\"");
    start.UseShellExecute=false;start.CreateNoWindow=true;start.RedirectStandardOutput=true;start.RedirectStandardError=true;
    using(var process=Process.Start(start)) {
     var stdout=process.StandardOutput.ReadToEndAsync();var stderr=process.StandardError.ReadToEndAsync();
     bool finished=await Task.Run(()=>process.WaitForExit(30000));if(!finished){process.Kill();throw new TimeoutException();}
     await stdout;await stderr;
     status.Text=process.ExitCode==0?"Đã kết nối. Mở video, bấm biểu tượng AniSub trong Chrome và chọn Bắt đầu.":"Chưa kết nối được. Nếu đã cài, không ghi đè; xem hướng dẫn gỡ/cài lại.";
    }
   }catch{status.Text="Kết nối chưa hoàn tất; xem hướng dẫn Chrome trong folder AniSub.";}
   finally{install.IsEnabled=true;}
  }
 }
}
