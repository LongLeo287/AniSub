using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Media;

namespace AniSub.Windows {
 public sealed class VolumeRamp {
  double from, target, start, duration; public double Value { get; private set; }
  public VolumeRamp(double value) { Value=from=target=value; }
  public void Set(double value,double now,double milliseconds) { Advance(now);from=Value;target=value;start=now;duration=Math.Max(1,milliseconds); }
  public double Advance(double now) { double t=Math.Max(0,Math.Min(1,(now-start)/Math.Max(1,duration)));double smooth=t*t*(3-2*t);Value=from+(target-from)*smooth;return Value; }
 }
 // Pure media-clock decisions, independently tested without codecs or models.
 public static class NarrationTimeline {
  public static int First(IList<SubtitleCue> cues,double position,double offset) { int i=0;while(i<cues.Count&&cues[i].EndMs+offset<=position)i++;return i; }
  public static bool InWindow(SubtitleCue cue,double position,double offset) { return cue.StartMs+offset<=position+12000; }
  public static bool Due(SubtitleCue cue,double position,double offset) { return cue.StartMs+offset<=position+35; }
  public static bool MustHold(bool complete,bool due,bool prepared,bool active) { return complete&&due&&(!prepared||active); }
 }
 public sealed class NarrationCoordinator {
  sealed class Clip { public int Index; public long Generation;public string Path,Text; public MediaPlayer Player;public bool Ready,Started,Released; }
  readonly TranslationBridge bridge;readonly Func<double> position,speed;readonly Action pause,resume;readonly Func<double> volume;readonly Action<double> setVolume;readonly Action<string> status,caption;
  readonly Stopwatch clock=Stopwatch.StartNew();readonly Queue<string> releases=new Queue<string>();readonly List<Clip> clips=new List<Clip>();readonly List<Clip> retiring=new List<Clip>();
  IList<SubtitleCue> cues=new List<SubtitleCue>();Clip active;VolumeRamp duck;double baseline=1,offset,holdStart,heldTotal,maxHold,lateMax;long generation;int next;bool wanted,enabled,complete=true,english,busy,closed,held,fault;double cancelAt;string lastError="NONE";
  public int Starts { get; private set; }public int Completed { get; private set; }public int Cancelled { get; private set; }public int Failed { get; private set; }public int Drops { get; private set; }public int Errors { get; private set; }
  public bool Speaking { get { return active!=null; } }public bool Busy { get { return busy; } }public bool Held { get { return held; } }public bool Faulted { get { return fault; } }
  public NarrationCoordinator(TranslationBridge provider,Func<double> mediaPosition,Func<double> playbackSpeed,Action holdVideo,Action resumeVideo,Func<double> getVolume,Action<double> writeVolume,Action<string> showStatus,Action<string> showCaption) {
   bridge=provider;position=mediaPosition;speed=playbackSpeed;pause=holdVideo;resume=resumeVideo;volume=getVolume;setVolume=writeVolume;status=showStatus;caption=showCaption;baseline=volume();duck=new VolumeRamp(baseline);
  }
  public void Reset(IList<SubtitleCue> source,double shift,bool enabledSpeech,bool translateEnglish,bool readComplete,bool userPlaying) {
   generation++;wanted=userPlaying;enabled=enabledSpeech;english=translateEnglish;complete=readComplete;offset=shift;cues=source;next=NarrationTimeline.First(cues,position(),offset);fault=false;lastError="NONE";
   EndHold(userPlaying);foreach(var c in clips) DisposeClip(c);clips.Clear();CancelActive();Tick();
  }
  public void SetPlaying(bool value) { wanted=value;if(!value) { generation++;EndHold(false);foreach(var c in clips)DisposeClip(c);clips.Clear();next=NarrationTimeline.First(cues,position(),offset);CancelActive(); }else { fault=false;Tick(); } }
  public void EndInput() { wanted=false;enabled=false;next=cues.Count;EndHold(false);foreach(var c in clips)DisposeClip(c);clips.Clear(); }
  void Hold() { if(held)return;held=true;holdStart=clock.Elapsed.TotalMilliseconds;pause();status("Sync wait: completing narration / preparing next line."); }
  void EndHold(bool play) { if(!held)return;double elapsed=clock.Elapsed.TotalMilliseconds-holdStart;heldTotal+=elapsed;maxHold=Math.Max(maxHold,elapsed);held=false;if(play&&wanted&&!fault&&!closed)resume(); }
  void Fail(string reason) { Errors++;fault=true;lastError=reason;pause();EndHold(false);foreach(var c in clips)DisposeClip(c);clips.Clear();next=active==null?NarrationTimeline.First(cues,position(),offset):active.Index+1;status("Narration paused: "+reason+". Fix input/provider, then Play to retry."); }
  void DisposeClip(Clip c) { if(c.Released)return;c.Released=true;if(c.Player!=null)c.Player.Close();if(!string.IsNullOrEmpty(c.Path))releases.Enqueue(c.Path); }
  void CancelActive() { if(active==null)return;var old=active;active=null;Cancelled++;retiring.Add(old);cancelAt=clock.Elapsed.TotalMilliseconds+60;duck.Set(baseline,clock.Elapsed.TotalMilliseconds,350); }
  public void Close() { closed=true;generation++;wanted=false;EndHold(false);foreach(var c in clips)DisposeClip(c);clips.Clear();if(active!=null) { Cancelled++;DisposeClip(active);active=null; }foreach(var c in retiring)DisposeClip(c);retiring.Clear();setVolume(baseline);Pump(); }
  public void Tick() {
   double now=clock.Elapsed.TotalMilliseconds;
   if(retiring.Count>0) { foreach(var c in retiring) { c.Player.Volume=Math.Max(0,(cancelAt-now)/60); }if(now>=cancelAt){foreach(var c in retiring)DisposeClip(c);retiring.Clear();} }
   setVolume(duck.Advance(now));
   if(closed||fault) { Pump();return; }
   if(held&&now-holdStart>10000) { Fail("SYNC_WAIT_TIMEOUT");Pump();return; }
   if(enabled&&wanted&&bridge!=null) {
    Clip due=clips.Count>0?clips[0]:null;
    bool hasDue=due!=null?NarrationTimeline.Due(cues[due.Index],position(),offset):(next<cues.Count&&NarrationTimeline.Due(cues[next],position(),offset));
    if(NarrationTimeline.MustHold(complete,hasDue,due!=null&&due.Ready,active!=null))Hold();
    if(due!=null&&hasDue&&due.Ready&&active==null&&retiring.Count==0) {
     if(!complete&&position()>cues[due.Index].EndMs+offset) { clips.RemoveAt(0);Drops++;DisposeClip(due); }
     else { clips.RemoveAt(0);active=due;due.Started=true;Starts++;lateMax=Math.Max(lateMax,Math.Max(0,position()-cues[due.Index].StartMs-offset));caption(due.Text);duck.Set(baseline*0.25,now,150);due.Player.SpeedRatio=Math.Max(0.5,Math.Min(2,speed()));due.Player.Play();status("Narration playing; complete-line synchronization enabled="+complete);EndHold(true); }
    } else if(held&&!hasDue)EndHold(true);
   }
   Pump();
  }
  public async Task PrimeAsync() {
   long stamp=generation;double began=clock.Elapsed.TotalMilliseconds;
   if(clips.Count==0&&next<cues.Count&&!NarrationTimeline.InWindow(cues[next],position(),offset))return;
   while(!closed&&stamp==generation&&(clips.Count==0||!clips[0].Ready)&&(next<cues.Count||clips.Count>0)) { Pump();await Task.Delay(50);if(fault)throw new InvalidOperationException("Narration preparation failed");if(clock.Elapsed.TotalMilliseconds-began>120000)throw new TimeoutException("Narration priming timeout"); }
   if(stamp!=generation||closed)throw new OperationCanceledException();
  }
  async void Pump() {
   if(busy||bridge==null)return;
   bool canBuild=!closed&&!fault&&enabled&&next<cues.Count&&clips.Count+(active==null?0:1)<6&&NarrationTimeline.InWindow(cues[next],position(),offset);
   if(releases.Count==0&&!canBuild)return;busy=true;long stamp=generation;Clip building=null;
   try {
    while(releases.Count>0) { var released=await bridge.RequestAsync("release",releases.Dequeue());if(!released.Ok&& !closed) { Fail("AUDIO_RELEASE_FAILED");return; } }
    if(closed||stamp!=generation||fault||!enabled||next>=cues.Count||clips.Count+(active==null?0:1)>=6||!NarrationTimeline.InWindow(cues[next],position(),offset))return;
    int index=next++;building=new Clip {Index=index,Generation=stamp};clips.Add(building);string text=cues[index].Text;
    if(english) { var translation=await bridge.RequestAsync("translate",text);if(!translation.Ok)throw new InvalidOperationException("TRANSLATION_FAILED");text=translation.Text; }
    if(stamp!=generation||closed||fault||building.Released)return;
    double budget=Math.Min(30000,cues[index].EndMs-cues[index].StartMs);if(index+1<cues.Count)budget=Math.Min(budget,Math.Max(200,cues[index+1].StartMs-cues[index].StartMs));
    var reply=await bridge.RequestAsync("synthesize",text,budget);building.Path=reply.AudioPath;
    if(stamp!=generation||closed||fault||building.Released) { if(building.Released&&!string.IsNullOrEmpty(building.Path))releases.Enqueue(building.Path);else DisposeClip(building);return; }
    if(!reply.Ok||string.IsNullOrEmpty(reply.AudioPath)||!File.Exists(reply.AudioPath))throw new InvalidOperationException("SPEECH_PREPARATION_FAILED");
    building.Text=text;var clip=building;clip.Player=new MediaPlayer();clip.Player.Volume=1;
    clip.Player.MediaOpened+=delegate { if(closed||stamp!=generation||clip.Released){DisposeClip(clip);return;}clip.Ready=true;Tick(); };
    clip.Player.MediaEnded+=delegate { if(object.ReferenceEquals(active,clip)) { active=null;Completed++;duck.Set(baseline,clock.Elapsed.TotalMilliseconds,350);DisposeClip(clip);Tick(); } };
    clip.Player.MediaFailed+=delegate { if(stamp!=generation||closed||clip.Released)return;if(object.ReferenceEquals(active,clip)) { active=null;Failed++;duck.Set(baseline,clock.Elapsed.TotalMilliseconds,350); }clips.Remove(clip);DisposeClip(clip);Fail("SPEECH_OUTPUT_FAILED"); };
    clip.Player.Open(new Uri(Path.GetFullPath(reply.AudioPath)));
   } catch(Exception e) { if(building!=null){clips.Remove(building);DisposeClip(building);}if(!closed&&stamp==generation)Fail(e is TimeoutException?"PROVIDER_TIMEOUT":"PROVIDER_FAILED"); }
   finally { busy=false; if(releases.Count>0)Pump(); }
  }
  public string Diagnostic { get { return "speechStarts="+Starts+" speechFinishes="+(Completed+Cancelled+Failed)+" completed="+Completed+" cancelled="+Cancelled+" failed="+Failed+" drops="+Drops+" errors="+Errors+" errorCode="+lastError+" faulted="+fault+" lateMaxMs="+lateMax.ToString("F0",CultureInfo.InvariantCulture)+" heldMs="+(heldTotal+(held?clock.Elapsed.TotalMilliseconds-holdStart:0)).ToString("F0",CultureInfo.InvariantCulture)+" maxHoldMs="+Math.Max(maxHold,held?clock.Elapsed.TotalMilliseconds-holdStart:0).ToString("F0",CultureInfo.InvariantCulture)+" buffered="+clips.Count+" busy="+busy+" syncWait="+held; } }
 }
}
