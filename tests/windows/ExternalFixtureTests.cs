using System;
using System.Threading.Tasks;
namespace AniSub.Windows {
 public static class ExternalFixtureTests {
  static int count;static void Check(bool pass,string label){if(!pass)throw new Exception(label);count++;}
  static WireSnapshot Snapshot(long revision,long sequence,bool playing,string text,double end){return new WireSnapshot {type="snapshot",session="fixture",revision=revision,sequence=sequence,positionMs=0,playing=playing,speed=1,language="vi",cues=new[]{new ExternalCue {startMs=0,endMs=end,text=text}}};}
  public static async Task<string> Run(string python,string worker,bool real) {
   using(var media=new ExternalMedia(new TranslationBridge(python,worker,"models/opus-en-vi-989c9fb","models/vieneu-nano-aba295e"))) {
    Check(!ExternalMedia.Valid(Snapshot(0,0,true,"hello",1000)),"sequence bound");
    var invalid=Snapshot(0,1,true,"hello",1000);invalid.speed=double.NaN;Check(!ExternalMedia.Valid(invalid),"finite speed");
    Check(media.AcceptSnapshot(Snapshot(1,1,true,real?"Xin chào, đây là giọng thuyết minh tiếng Việt.":"SLOW",10000)).ok,"accept");
    if(!real){
     Check(!media.AcceptSnapshot(Snapshot(1,1,true,"SLOW",10000)).ok,"replay");
     media.AcceptSnapshot(Snapshot(2,2,false,"pause",10000));await Task.Delay(900);
    Check(media.Started==0&&!media.Status().speaking,"stale inference after pause");
     var future=Snapshot(3,3,true,"phrase",4000);future.cues[0].startMs=1600;
     media.AcceptSnapshot(future);await Task.Delay(650);
     Check(media.Started==0,"prepared future cue not started early");
     Check(media.Status().translated=="","future caption not published early");
     media.AcceptSnapshot(Snapshot(4,4,false,"pause",10000));await Task.Delay(1100);
     Check(media.Started==0,"preopened future cancelled by revision");
     media.AcceptSnapshot(Snapshot(5,5,true,"phrase",10000));
    }
    for(int i=0;i<100&&media.Started==0;i++){media.Touch();await Task.Delay(100);}
    Check(media.Started==1,"actual media start");
    // Empty active cues are caption expiry, not a request to cut an accepted phrase.
    media.AcceptSnapshot(new WireSnapshot {type="snapshot",session="fixture",revision=real?1:5,sequence=6,playing=true,positionMs=400,speed=1,language="vi",cues=new ExternalCue[0]});
    for(int i=0;i<100&&media.Finished==0;i++){media.Touch();await Task.Delay(100);}
    Check(media.Finished==1&&!media.Status().speaking,"accepted phrase completes");
    media.Stop("");Check(!media.Status().speaking,"explicit stop");
    if(!real){
     media.Stop("");var capturedBefore=media.Started;
     media.AcceptRecognizedText("SLOW","vi");await Task.Delay(50);media.AcceptRecognizedText("phrase","vi");
     await Task.Delay(2200);Check(media.Started==capturedBefore+2,"live recognition queues behind inference instead of losing phrase");
     media.Stop("");
     var before=media.Started;
     var prepared=Snapshot(6,7,true,"phrase",3000);prepared.cues[0].startMs=1100;
     media.AcceptSnapshot(prepared);await Task.Delay(500);Check(media.Started==before,"future prepare audible gate");
     await Task.Delay(900);Check(media.Started==before+1,"future phrase starts when due");
     await Task.Delay(700);Check(media.Finished==media.Started,"future phrase completes");
     var lateBefore=media.Started;media.AcceptSnapshot(Snapshot(7,8,true,"SLOW",100));
     await Task.Delay(800);Check(media.Started==lateBefore+1,"short cue admitted before expiry survives inference latency");
     await Task.Delay(500);
     media.AcceptSnapshot(Snapshot(8,9,true,"FAIL",10000));await Task.Delay(500);Check(media.Status().error=="PROVIDER_FAILED","failure visible");
     media.Stop("");media.AcceptSnapshot(Snapshot(9,10,true,"phrase",10000));await Task.Delay(3400);Check(media.Status().error=="INPUT_IDLE"&&!media.Status().speaking,"heartbeat expiry");
     media.Stop("");
     var pressure=Snapshot(10,11,true,"SLOW",10000);pressure.cues=new[]{new ExternalCue{startMs=0,endMs=10000,text="SLOW"},new ExternalCue{startMs=1500,endMs=10000,text="second"},new ExternalCue{startMs=2000,endMs=10000,text="third"}};
     var pressureBefore=media.Started;media.AcceptSnapshot(pressure);pressure.sequence=12;media.AcceptSnapshot(pressure);
     for(int i=0;i<55&&media.Started<pressureBefore+3;i++){media.Touch();await Task.Delay(100);}
     Check(media.Started==pressureBefore+3,"queue pressure rejected cue retried after admission slot frees");
     var changed=Snapshot(10,13,true,"different language",10000);changed.language="en";Check(!media.AcceptSnapshot(changed).ok,"language change requires revision");
     media.Stop("");var expiredBefore=media.Started;var droppedBefore=media.Dropped;
     media.AcceptRecognizedText("VERY_SLOW","vi");media.AcceptRecognizedText("phrase","vi");await Task.Delay(5800);
     Check(media.Started==expiredBefore+1&&media.Dropped==droppedBefore,"admitted live phrase survives inference beyond old five second deadline");
     await Task.Delay(1000);Check(media.Started==expiredBefore+2&&media.Dropped==droppedBefore,"admitted queued phrase older than five seconds still completes");
     media.Stop("");var overlapBefore=media.Started;
     Check(media.AcceptRecognizedText("LONG_AUDIO","vi"),"live first admitted");
     for(int i=0;i<20&&!media.Status().speaking;i++)await Task.Delay(50);
     Check(media.Status().speaking,"long fixture playing");
     Check(media.AcceptRecognizedText("SLOW","vi"),"live next admitted");
     await Task.Delay(750);
     Check(media.Status().speaking&&media.PreparedAhead&&media.Started==overlapBefore+1,"next synthesis completes concurrently with current playback");
     media.ChangeVoice("Minh Quân");await Task.Delay(1500);
     Check(!media.PreparedAhead&&!media.Status().speaking&&media.Started==overlapBefore+1,"voice change cancels active and prepared speech");
     media.Stop("");media.AcceptRecognizedText("LONG_AUDIO","vi");
     for(int i=0;i<20&&!media.Status().speaking;i++)await Task.Delay(50);
     media.AcceptRecognizedText("phrase","vi");await Task.Delay(250);
     Check(media.PreparedAhead,"second prepared fixture ready");media.Stop("");await Task.Delay(200);
     Check(!media.PreparedAhead&&media.PendingLive==0&&!media.Processing,"stop releases prepared and clears queue");
     var pressureDrops=media.Dropped;media.AcceptRecognizedText("VERY_SLOW","vi");
     for(int i=0;i<8;i++)Check(media.AcceptRecognizedText("queued "+i,"vi"),"bounded queue admission "+i);
     Check(!media.AcceptRecognizedText("retained by caller","vi")&&media.PendingLive==8&&media.Status().error=="BACKPRESSURE","full live queue rejects explicitly");
     Check(!media.AcceptRecognizedText("retained by caller","vi")&&media.Dropped==pressureDrops+1,"retry does not inflate rejection counter");
     media.Stop("");await Task.Delay(5500);
     Check(media.PendingLive==0&&!media.PreparedAhead&&!media.Status().speaking,"stop retires in-flight live synthesis");
     await media.ChangeContentPolicyAsync("technology","chip | processor | potato");
     Check(media.AcceptRecognizedText("hello","en"),"policy translation admitted");
     for(int i=0;i<30&&!media.Status().speaking;i++)await Task.Delay(50);
     Check(media.Status().translated=="technology:processor","selected policy forwarded to translate");
     bool invalidPolicy=false;try{await media.ChangeContentPolicyAsync("film","chip | processor | potato\ncard | processor | deck");}catch(ArgumentException){invalidPolicy=true;}
     Check(invalidPolicy&&media.Status().speaking,"invalid overlapping policy does not reset playback");
     await media.ChangeContentPolicyAsync("film","");
     Check(!media.Status().speaking&&media.PendingLive==0,"valid profile switch cancels obsolete output");
     var policyBefore=media.Started;media.AcceptRecognizedText("POLICY_DELAY","en");await Task.Delay(50);
     media.Stop("");await Task.Delay(700);
     Check(media.Started==policyBefore,"retired delayed translation never starts speech");
     await media.ChangeContentPolicyAsync("pc-game","party | squad | feast");media.AcceptRecognizedText("new","en");
     for(int i=0;i<30&&!media.Status().speaking;i++)await Task.Delay(50);
     Check(media.Status().translated=="pc-game:squad","replacement profile does not reuse previous terms");
     media.Stop("");
     var validation=media.ChangeContentPolicyAsync("technology","chip | processor | potato\ncard | processor | deck");
     await Task.Delay(50);Check(media.AcceptRecognizedText("retained","en"),"input admitted during validation");
     Check(media.PendingLive==1,"validation lock retains queued input");
     try{await validation;}catch(ArgumentException){}
     for(int i=0;i<30&&!media.Status().speaking;i++)await Task.Delay(50);
     Check(media.Status().speaking&&media.Status().translated=="pc-game:squad","invalid delayed validation preserves queue and prior policy");
     media.Stop("");
    }
   }
   return "EXTERNAL_FIXTURE_PASS "+count+" real="+real;
  }
 }
}
