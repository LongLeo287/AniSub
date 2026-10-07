using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;

namespace AniSub.Windows {
 // Metadata only. Speaker embeddings never enter UI or the subprocess wire.
 public sealed class VoiceSelection : WrapPanel {
  readonly ComboBox region=new ComboBox {Width=115},gender=new ComboBox {Width=90},preset=new ComboBox {Width=145};
  readonly TextBlock detail=new TextBlock {VerticalAlignment=VerticalAlignment.Center,TextWrapping=TextWrapping.Wrap,MaxWidth=420};
  VoicePreset[] voices=new VoicePreset[0]; bool updating;string profile="Nano";
  public event Action<string> Changed;
  public string SelectedVoice {get {var item=preset.SelectedItem as VoicePreset;return item==null?null:item.name;}}
  public static VoicePreset[] Filter(IEnumerable<VoicePreset> input,string area,string sex) {
   return input.Where(x=>(area=="all"||x.region==area)&&(sex=="all"||x.gender==sex)).ToArray();
  }
  public VoiceSelection() {
   Children.Add(new TextBlock {Text=" Giọng Việt ",VerticalAlignment=VerticalAlignment.Center});
   region.Items.Add(new ComboBoxItem {Content="Tất cả vùng",Tag="all"});
   region.Items.Add(new ComboBoxItem {Content="Bắc",Tag="north"});
   region.Items.Add(new ComboBoxItem {Content="Nam",Tag="south"});
   region.Items.Add(new ComboBoxItem {Content="Trung: chưa có",Tag="central",IsEnabled=false});region.SelectedIndex=0;
   gender.Items.Add(new ComboBoxItem {Content="Nam / nữ",Tag="all"});gender.Items.Add(new ComboBoxItem {Content="Nam",Tag="male"});gender.Items.Add(new ComboBoxItem {Content="Nữ",Tag="female"});gender.SelectedIndex=0;
   Children.Add(region);Children.Add(gender);Children.Add(preset);Children.Add(detail);
   region.SelectionChanged+=delegate{Refilter();};gender.SelectionChanged+=delegate{Refilter();};
   preset.SelectionChanged+=delegate {if(updating)return;Publish();};
   IsEnabled=false;detail.Text="Nạp giọng để chọn. Nano ưu tiên tiếng Việt; tiếng Anh/tên riêng cần nghe kiểm tra.";
  }
  public void SetCatalog(VoicePreset[] catalog,string defaultVoice,string modelProfile=null) {
   profile=modelProfile=="Turbo"?"Turbo":"Nano";
   if(catalog==null||catalog.Length<1||catalog.Length>64||catalog.Any(x=>x==null||string.IsNullOrWhiteSpace(x.name)||x.name.Length>80||x.description==null||x.description.Length>256||(x.gender!="male"&&x.gender!="female")||!(new[]{"north","south","central","unknown"}).Contains(x.region))||catalog.Select(x=>x.name).Distinct(StringComparer.Ordinal).Count()!=catalog.Length||!catalog.Any(x=>x.name==defaultVoice))throw new ArgumentException("Invalid voice catalog");
   string preferred=SelectedVoice??defaultVoice;voices=catalog;
   foreach(ComboBoxItem item in region.Items)if((string)item.Tag!="all")item.IsEnabled=catalog.Any(x=>x.region==(string)item.Tag);
   updating=true;region.SelectedIndex=0;gender.SelectedIndex=0;updating=false;
   Bind(preferred);IsEnabled=true;
  }
  void Refilter() {if(updating)return;Bind(SelectedVoice);}
  void Bind(string preferred) {
   var area=(ComboBoxItem)region.SelectedItem;var sex=(ComboBoxItem)gender.SelectedItem;
   var matching=Filter(voices,(string)area.Tag,(string)sex.Tag);
   updating=true;preset.Items.Clear();foreach(var voice in matching)preset.Items.Add(voice);
   preset.SelectedItem=matching.FirstOrDefault(x=>x.name==preferred)??matching.FirstOrDefault();updating=false;Publish();
  }
  void Publish() {
   var selected=preset.SelectedItem as VoicePreset;
   detail.Text=selected==null?"Không có giọng phù hợp; không tự thay model.":selected.description+(profile=="Turbo"?". Turbo: Việt/Anh; tên riêng vẫn cần nghe kiểm tra.":". Nano: ưu tiên tiếng Việt; tiếng Anh/tên riêng cần nghe kiểm tra.");
   if(Changed!=null)Changed(selected==null?null:selected.name);
  }
 }
}
