using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading.Tasks;
using System.Windows.Threading;
namespace AniSub.Windows {
 public static class CoordinatorFixtureTests {
  sealed class State { public double Position,Volume=0.8;public int Holds,Resumes;public void Hold(){Holds++;}public void Resume(){Resumes++;} }
  static int assertions;
  static void Assert(bool value,string reason){if(!value)throw new InvalidOperationException(reason);assertions++;}
  static async Task Until(Func<bool> predicate,int budget=4000){var watch=Stopwatch.StartNew();while(!predicate()){if(watch.ElapsedMilliseconds>budget)throw new TimeoutException("fixture wait");await Task.Delay(20);}}
  static NarrationCoordinator Make(TranslationBridge bridge,State state){return new NarrationCoordinator(bridge,()=>state.Position,()=>1,state.Hold,state.Resume,()=>state.Volume,value=>state.Volume=value,value=>{},value=>{});}
  static IList<SubtitleCue> Cues(string first){return new List<SubtitleCue>{new SubtitleCue(0,100,first),new SubtitleCue(100,300,"NEXT")};}
  public static async Task<string> Run(string python,string worker){
   using(var bridge=new TranslationBridge(python,worker,"unused","unused")){
    var state=new State();var coordinator=Make(bridge,state);var timer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(20)};timer.Tick+=delegate{coordinator.Tick();};timer.Start();
    try {
     coordinator.Reset(Cues("FIRST"),0,true,false,true,false);await coordinator.PrimeAsync();coordinator.SetPlaying(true);await Until(()=>coordinator.Starts==1);
     state.Position=150;coordinator.Tick();Assert(coordinator.Speaking,"caption expiry cut accepted speech");Assert(coordinator.Cancelled==0,"normal transition cancelled speech");Assert(coordinator.Held,"overlap did not hold video");
     coordinator.EndInput();Assert(coordinator.Speaking,"EOF cut accepted speech");await Until(()=>coordinator.Completed==1&&!coordinator.Busy);Assert(coordinator.Starts==1,"EOF admitted new output");Assert(coordinator.Errors==0,"EOF release falsely faulted");await Task.Delay(400);Assert(Math.Abs(state.Volume-0.8)<0.001,"EOF duck not restored");
     state.Position=0;coordinator.Reset(Cues("SLOW"),0,true,false,true,true);Assert(coordinator.Held,"late prepare not held");int resumeBaseline=state.Resumes;
     coordinator.SetPlaying(false);coordinator.Reset(Cues("SLOW"),0,false,false,true,false);await Task.Delay(800);Assert(coordinator.Starts==1,"stale inference started after pause");Assert(state.Resumes==resumeBaseline,"pause wrongly auto-resumed hold");Assert(!coordinator.Busy,"stale release failed to drain");
     coordinator.Reset(Cues("SLOW"),0,true,false,true,true);Assert(coordinator.Held,"second late prepare not held");resumeBaseline=state.Resumes;coordinator.Reset(Cues("SLOW"),0,false,false,true,true);Assert(state.Resumes>resumeBaseline,"disabling narration left video held");await Task.Delay(800);
     coordinator.Reset(Cues("FAIL"),0,true,false,true,true);await Until(()=>coordinator.Faulted);Assert(coordinator.Errors>0,"provider failure hidden");Assert(!coordinator.Held,"failure left sync wait active");Assert(coordinator.Starts==1,"failed cue was spoken");
     return "PASS "+assertions+" actual coordinator/Dispatcher/MediaPlayer fixture assertions (synthetic WAV, not AI quality)";
    } finally {coordinator.Close();timer.Stop();}
   }
  }
 }
}
