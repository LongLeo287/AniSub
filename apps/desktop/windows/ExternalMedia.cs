using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Media;
using System.Windows.Threading;

namespace AniSub.Windows {
 public sealed class ExternalCue { public double startMs {get;set;} public double endMs {get;set;} public string text {get;set;} }
 public sealed class WireSnapshot {
  public string type {get;set;} public string session {get;set;} public long revision {get;set;} public long sequence {get;set;}
  public double positionMs {get;set;} public bool playing {get;set;} public double speed {get;set;}
  public string language {get;set;} public ExternalCue[] cues {get;set;}
 }
 public sealed class ExternalStatus {public bool ok {get;set;} public bool speaking {get;set;} public string translated {get;set;} public string error {get;set;} public long dropped {get;set;} public int pendingLive {get;set;} public bool processing {get;set;} public bool preparedAhead {get;set;} }
 // Owner-thread only: provider awaits return to WPF Dispatcher; controls retire results by generation.
 public sealed class ExternalMedia : IDisposable {
  readonly TranslationBridge bridge; MediaPlayer player=new MediaPlayer(); readonly DispatcherTimer timer;
  readonly Queue<ExternalCue> queue=new Queue<ExternalCue>(); readonly HashSet<string> seen=new HashSet<string>();
  sealed class LiveJob {public string Text,Language;}
  sealed class PreparedLive {public string Path,Caption;public int Guard;}
  const int LiveQueueLimit=8;
  readonly Queue<LiveJob> liveJobs=new Queue<LiveJob>();
  PreparedLive preparedLive;bool liveBackpressure;
  readonly Queue<string> recent=new Queue<string>();readonly HashSet<string> retired=new HashSet<string>();readonly Queue<string> retiredOrder=new Queue<string>();
  readonly Stopwatch anchor=new Stopwatch(); readonly Queue<string> releases=new Queue<string>();
  string session,language="vi",audioPath,error="",translated="",pendingCaption=""; long revision,sequence; int generation;
  double position,speed=1; bool playing,busy,speaking,disposed,catalogReady,liveRecognition; DateTime lastMessage=DateTime.UtcNow;
  bool outputReady,outputTimed; double outputStart,outputDeadline;
  public long Started {get;private set;} public long Finished {get;private set;} public long Dropped {get;private set;}
  public int PendingLive {get{return liveJobs.Count;}}
  public bool Processing {get{return busy;}}
  public bool PreparedAhead {get{return preparedLive!=null;}}
  ContentPolicy contentPolicy=ContentPolicy.Parse("general", "");
  public int GlossaryApplied {get;private set;} public int GlossaryUnresolved {get;private set;}
  public async Task ChangeContentPolicyAsync(string profile,string terms) {
   var next=ContentPolicy.Parse(profile,terms);
   if(busy)throw new InvalidOperationException("Provider busy");
   busy=true;
   try {
   var valid=await bridge.RequestAsync("validate-policy","",0,next);
   if(disposed)throw new ObjectDisposedException("ExternalMedia");
   if(!valid.Ok)throw new ArgumentException("Invalid content policy");
   ResetOutput();seen.Clear();recent.Clear();translated="";error="";contentPolicy=next;
   GlossaryApplied=0;GlossaryUnresolved=0;Notify();
   } finally {busy=false;Notify();}
  }
  public event Action Changed;
  public ExternalMedia(TranslationBridge provider) {
   bridge=provider;
   timer=new DispatcherTimer {Interval=TimeSpan.FromMilliseconds(50)};timer.Tick+=delegate {
    if(!liveRecognition&&(session!=null||speaking)&&DateTime.UtcNow.Subtract(lastMessage).TotalSeconds>3)Stop("INPUT_IDLE");
    Pump();
   };timer.Start();
  }
  void Notify(){if(Changed!=null)Changed();}
  double Now(){return position+(playing?anchor.Elapsed.TotalMilliseconds*speed:0);}
  public static bool Valid(WireSnapshot value) {
   return value!=null&&value.type=="snapshot"&&!string.IsNullOrWhiteSpace(value.session)&&value.session.Length<=80&&value.revision>=0&&value.sequence>0&&
    Finite(value.positionMs)&&value.positionMs>=0&&Finite(value.speed)&&value.speed>=.5&&value.speed<=2&&
    (value.language=="vi"||value.language=="en")&&value.cues!=null&&value.cues.Length<=32&&
    value.cues.All(c=>c!=null&&Finite(c.startMs)&&Finite(c.endMs)&&c.startMs>=0&&c.endMs>c.startMs&&c.endMs-c.startMs<=300000&&
     !string.IsNullOrWhiteSpace(c.text)&&c.text.Length<=512);
  }
  static bool Finite(double n){return !double.IsNaN(n)&&!double.IsInfinity(n);}
  public ExternalStatus Status(){return new ExternalStatus {ok=error=="",speaking=speaking,translated=translated,error=error,dropped=Dropped,pendingLive=PendingLive,processing=Processing,preparedAhead=PreparedAhead};}
  public ExternalStatus AcceptSnapshot(WireSnapshot value) {
   if(!Valid(value))return new ExternalStatus {ok=false,error="INVALID_SNAPSHOT",translated="",speaking=speaking};
   if(retired.Contains(value.session))return new ExternalStatus {ok=false,error="RETIRED_SESSION",translated="",speaking=false};
   if(session==value.session&&(value.revision<revision||value.sequence<=sequence))return new ExternalStatus {ok=false,error="STALE_SNAPSHOT",translated="",speaking=speaking};
   bool replacing=session!=value.session||revision!=value.revision;
   if(!replacing&&(value.playing!=playing||value.speed!=speed||value.language!=language||Math.Abs(value.positionMs-Now())>1500)) {
    Stop("REVISION_REQUIRED");return Status();
   }
   if(replacing){if(session!=null&&session!=value.session)Retire(session);ResetOutput();seen.Clear();recent.Clear();error="";translated="";}
   session=value.session;revision=value.revision;sequence=value.sequence;language=value.language;liveRecognition=false;
   position=value.positionMs;speed=value.speed;playing=value.playing;anchor.Restart();lastMessage=DateTime.UtcNow;
   if(!playing){ResetOutput();Notify();return Status();}
   foreach(var cue in value.cues.OrderBy(x=>x.startMs)) {
    string key=cue.startMs.ToString("R",System.Globalization.CultureInfo.InvariantCulture)+"/"+cue.endMs.ToString("R",System.Globalization.CultureInfo.InvariantCulture)+"/"+cue.text;
    if(seen.Contains(key))continue;
    if(cue.endMs<=Now()||cue.startMs>Now()+5000||queue.Count>=2){Dropped++;continue;}
    seen.Add(key);recent.Enqueue(key);if(recent.Count>512)seen.Remove(recent.Dequeue());
    queue.Enqueue(cue);
   }
   Pump();Notify();return Status();
  }
  public void Touch(){lastMessage=DateTime.UtcNow;}
  void Retire(string id){if(retired.Add(id)){retiredOrder.Enqueue(id);if(retiredOrder.Count>256)retired.Remove(retiredOrder.Dequeue());}}
  public ExternalStatus Control(WireSnapshot value) {
   if(value==null||(value.type!="status"&&value.type!="close"))return new ExternalStatus{ok=false,error="INVALID_CONTROL",translated=""};
   if(session==null&&value.type=="status"&&string.IsNullOrEmpty(value.session))return Status();
   if(value.session!=session||value.sequence<=sequence||value.revision<revision)return new ExternalStatus{ok=false,error="STALE_CONTROL",translated=""};
   sequence=value.sequence;
   if(value.type=="close"){Retire(session);Stop("");}else Touch();return Status();
  }
  public void Stop(string reason){ResetOutput();session=null;seen.Clear();recent.Clear();playing=false;liveRecognition=false;translated="";error=reason??"";Notify();}
  public void ChangeVoice(string name){ResetOutput();seen.Clear();recent.Clear();translated="";error="";bridge.SelectedVoice=name;Notify();}
  // Recognized live text has unknown media bounds. Never pretend that recognition time equals cue time.
  public bool AcceptRecognizedText(string text,string sourceLanguage) {
   if(disposed)return false;
   if(string.IsNullOrWhiteSpace(text)||text.Length>512||(sourceLanguage!="vi"&&sourceLanguage!="en")){error="UNSUPPORTED_RECOGNITION";Notify();return false;}
   if(!liveRecognition){Stop("");session="live-"+Guid.NewGuid().ToString("N");playing=true;liveRecognition=true;}
   // Caller retains one refused result and retries rather than continuing capture. Count
   // a saturation episode once, not each retry; admitted phrases never expire silently.
   if(liveJobs.Count>=LiveQueueLimit){if(!liveBackpressure)Dropped++;liveBackpressure=true;error="BACKPRESSURE";Notify();return false;}
   liveBackpressure=false;if(error=="BACKPRESSURE")error="";
   liveJobs.Enqueue(new LiveJob{Text=text,Language=sourceLanguage});lastMessage=DateTime.UtcNow;Pump();Notify();return true;
  }
  void ResetOutput() {
   generation++;queue.Clear();liveJobs.Clear();pendingCaption="";outputReady=false;player.Stop();player.Close();
   liveBackpressure=false;if(preparedLive!=null){releases.Enqueue(preparedLive.Path);preparedLive=null;}
   if(speaking){speaking=false;Finished++;}
   if(audioPath!=null){releases.Enqueue(audioPath);audioPath=null;}
  }
  void Finish(){outputReady=false;if(speaking){speaking=false;Finished++;}player.Close();if(audioPath!=null){releases.Enqueue(audioPath);audioPath=null;}Notify();Pump();}
  void StartReady() {
   if(!outputReady||speaking||!playing||audioPath==null||disposed)return;
   if(outputTimed&&Now()>outputDeadline){Dropped++;Finish();return;}
   if(outputTimed&&Now()<outputStart)return;
   outputReady=false;translated=pendingCaption;player.Play();speaking=true;Started++;Notify();
  }
  async void Pump() {
   StartReady();
   if(disposed||busy)return;
   if(releases.Count>0){busy=true;try{await bridge.RequestAsync("release",releases.Dequeue());}catch{error="PROVIDER_RELEASE_FAILED";}finally{busy=false;}return;}
   if(!playing)return;
   if(liveRecognition){
    if(!speaking&&audioPath==null&&preparedLive!=null){var ready=preparedLive;preparedLive=null;if(ready.Guard==generation)OpenOutput(ready.Path,ready.Caption,0,0,false,ready.Guard);else releases.Enqueue(ready.Path);return;}
    // One utterance can be synthesized while the current audio plays. The provider
    // remains serialized, and there is at most one prepared waveform ahead.
    if(preparedLive==null&&liveJobs.Count>0&&(audioPath==null||speaking)){var live=liveJobs.Dequeue();language=live.Language;Run(live.Text,0,0,false,generation);}
    return;
   }
   if(speaking||audioPath!=null)return;
   if(queue.Count==0)return;
   var cue=queue.Peek();if(cue.endMs+2000<Now()){queue.Dequeue();Dropped++;return;}
   // At most one prepared/pre-opened utterance plus two pending cue jobs. Never start it early.
   if(cue.startMs>Now()+5000)return;
   queue.Dequeue();Run(cue.text,cue.startMs,cue.endMs,true,generation);
  }
  bool Expired(bool timed,double expires){return timed&&expires+2000<Now();}
  async void Run(string text,double starts,double expires,bool timed,int guard) {
   busy=true;
   try {
    if(!catalogReady){var catalog=await bridge.RequestAsync("voice-catalog","");ApplyCatalog(catalog);}
    if(disposed||guard!=generation)return;
    if(Expired(timed,expires)){Dropped++;Notify();return;}
    string result=text;
    if(language=="en") {var policy=contentPolicy;var mt=await bridge.RequestAsync("translate",text,0,policy);if(!mt.Ok)throw new InvalidOperationException();if(disposed||guard!=generation)return;result=mt.Text;GlossaryApplied=mt.GlossaryApplied;GlossaryUnresolved=mt.GlossaryUnresolved;}
    // An admitted phrase can start up to 2 media seconds after its caption ends. It then
    // completes intact; no unbounded catch-up. Already expired incoming cues are never admitted.
    if(disposed||guard!=generation||Expired(timed,expires)){Dropped++;Notify();return;}
    double budget=timed?Math.Min(30000,Math.Max(0,(expires-starts)/speed)):0;
    var wav=await bridge.RequestAsync("synthesize",result,budget);
    if(!wav.Ok||string.IsNullOrEmpty(wav.AudioPath))throw new InvalidOperationException();
    if(disposed||guard!=generation||Expired(timed,expires)){if(!disposed)releases.Enqueue(wav.AudioPath);Dropped++;Notify();return;}
    if(!timed&&(speaking||audioPath!=null)){preparedLive=new PreparedLive{Path=wav.AudioPath,Caption=result,Guard=guard};Notify();}
    else OpenOutput(wav.AudioPath,result,starts,expires,timed,guard);
   } catch {if(guard==generation&&!disposed){error="PROVIDER_FAILED";Notify();}}
   finally {busy=false;Notify();}
  }
  void OpenOutput(string path,string caption,double starts,double expires,bool timed,int guard) {
    pendingCaption=caption;audioPath=path;
    player=new MediaPlayer();var output=player;
    outputTimed=timed;outputStart=starts;outputDeadline=expires+2000;
    output.MediaOpened+=delegate {if(!disposed&&guard==generation&&output==player&&audioPath!=null){outputReady=true;StartReady();}};
    output.MediaEnded+=delegate {if(guard==generation&&output==player)Finish();};
    output.MediaFailed+=delegate {if(guard==generation&&output==player){error="AUDIO_OUTPUT_FAILED";Finish();}};
    output.Open(new Uri(audioPath,UriKind.Absolute));Notify();
  }
  void ApplyCatalog(ProviderReply value){
   if(!value.Ok||value.Voices==null||value.Voices.Length==0||!value.Voices.Any(v=>v.name==value.DefaultVoice))throw new InvalidOperationException("Invalid catalog");
   if(!value.Voices.Any(v=>v.name==bridge.SelectedVoice))bridge.SelectedVoice=value.DefaultVoice;
   catalogReady=true;
  }
  public async Task<ProviderReply> Catalog() {if(busy)throw new InvalidOperationException("Provider busy");busy=true;try{var value=await bridge.RequestAsync("voice-catalog","");ApplyCatalog(value);return value;}finally{busy=false;}}
  public async Task<ProviderReply> Prepare() {if(busy)throw new InvalidOperationException("Provider busy");busy=true;try{return await bridge.RequestAsync("probe","");}finally{busy=false;}}
  public void Dispose(){if(disposed)return;disposed=true;timer.Stop();ResetOutput();bridge.Dispose();}
 }
}
