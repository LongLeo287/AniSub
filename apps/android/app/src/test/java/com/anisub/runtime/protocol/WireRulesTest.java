package com.anisub.runtime.protocol;

import org.junit.Test;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Consumer contract tests: missing bounds, wrong dispatch and mutation must fail. */
public class WireRulesTest {
    private static final long MAX = 9007199254740991L;
    private static String chars(int n) { char[] c = new char[n]; Arrays.fill(c, 'x'); return new String(c); }
    private static ClockAnchor clock(double speed) { return new ClockAnchor(0L, 100L, true, speed); }
    private static Cue cue(String id, String text, Long start, Long end) {
        return new Cue(id, text, "vi", 0L, start, end, "DIRECT", "UNKNOWN", "track");
    }
    private static SessionDescriptor descriptor(String target) {
        return new SessionDescriptor("episode", "source", "track", "DIRECT_TEXT", "TEXT_TRACK", "vi", target, 0L, "model", "1", "voice", true);
    }
    private static Envelope playback(String type, Envelope.Body body) {
        return new Envelope(2, type, "request", "session", 0L, 1L, body);
    }
    private static Envelope manage(String type, Envelope.Body body) {
        return new Envelope(2, type, "request", null, null, null, body);
    }
    private static void accepted(Envelope e) { assertTrue(WireRules.validate(e).code, WireRules.validate(e).accepted); }
    private static void rejected(String code, Envelope e) {
        ValidationResult r = WireRules.validate(e); assertFalse(r.accepted); assertEquals(code, r.code);
    }
    @Test public void limitsAndTypes() {
        List<Cue> cues = new ArrayList<>(); for (int i=0;i<16;i++) cues.add(cue("c"+i, chars(512), null, null));
        accepted(playback("CUE_SNAPSHOT", new Envelope.Cues(cues)));
        cues.add(cue("extra", "x", null, null));
        try { new Envelope.Cues(cues); fail("17 cues must reject before allocation"); } catch (IllegalArgumentException expected) { }
        accepted(playback("CUE_SNAPSHOT", new Envelope.Cues(Collections.singletonList(cue(chars(80), chars(512), null, null)))));
        rejected("MALFORMED", playback("CUE_SNAPSHOT", new Envelope.Cues(Collections.singletonList(cue(chars(81), "x", null, null)))));
        rejected("MALFORMED", playback("CUE_SNAPSHOT", new Envelope.Cues(Collections.singletonList(cue("c", chars(513), null, null)))));
        accepted(new Envelope(2,"CLOSE","r","s", MAX, MAX, Envelope.Empty.INSTANCE));
        rejected("MALFORMED", new Envelope(2,"CLOSE","r","s", MAX+1, 1L, Envelope.Empty.INSTANCE));
        rejected("MALFORMED", playback("CUE_SNAPSHOT", new Envelope.Cues(Collections.singletonList(cue("c", "x", null, -1L)))));
        accepted(playback("PLAY", new Envelope.Clock(clock(0.5)))); accepted(playback("PLAY", new Envelope.Clock(clock(2))));
        rejected("MALFORMED", playback("PLAY", new Envelope.Clock(clock(Double.NaN))));
        rejected("MALFORMED", playback("PLAY", new Envelope.Clock(clock(2.01))));
        rejected("UNSUPPORTED_TRANSLATION", playback("OPEN", new Envelope.Open(descriptor("en"), clock(1))));
    }
    @Test public void everyCommandRequiresItsTypedBodyAndFields() {
        accepted(manage("HELLO", new Envelope.Hello(0)));
        accepted(manage("GET_CAPABILITIES", Envelope.Empty.INSTANCE)); accepted(manage("GET_SETTINGS", Envelope.Empty.INSTANCE));
        accepted(manage("LIST_MODELS", new Envelope.Page(null,null,null,null)));
        accepted(manage("LIST_VOICES", new Envelope.Page("model","1",null,16)));
        accepted(manage("SET_SETTINGS", new Envelope.SetSettings(0L,new SessionDescriptor.Settings("AUTO",null,null,null,"vi","AUTO","LIVE_BOUNDED"))));
        accepted(manage("DOWNLOAD_MODEL",new Envelope.Model("model","1",true)));
        for(String type:Arrays.asList("PREPARE_MODEL","REMOVE_MODEL")) accepted(manage(type,new Envelope.Model("model","1",null)));
        accepted(manage("CANCEL_DOWNLOAD",new Envelope.Operation("op")));
        accepted(playback("OPEN",new Envelope.Open(descriptor("vi"),clock(1))));
        accepted(playback("CUE_SNAPSHOT",new Envelope.Cues(Collections.emptyList())));
        accepted(playback("TIMELINE_BATCH",new Envelope.Cues(Collections.singletonList(cue("c","x",1L,2L)))));
        for(String type:Arrays.asList("CLOCK_ANCHOR","PLAY","PAUSE","SEEK","STOP","PLAYBACK_SPEED")) accepted(playback(type,new Envelope.Clock(clock(1))));
        for(String type:Arrays.asList("EPISODE_CHANGE","SOURCE_CHANGE","CLOSE")) accepted(playback(type,Envelope.Empty.INSTANCE));
        for(String type:Arrays.asList("HELLO","SET_SETTINGS","LIST_MODELS","LIST_VOICES","DOWNLOAD_MODEL","CANCEL_DOWNLOAD","REMOVE_MODEL","PREPARE_MODEL","OPEN","CUE_SNAPSHOT","TIMELINE_BATCH","CLOCK_ANCHOR","PLAY","PAUSE","SEEK","STOP","PLAYBACK_SPEED"))
            rejected("MALFORMED",playback(type,Envelope.Empty.INSTANCE));
        rejected("UNSUPPORTED",manage("EXECUTE",Envelope.Empty.INSTANCE));
        rejected("PROTOCOL_MISMATCH",new Envelope(1,"HELLO","r",null,null,null,new Envelope.Hello(0)));
        rejected("UNSUPPORTED",manage("HELLO",new Envelope.Hello(1)));
        rejected("MALFORMED",new Envelope(2,"PLAY","r",null,0L,1L,new Envelope.Clock(clock(1))));
        rejected("MALFORMED",new Envelope(2,"PLAY","r","s",0L,0L,new Envelope.Clock(clock(1))));
        rejected("MALFORMED",manage("DOWNLOAD_MODEL",new Envelope.Model("m","1",false)));
        rejected("MALFORMED",manage("LIST_MODELS",new Envelope.Page(null,null,"p",17)));
        rejected("MALFORMED",manage("LIST_VOICES",new Envelope.Page(null,"1",null,1)));
        rejected("MALFORMED",manage("SET_SETTINGS",new Envelope.SetSettings(null,new SessionDescriptor.Settings("AUTO",null,null,null,"vi","AUTO","LIVE_BOUNDED"))));
        rejected("MALFORMED",manage("SET_SETTINGS",new Envelope.SetSettings(0L,new SessionDescriptor.Settings("EXPLICIT",null,null,null,"vi","AUTO","LIVE_BOUNDED"))));
        rejected("UNSUPPORTED",manage("SET_SETTINGS",new Envelope.SetSettings(0L,new SessionDescriptor.Settings("AUTO",null,null,null,"vi","FAST","LIVE_BOUNDED"))));
        rejected("UNSUPPORTED",playback("CUE_SNAPSHOT",new Envelope.Cues(Collections.singletonList(new Cue("c","x","vi",0L,null,null,"OCR","UNKNOWN","t")))));
        rejected("UNSUPPORTED",playback("CUE_SNAPSHOT",new Envelope.Cues(Collections.singletonList(new Cue("c","x","vi",0L,null,null,"DIRECT","GUESSED","t")))));
        rejected("MALFORMED",playback("TIMELINE_BATCH",new Envelope.Cues(Collections.singletonList(cue("c","x",null,2L)))));
        rejected("MALFORMED",playback("CUE_SNAPSHOT",new Envelope.Cues(Collections.singletonList(cue("c","x",2L,1L)))));
        rejected("MALFORMED",playback("CUE_SNAPSHOT",new Envelope.Cues(Arrays.asList(cue("c","x",null,null),cue("c","y",null,null)))));
        rejected("MALFORMED",playback("PLAY",new Envelope.Clock(new ClockAnchor(null,0L,true,1.0))));
    }
    @Test public void acceptedCueBatchCannotBeMutatedByItsCaller() {
        List<Cue> input=new ArrayList<>(); input.add(cue("a","x",null,null)); Envelope.Cues body=new Envelope.Cues(input); input.clear();
        accepted(playback("CUE_SNAPSHOT",body)); assertEquals(1,body.cues.size());
        try {body.cues.clear();fail("immutable");}catch(UnsupportedOperationException expected) { }
    }
    @Test public void replyIdentitiesStayBoundedAndKindsKeepTheirRequiredFields() {
        new Reply(Reply.Kind.ACK,"r",null,null,null,"op",null,null,null,null,null,null,null);
        new Reply(Reply.Kind.ERROR,null,"s",0L,null,null,null,null,null,null,null,RuntimeError.STALE,true);
        new Reply(Reply.Kind.SPEECH_STARTED,null,"s",0L,1L,null,"speech","cue",0,"1",null,null,null);
        new Reply(Reply.Kind.SPEECH_FINISHED,null,"s",0L,2L,null,"speech","cue",0,"1","COMPLETED",null,null);
        try {new Reply(Reply.Kind.ACK,null,null,null,null,null,null,null,null,null,null,null,null);fail("ACK needs correlation");}catch(IllegalArgumentException expected) { }
        try {new Reply(Reply.Kind.ERROR,"r",null,null,null,null,null,null,null,null,null,null,true);fail("ERROR needs code");}catch(IllegalArgumentException expected) { }
        try {new Reply(Reply.Kind.SPEECH_STARTED,null,"s",0L,0L,null,"speech","cue",0,"1",null,null,null);fail("event seq starts at1");}catch(IllegalArgumentException expected) { }
        try {new Reply(Reply.Kind.SPEECH_FINISHED,null,"s",0L,2L,null,"speech","cue",0,"1","RAW TEXT",null,null);fail("terminal reason enum");}catch(IllegalArgumentException expected) { }
        try {new Reply(Reply.Kind.ACK,chars(81),null,null,null,null,null,null,null,null,null,null,null);fail("bounded reply");}catch(IllegalArgumentException expected) { }
    }
    @Test public void urlReferencesRemainForbiddenWithEveryLineTerminator() {
        for(String suffix:Arrays.asList("\n","\r","\r\n","\u0085","\u2028","\u2029")) {
            SessionDescriptor d=new SessionDescriptor("https://example.com"+suffix,"source","track","DIRECT_TEXT","TEXT_TRACK","vi","vi",0L,"m","1","v",true);
            rejected("MALFORMED",playback("OPEN",new Envelope.Open(d,clock(1))));
            rejected("MALFORMED",playback("CUE_SNAPSHOT",new Envelope.Cues(Collections.singletonList(new Cue("c","x","vi",0L,null,null,"DIRECT","UNKNOWN","https://example.com"+suffix)))));
        }
    }
    @Test public void managementRepliesCannotClaimResultsWithoutRequiredBodies() {
        for(Reply.Kind kind:Arrays.asList(Reply.Kind.HELLO_ACK,Reply.Kind.SETTINGS,Reply.Kind.CAPABILITIES,Reply.Kind.MODEL_PROGRESS,Reply.Kind.MODEL_STATE)) {
            try {new Reply(kind,"r",null,null,null,null,null,null,null,null,null,null,null);fail(kind+" requires a body");}catch(IllegalArgumentException expected) { }
        }
    }
    private static Reply response(Reply.Kind kind,ReplyBodies.Body body) {
        return new Reply(kind,"r",null,null,null,kind==Reply.Kind.MODEL_PROGRESS?"op":null,null,null,null,null,null,null,null,body);
    }
    private static void malformed(Runnable action) {try{action.run();fail("required or bounded response field");}catch(IllegalArgumentException expected){assertEquals("MALFORMED",expected.getMessage());}}
    @Test public void managementResultsCarryBoundedTypedSnapshotsAndUnknownVoiceMetadata() {
        ReplyBodies.Limits limits=new ReplyBodies.Limits();
        response(Reply.Kind.HELLO_ACK,new ReplyBodies.HelloAck(limits));
        SessionDescriptor.Settings settings=new SessionDescriptor.Settings("AUTO",null,null,null,"vi","AUTO","LIVE_BOUNDED");
        ReplyBodies.Settings saved=new ReplyBodies.Settings(3L,settings);response(Reply.Kind.SETTINGS,saved);
        new Reply(Reply.Kind.ERROR,"r",null,null,null,null,null,null,null,null,null,RuntimeError.SETTINGS_CONFLICT,true,saved);
        response(Reply.Kind.MODEL_PROGRESS,new ReplyBodies.ModelProgress(0L,MAX));
        response(Reply.Kind.MODEL_STATE,new ReplyBodies.ModelState("m","1",ReplyBodies.ModelStatus.ERROR,RuntimeError.MODEL_CORRUPT));
        response(Reply.Kind.MODEL_STATE,new ReplyBodies.ModelState("m","1",ReplyBodies.ModelStatus.NOT_INSTALLED,RuntimeError.CANCELLED));
        List<ReplyBodies.ModelEntry> models=new ArrayList<>();for(int i=0;i<16;i++)models.add(catalogModel("m"+i));
        ReplyBodies.Models page=new ReplyBodies.Models(models,chars(80));response(Reply.Kind.MODELS,page);models.clear();assertEquals(16,page.models.size());
        ReplyBodies.VoiceDescriptor voice=new ReplyBodies.VoiceDescriptor("v","m","1","vi","Voice",ReplyBodies.Accent.UNKNOWN,ReplyBodies.Gender.UNKNOWN,false,Collections.singletonList(ReplyBodies.RateMode.NATURAL),ReplyBodies.OutputMode.FULL_UTTERANCE);
        response(Reply.Kind.VOICES,new ReplyBodies.Voices(Collections.singletonList(voice),null));
        response(Reply.Kind.CAPABILITIES,new ReplyBodies.Capabilities(ReplyBodies.Readiness.MODEL_MISSING,limits,Collections.singletonList("DIRECT_TEXT"),Collections.emptyList(),Collections.emptyList(),false,false,false,false,false,"unavailable",Collections.emptyList()));
        response(Reply.Kind.CAPABILITIES,new ReplyBodies.Capabilities(ReplyBodies.Readiness.READY,limits,Collections.singletonList("DIRECT_TEXT"),Collections.emptyList(),Collections.singletonList("vi"),true,true,false,true,false,"local-engine",Collections.singletonList(new ReplyBodies.ModelEntry("m","1",ReplyBodies.ModelStatus.READY))));
        malformed(()->new ReplyBodies.HelloAck(null));
        malformed(()->new ReplyBodies.Settings(MAX+1,settings));
        malformed(()->new ReplyBodies.ModelProgress(2L,1L));
        malformed(()->new ReplyBodies.ModelProgress(null,1L));
        malformed(()->new ReplyBodies.ModelState("m","1",ReplyBodies.ModelStatus.ERROR,null));
        malformed(()->new ReplyBodies.Models(Collections.nCopies(17,catalogModel("m")),null));
        malformed(()->new ReplyBodies.Models(Collections.singletonList(new ReplyBodies.ModelEntry("m","1",ReplyBodies.ModelStatus.INSTALLED)),null));
        malformed(()->new ReplyBodies.ModelEntry("m","1",ReplyBodies.ModelStatus.INSTALLED,-1L,10L,"license","TTS",Collections.singletonList("vi"),true));
        malformed(()->new ReplyBodies.ModelEntry("m","1",ReplyBodies.ModelStatus.INSTALLED,10L,10L,null,"TTS",Collections.singletonList("vi"),true));
        malformed(()->new ReplyBodies.Voices(Collections.singletonList(voice),chars(81)));
        malformed(()->new ReplyBodies.Voices(Collections.nCopies(17,voice),null));
        malformed(()->new ReplyBodies.VoiceDescriptor("v","m","1","vi",chars(513),ReplyBodies.Accent.UNKNOWN,ReplyBodies.Gender.UNKNOWN,false,Collections.singletonList(ReplyBodies.RateMode.NATURAL),ReplyBodies.OutputMode.FULL_UTTERANCE));
        malformed(()->new ReplyBodies.VoiceDescriptor("v","m","1","vi","Voice",ReplyBodies.Accent.NORTH,ReplyBodies.Gender.UNKNOWN,false,Collections.singletonList(ReplyBodies.RateMode.NATURAL),ReplyBodies.OutputMode.FULL_UTTERANCE));
        malformed(()->new Reply(Reply.Kind.MODEL_PROGRESS,"r",null,null,null,null,null,null,null,null,null,null,null,new ReplyBodies.ModelProgress(0L,1L)));
        malformed(()->new Reply(Reply.Kind.SETTINGS,"r",null,null,null,null,null,null,null,null,null,null,null,new ReplyBodies.HelloAck(limits)));
        malformed(()->new Reply(Reply.Kind.ERROR,null,null,null,null,null,null,null,null,null,null,RuntimeError.SETTINGS_CONFLICT,true,saved));
        List<ReplyBodies.ModelEntry> inventory=new ArrayList<>();for(int i=0;i<65;i++)inventory.add(new ReplyBodies.ModelEntry("m"+i,"1",ReplyBodies.ModelStatus.INSTALLED));
        malformed(()->new ReplyBodies.Capabilities(ReplyBodies.Readiness.INSTALLED,limits,Collections.singletonList("DIRECT_TEXT"),Collections.emptyList(),Collections.emptyList(),false,false,false,false,false,"local-engine",inventory));
        try {page.models.clear();fail("immutable models");}catch(UnsupportedOperationException expected) { }
    }
    private static ReplyBodies.ModelEntry catalogModel(String id) {return new ReplyBodies.ModelEntry(id,"1",ReplyBodies.ModelStatus.INSTALLED,100L,200L,"license-ref","TTS",Collections.singletonList("vi"),true);}
    @Test public void semanticFixturesRejectBadShapesAndPreserveKnownAndUnknownTimes() throws Exception {
        Path root=Paths.get("").toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("tests/fixtures/android-v2/valid.json"))) root=root.getParent();
        assertNotNull("fixture repository root",root);
        List<?> valid=(List<?>)jsonValue(new JSONArray(new String(Files.readAllBytes(root.resolve("tests/fixtures/android-v2/valid.json")),StandardCharsets.UTF_8)));
        List<?> invalid=(List<?>)jsonValue(new JSONArray(new String(Files.readAllBytes(root.resolve("tests/fixtures/android-v2/invalid.json")),StandardCharsets.UTF_8)));
        for(Object value:valid) {
            Map<String,Object> fixture=object(value);Map<String,Object> message=object(fixture.get("message"));
            if(isReply(message)) {Reply dto=replyFixture(message);Map<String,Object> wire=wire(dto);assertEquals(fixture.get("name").toString(),wire,wire(replyFixture(wire)));assertFalse(wire.containsKey("body"));continue;}
            Envelope dto=fromFixture(message); accepted(dto);
            Map<String,Object> wire=wire(dto); Envelope restored=fromFixture(wire); accepted(restored);
            assertEquals(fixture.get("name").toString(),wire,wire(restored));
            assertFalse(wire.containsKey("body")); assertFalse(wire.containsKey("futureOption"));
            if(dto.body instanceof Envelope.Cues) {
                for(int i=0;i<((Envelope.Cues)dto.body).cues.size();i++) {
                    Cue first=((Envelope.Cues)dto.body).cues.get(i), second=((Envelope.Cues)restored.body).cues.get(i);
                    assertEquals(first.originalText,second.originalText); assertEquals(first.startMs,second.startMs); assertEquals(first.endMs,second.endMs);
                }
            }
        }
        for(Object value:invalid) {
            Map<String,Object> fixture=object(value);String code;
            try {Map<String,Object> message=object(fixture.get("message"));if(isReply(message)){replyFixture(message);fail(fixture.get("name").toString());code=null;}else{ValidationResult result=WireRules.validate(fromFixture(message));assertFalse(fixture.get("name").toString(),result.accepted);code=result.code;}}
            catch(IllegalArgumentException badShape) {code="MALFORMED";}
            assertEquals(fixture.get("name").toString(),fixture.get("code"),code);
        }
    }
    // Fixture adapter only. Task 6 must independently test the Android JSON/Bundle codec.
    private static boolean isReply(Map<String,Object> m) {try {Reply.Kind.valueOf(string(m,"type",true));return true;}catch(IllegalArgumentException invalid){return false;}}
    private static Reply replyFixture(Map<String,Object> m) {
        if(!Integer.valueOf(2).equals(integer(m,"major",true)))throw new IllegalArgumentException("MALFORMED");
        Reply.Kind kind=Reply.Kind.valueOf(string(m,"type",true));ReplyBodies.Body body=null;
        switch(kind) {
            case HELLO_ACK:if(!Integer.valueOf(0).equals(integer(m,"minor",true)))throw new IllegalArgumentException("MALFORMED");body=new ReplyBodies.HelloAck(fixtureLimits(required(m,"limits")));break;
            case SETTINGS:body=fixtureSettings(m);break;
            case ERROR:if("SETTINGS_CONFLICT".equals(string(m,"code",true)))body=fixtureSettings(m);break;
            case MODEL_PROGRESS:body=new ReplyBodies.ModelProgress(number(m,"bytesTransferred",true),number(m,"expectedBytes",true));break;
            case MODEL_STATE:body=new ReplyBodies.ModelState(string(m,"modelId",true),string(m,"version",true),ReplyBodies.ModelStatus.valueOf(string(m,"state",true)),error(m,"reason",false));break;
            case MODELS:body=new ReplyBodies.Models(fixtureModels(required(m,"models"),true),string(m,"nextPageToken",true));break;
            case VOICES: {
                List<ReplyBodies.VoiceDescriptor> voices=new ArrayList<>();for(Object entry:list(required(m,"voices"))) {
                    Map<String,Object> v=object(entry);List<ReplyBodies.RateMode> rates=new ArrayList<>();for(String rate:strings(required(v,"supportedRateModes")))rates.add(ReplyBodies.RateMode.valueOf(rate));
                    voices.add(new ReplyBodies.VoiceDescriptor(string(v,"id",true),string(v,"modelId",true),string(v,"version",true),string(v,"language",true),string(v,"displayName",true),ReplyBodies.Accent.valueOf(string(v,"accent",true)),ReplyBodies.Gender.valueOf(string(v,"gender",true)),bool(v,"verifiedMetadata",true),rates,ReplyBodies.OutputMode.valueOf(string(v,"outputMode",true))));
                }body=new ReplyBodies.Voices(voices,string(m,"nextPageToken",true));break;
            }
            case CAPABILITIES:
                if(!Integer.valueOf(2).equals(integer(m,"protocolMajor",true))||!Integer.valueOf(0).equals(integer(m,"minor",true)))throw new IllegalArgumentException("MALFORMED");
                body=new ReplyBodies.Capabilities(ReplyBodies.Readiness.valueOf(string(m,"state",true)),fixtureLimits(required(m,"limits")),strings(required(m,"supportedInputs")),strings(required(m,"translationPairs")),strings(required(m,"speechLanguages")),bool(m,"aiVoice",true),bool(m,"offline",true),bool(m,"multiSpeaker",true),bool(m,"lookahead",true),bool(m,"clientHoldSupported",true),string(m,"engine",true),fixtureModels(required(m,"installedModels"),false));break;
            default:break;
        }
        return new Reply(kind,string(m,"requestId",false),string(m,"sessionId",false),number(m,"revision",false),number(m,"eventSeq",false),string(m,"operationId",false),string(m,"speechId",false),string(m,"cueId",false),integer(m,"segmentIndex",false),string(m,"modelVersion",false),kind==Reply.Kind.MODEL_STATE?null:string(m,"reason",false),error(m,"code",false),bool(m,"recoverable",false),body);
    }
    private static ReplyBodies.Settings fixtureSettings(Map<String,Object> m) {
        Map<String,Object> s=object(required(m,"settings"));return new ReplyBodies.Settings(number(m,"settingsRevision",true),new SessionDescriptor.Settings(string(s,"voiceMode",true),string(s,"modelId",true),string(s,"modelVersion",true),string(s,"voiceId",true),string(s,"targetLanguage",true),string(s,"rateMode",true),string(s,"deliveryMode",true)));
    }
    private static ReplyBodies.Limits fixtureLimits(Object value) {
        Map<String,Object> l=object(value);String[] names={"maxPayloadUtf16Units","maxBundleBytes","maxCues","maxTextUtf16Units","maxIdUtf16Units","maxPageSize","maxInstalledVersions","maxReadyModels"};long[] expected={16384,65536,16,512,80,16,64,1};
        for(int i=0;i<names.length;i++)if(!Long.valueOf(expected[i]).equals(number(l,names[i],true)))throw new IllegalArgumentException("MALFORMED");return new ReplyBodies.Limits();
    }
    private static List<ReplyBodies.ModelEntry> fixtureModels(Object value,boolean catalog) {
        List<ReplyBodies.ModelEntry> result=new ArrayList<>();for(Object entry:list(value)){Map<String,Object> m=object(entry);
            result.add(catalog?new ReplyBodies.ModelEntry(string(m,"modelId",true),string(m,"version",true),ReplyBodies.ModelStatus.valueOf(string(m,"state",true)),number(m,"downloadBytes",true),number(m,"expandedBytes",true),string(m,"licenseRef",true),string(m,"task",true),strings(required(m,"languages")),bool(m,"compatible",true)):new ReplyBodies.ModelEntry(string(m,"modelId",true),string(m,"version",true),ReplyBodies.ModelStatus.valueOf(string(m,"state",true))));
        }return result;
    }
    private static RuntimeError error(Map<String,Object> m,String key,boolean required) {String value=string(m,key,required);return value==null?null:RuntimeError.valueOf(value);}
    private static List<?> list(Object value) {if(!(value instanceof List))throw new IllegalArgumentException("MALFORMED");return (List<?>)value;}
    private static List<String> strings(Object value) {List<String> result=new ArrayList<>();for(Object item:list(value)){if(!(item instanceof String))throw new IllegalArgumentException("MALFORMED");result.add((String)item);}return result;}
    private static Object jsonValue(Object value) throws Exception {
        if(value==JSONObject.NULL)return null;
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;List<Object> result=new ArrayList<>();for(int i=0;i<a.length();i++)result.add(jsonValue(a.get(i)));return result;}
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;Map<String,Object> result=new LinkedHashMap<>();Iterator<String> keys=o.keys();while(keys.hasNext()){String k=keys.next();result.put(k,jsonValue(o.get(k)));}return result;}
        return value;
    }
    private static Envelope fromFixture(Map<String,Object> m) {
        String type=string(m,"type",true); Envelope.Body body;
        switch(type) {
            case "HELLO":body=new Envelope.Hello(integer(m,"minor",true));break;
            case "SET_SETTINGS": {
                Map<String,Object> s=object(required(m,"settings"));
                body=new Envelope.SetSettings(number(m,"expectedSettingsRevision",true),new SessionDescriptor.Settings(string(s,"voiceMode",true),string(s,"modelId",true),string(s,"modelVersion",true),string(s,"voiceId",true),string(s,"targetLanguage",true),string(s,"rateMode",true),string(s,"deliveryMode",true)));break;
            }
            case "LIST_MODELS":case "LIST_VOICES":body=new Envelope.Page(string(m,"modelId",type.equals("LIST_VOICES")),string(m,"version",type.equals("LIST_VOICES")),string(m,"pageToken",false),integer(m,"pageSize",false));break;
            case "DOWNLOAD_MODEL":case "REMOVE_MODEL":case "PREPARE_MODEL":body=new Envelope.Model(string(m,"modelId",true),string(m,"version",true),bool(m,"consent",type.equals("DOWNLOAD_MODEL")));break;
            case "CANCEL_DOWNLOAD":body=new Envelope.Operation(string(m,"operationId",true));break;
            case "OPEN":body=new Envelope.Open(new SessionDescriptor(string(m,"episodeRef",true),string(m,"sourceRef",true),string(m,"trackRef",true),string(m,"inputMode",true),string(m,"trackAvailability",true),string(m,"originalLanguage",true),string(m,"targetLanguage",true),number(m,"settingsRevision",true),string(m,"modelId",true),string(m,"version",true),string(m,"voiceId",true),bool(m,"speechEnabled",true)),fixtureClock(required(m,"clock")));break;
            case "CUE_SNAPSHOT":case "TIMELINE_BATCH": {
                Object list=required(m,"cues");if(!(list instanceof List)) throw new IllegalArgumentException();List<Cue> cues=new ArrayList<>();
                for(Object entry:(List<?>)list) {Map<String,Object> c=object(entry);cues.add(new Cue(string(c,"cueId",true),string(c,"originalText",true),string(c,"language",true),number(c,"observedAtMediaMs",true),number(c,"startMs",true),number(c,"endMs",true),string(c,"origin",true),string(c,"role",true),string(c,"trackRef",true)));}
                body=new Envelope.Cues(cues);break;
            }
            case "CLOCK_ANCHOR":case "PLAY":case "PAUSE":case "SEEK":case "STOP":case "PLAYBACK_SPEED":body=new Envelope.Clock(fixtureClock(required(m,"clock")));break;
            default:body=Envelope.Empty.INSTANCE;
        }
        boolean playback=Arrays.asList("OPEN","CUE_SNAPSHOT","TIMELINE_BATCH","CLOCK_ANCHOR","PLAY","PAUSE","SEEK","STOP","PLAYBACK_SPEED","EPISODE_CHANGE","SOURCE_CHANGE","CLOSE").contains(type);
        return new Envelope(integer(m,"major",true),type,string(m,"requestId",true),string(m,"sessionId",playback),number(m,"revision",playback),number(m,"seq",playback),body);
    }
    private static ClockAnchor fixtureClock(Object value) {
        if(value==null)return null;Map<String,Object> c=object(value);Object speed=required(c,"speed");if(!(speed instanceof Number))throw new IllegalArgumentException();
        return new ClockAnchor(number(c,"positionMs",true),number(c,"sampledAtElapsedMs",true),bool(c,"playing",true),((Number)speed).doubleValue());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {if(!(value instanceof Map))throw new IllegalArgumentException();return (Map<String,Object>)value;}
    private static Object required(Map<String,Object> m,String key) {if(!m.containsKey(key))throw new IllegalArgumentException();return m.get(key);}
    private static String string(Map<String,Object> m,String key,boolean required) {Object v=required?required(m,key):m.get(key);if(v!=null&&!(v instanceof String))throw new IllegalArgumentException();return (String)v;}
    private static Long number(Map<String,Object> m,String key,boolean required) {Object v=required?required(m,key):m.get(key);if(v!=null&&!(v instanceof Long)&&!(v instanceof Integer))throw new IllegalArgumentException();return v==null?null:((Number)v).longValue();}
    private static Integer integer(Map<String,Object> m,String key,boolean required) {Long v=number(m,key,required);if(v!=null&&(v<Integer.MIN_VALUE||v>Integer.MAX_VALUE))throw new IllegalArgumentException();return v==null?null:v.intValue();}
    private static Boolean bool(Map<String,Object> m,String key,boolean required) {Object v=required?required(m,key):m.get(key);if(v!=null&&!(v instanceof Boolean))throw new IllegalArgumentException();return (Boolean)v;}
    private static Map<String,Object> wire(Envelope e) throws Exception {
        Map<String,Object> out=fields(e);out.remove("body");
        if(e.body instanceof Envelope.Open) {out.putAll(fields(((Envelope.Open)e.body).descriptor));out.put("clock",fields(((Envelope.Open)e.body).clock));}
        else out.putAll(fields(e.body));return out;
    }
    private static Map<String,Object> wire(Reply r) throws Exception {
        Map<String,Object> out=fields(r);out.remove("body");out.remove("kind");out.put("type",r.kind.name());if(r.body!=null)out.putAll(fields(r.body));return out;
    }
    private static Map<String,Object> fields(Object dto) throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();
        for(Field f:dto.getClass().getFields()) {
            if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;Object v=f.get(dto);
            if(v==null&&!(dto instanceof Cue)&&!(dto instanceof SessionDescriptor.Settings)&&!(dto instanceof ReplyBodies.Models)&&!(dto instanceof ReplyBodies.Voices))continue;
            if(v instanceof Integer) v=((Integer)v).longValue();
            if(v instanceof Enum)v=((Enum<?>)v).name();
            if(v instanceof List) {List<Object> list=new ArrayList<>();for(Object element:(List<?>)v)list.add(element instanceof Enum?((Enum<?>)element).name():element instanceof String||element instanceof Number||element instanceof Boolean?element:fields(element));v=list;}
            else if(v!=null&&!(v instanceof String)&&!(v instanceof Number)&&!(v instanceof Boolean))v=fields(v);
            out.put(f.getName(),v);
        }return out;
    }
}
