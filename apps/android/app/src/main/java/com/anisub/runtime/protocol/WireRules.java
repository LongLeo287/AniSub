package com.anisub.runtime.protocol;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Stateless structural rules. Ordering, clock age, READY and horizon admission belong to the coordinator. */
public final class WireRules {
    public static final int MAJOR=2, MINOR=0, MAX_JSON_UNITS=16384, MAX_BUNDLE_BYTES=65536, MAX_CUES=16, MAX_TEXT_UNITS=512, MAX_ID_UNITS=80;
    public static final long MAX_SAFE_INTEGER=9007199254740991L;
    private static final Pattern URI_PREFIX=Pattern.compile("^[a-z][a-z0-9+.-]*:",Pattern.CASE_INSENSITIVE);
    private WireRules() { }
    public static ValidationResult validate(Envelope m) {
        if(m==null || m.major==null) return bad();
        if(m.major!=MAJOR) return fail("PROTOCOL_MISMATCH");
        if(m.type==null || !id(m.requestId) || m.body==null) return bad();
        if(m.sessionId!=null && !id(m.sessionId) || m.revision!=null && !safe(m.revision) || m.seq!=null && (!safe(m.seq)||m.seq<1)) return bad();
        switch(m.type) {
            case "HELLO":
                if(!(m.body instanceof Envelope.Hello) || ((Envelope.Hello)m.body).minor==null) return bad();
                return ((Envelope.Hello)m.body).minor==MINOR?ok():fail("UNSUPPORTED");
            case "GET_CAPABILITIES":case "GET_SETTINGS": return m.body instanceof Envelope.Empty?ok():bad();
            case "SET_SETTINGS":
                if(!(m.body instanceof Envelope.SetSettings)) return bad();
                Envelope.SetSettings set=(Envelope.SetSettings)m.body;
                return safe(set.expectedSettingsRevision)?settings(set.settings):bad();
            case "LIST_MODELS":case "LIST_VOICES":
                if(!(m.body instanceof Envelope.Page)) return bad();
                Envelope.Page page=(Envelope.Page)m.body;
                if(page.pageToken!=null && !id(page.pageToken) || page.pageSize!=null && (page.pageSize<1||page.pageSize>16)) return bad();
                if(m.type.equals("LIST_VOICES") && (!id(page.modelId)||!id(page.version))) return bad();
                return ok();
            case "DOWNLOAD_MODEL":case "REMOVE_MODEL":case "PREPARE_MODEL":
                if(!(m.body instanceof Envelope.Model)) return bad();
                Envelope.Model model=(Envelope.Model)m.body;
                return id(model.modelId)&&id(model.version)&&(!m.type.equals("DOWNLOAD_MODEL")||Boolean.TRUE.equals(model.consent))?ok():bad();
            case "CANCEL_DOWNLOAD":
                return m.body instanceof Envelope.Operation && id(((Envelope.Operation)m.body).operationId)?ok():bad();
            case "OPEN":
                if(!playback(m)||!(m.body instanceof Envelope.Open)) return bad();
                Envelope.Open open=(Envelope.Open)m.body;
                if(!clock(open.clock)) return bad();
                return descriptor(open.descriptor);
            case "CUE_SNAPSHOT":case "TIMELINE_BATCH":
                if(!playback(m)||!(m.body instanceof Envelope.Cues)) return bad();
                Envelope.Cues cues=(Envelope.Cues)m.body;
                if(cues.cues==null||cues.cues.size()>MAX_CUES) return bad();
                Set<String> ids=new HashSet<>();
                for(Cue c:cues.cues) {
                    ValidationResult result=cue(c,m.type.equals("TIMELINE_BATCH"));
                    if(!result.accepted) return result;
                    if(!ids.add(c.cueId)) return bad();
                }
                return ok();
            case "CLOCK_ANCHOR":case "PLAY":case "PAUSE":case "SEEK":case "STOP":case "PLAYBACK_SPEED":
                return playback(m)&&m.body instanceof Envelope.Clock&&clock(((Envelope.Clock)m.body).clock)?ok():bad();
            case "EPISODE_CHANGE":case "SOURCE_CHANGE":case "CLOSE":
                return playback(m)&&m.body instanceof Envelope.Empty?ok():bad();
            default: return fail("UNSUPPORTED");
        }
    }
    private static boolean playback(Envelope m) { return id(m.sessionId)&&safe(m.revision)&&safe(m.seq)&&m.seq>=1; }
    public static boolean safe(Long n) { return n!=null&&n>=0&&n<=MAX_SAFE_INTEGER; }
    public static boolean id(String s) { return s!=null&&!s.isEmpty()&&s.length()<=MAX_ID_UNITS; }
    private static boolean reference(String s) { return id(s)&&!URI_PREFIX.matcher(s).find()&&!s.contains("\\"); }
    private static boolean clock(ClockAnchor c) {
        return c!=null&&safe(c.positionMs)&&safe(c.sampledAtElapsedMs)&&c.playing!=null&&c.speed!=null
            &&!c.speed.isNaN()&&!c.speed.isInfinite()&&c.speed>=0.5&&c.speed<=2;
    }
    private static ValidationResult descriptor(SessionDescriptor d) {
        if(d==null||!reference(d.episodeRef)||!reference(d.sourceRef)||!reference(d.trackRef)||!safe(d.settingsRevision)
            ||!id(d.modelId)||!id(d.version)||!id(d.voiceId)||d.speechEnabled==null
            ||d.inputMode==null||d.trackAvailability==null||d.originalLanguage==null||d.targetLanguage==null) return bad();
        if(!d.originalLanguage.equals(d.targetLanguage)) return fail("UNSUPPORTED_TRANSLATION");
        if(!"vi".equals(d.originalLanguage)||!"DIRECT_TEXT".equals(d.inputMode)||!"TEXT_TRACK".equals(d.trackAvailability)) return fail("UNSUPPORTED");
        return ok();
    }
    private static ValidationResult settings(SessionDescriptor.Settings s) {
        if(s==null||s.voiceMode==null||s.targetLanguage==null||s.rateMode==null||s.deliveryMode==null) return bad();
        if(!"vi".equals(s.targetLanguage)) return fail("UNSUPPORTED_TRANSLATION");
        if(!("AUTO".equals(s.voiceMode)||"EXPLICIT".equals(s.voiceMode))||!("AUTO".equals(s.rateMode)||"NATURAL".equals(s.rateMode))||!"LIVE_BOUNDED".equals(s.deliveryMode)) return fail("UNSUPPORTED");
        if(s.modelId!=null&&!id(s.modelId)||s.modelVersion!=null&&!id(s.modelVersion)||s.voiceId!=null&&!id(s.voiceId)) return bad();
        return "EXPLICIT".equals(s.voiceMode)&&(!id(s.modelId)||!id(s.modelVersion)||!id(s.voiceId))?bad():ok();
    }
    private static ValidationResult cue(Cue c,boolean timeline) {
        if(c==null||!id(c.cueId)||c.originalText==null||c.originalText.length()>MAX_TEXT_UNITS||!reference(c.trackRef)
            ||!safe(c.observedAtMediaMs)||c.startMs!=null&&!safe(c.startMs)||c.endMs!=null&&!safe(c.endMs)
            ||c.startMs!=null&&c.endMs!=null&&c.endMs<c.startMs||timeline&&(c.startMs==null||c.endMs==null)
            ||c.language==null||c.origin==null||c.role==null) return bad();
        if(!"vi".equals(c.language)||!"DIRECT".equals(c.origin)||!("DIALOGUE".equals(c.role)||"ANNOTATION".equals(c.role)||"UNKNOWN".equals(c.role))) return fail("UNSUPPORTED");
        return ok();
    }
    private static ValidationResult ok() {return ValidationResult.accept();}
    private static ValidationResult bad() {return fail("MALFORMED");}
    private static ValidationResult fail(String code) {return ValidationResult.reject(code);}
}
