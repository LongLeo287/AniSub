package com.anisub.runtime;

import java.util.Collections;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceSelectionTest {
    private static final class Env implements OpenRules.Env {
        boolean ready = true, enabled = true;
        public boolean systemReady(){return true;}
        public boolean packReady(String lang){return ready && "vi".equals(lang);}
        public Set<String> packVoices(String lang){return "vi".equals(lang) ? Collections.singleton("ai-vi") : Collections.emptySet();}
        public boolean knownAiVoice(String id,String lang){return packVoices(lang).contains(id);}
        public boolean voiceEnabled(String id){return enabled;}
        public boolean systemVoiceReady(String id,String lang){return "sys-en".equals(id)&&"en".equals(lang);}
        public boolean translateAvailable(){return false;}
        public String translateUnavailableReason(){return "NO_ENGINE";}
        public boolean modelReady(String lang){return true;}
        public boolean detectAvailable(){return false;}
        public boolean autoDownloadModels(){return false;}
        public boolean canAutoDownload(){return false;}
    }
    private OpenRules.Decision decide(JSONObject open, String source, Env env) throws Exception {
        return OpenRules.decide(open,source,env,1f);
    }
    private JSONObject ai() throws Exception {return new JSONObject().put("mode","ai");}
    @Test public void absentNullAndEqualAliasesPreserveAiAdmission() throws Exception {
        Env env=new Env(); assertTrue(decide(ai(),"vi",env).accepted());
        assertTrue(decide(ai().put("voiceId",JSONObject.NULL),"vi",env).accepted());
        assertEquals("ai-vi",decide(ai().put("voiceId","ai-vi").put("voice","ai-vi"),"vi",env).voiceId);
        assertEquals("ai-vi",decide(ai().put("voice","ai-vi"),"vi",env).voiceId);
    }
    @Test public void conflictingAndMalformedIdsAreMalformed() throws Exception {
        for(Object id:new Object[]{0,true,"",new String(new char[81]).replace('\0','x')}) {
            try {decide(ai().put("voiceId",id),"vi",new Env());fail();}catch(JSONException expected){}
        }
        try {decide(ai().put("voiceId","ai-vi").put("voice","other"),"vi",new Env());fail();}catch(JSONException expected){}
    }
    @Test public void wrongKindLanguageUnknownAndDisabledNeverFallback() throws Exception {
        Env env=new Env();
        assertEquals("UNSUPPORTED",decide(ai().put("voiceId","sys-en"),"vi",env).error);
        assertEquals("UNSUPPORTED",decide(ai().put("voiceId","unknown"),"vi",env).error);
        assertEquals("UNSUPPORTED",decide(ai().put("voiceId","ai-vi").put("voiceLang","en"),"en",env).error);
        env.enabled=false;assertEquals("UNSUPPORTED",decide(ai().put("voiceId","ai-vi"),"vi",env).error);
    }
    @Test public void knownMissingAiPackHasDedicatedError() throws Exception {
        Env env=new Env();env.ready=false;
        assertEquals("VOICE_PACK_MISSING",decide(ai().put("voiceId","ai-vi"),"vi",env).error);
    }
    @Test public void legacySystemIgnoresVoiceAliasAndDoesNotTranslate() throws Exception {
        JSONObject open=new JSONObject().put("mode","system").put("systemTest",true).put("voice","unknown");
        OpenRules.Decision d=decide(open,"vi",new Env());assertTrue(d.accepted());assertNull(d.voiceId);assertFalse(d.translate);
        assertFalse(decide(open,"en",new Env()).accepted());
    }
    @Test public void explicitLocalEnglishSystemRequiresMatchingSourceAndTestOptIn() throws Exception {
        JSONObject open=new JSONObject().put("mode","system").put("voiceId","sys-en").put("voiceLang","en").put("systemTest",true);
        OpenRules.Decision d=decide(open,"en-US",new Env());assertTrue(d.accepted());assertFalse(d.translate);
        assertEquals("UNSUPPORTED",decide(open,"vi",new Env()).error);
        open.put("systemTest",false);assertEquals("UNSUPPORTED",decide(open,"en",new Env()).error);
    }
}
