package com.anisub.runtime;

import com.anisub.runtime.settings.AniSubPrefs;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoiceRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceMetadataTest {
    private AniSubPrefs preferences(){
        Map<String,String> values=new HashMap<>();
        return new AniSubPrefs(new AniSubPrefs.Store(){
            public String getString(String key,String def){return values.containsKey(key)?values.get(key):def;}
            public void putString(String key,String value){values.put(key,value);}
            public void remove(String key){values.remove(key);}
        });
    }
    @Test public void readingPreferencesAreOptionalAndKeepLegacyUnchanged() throws Exception {
        JSONObject base=Capabilities.build(false,"missing",null,"IDLE","0.3.0",3);
        String original=base.toString();AniSubPrefs prefs=preferences();prefs.setDuckLevel(45);
        JSONObject out=Capabilities.withReadingPreferences(base,prefs);
        assertEquals(original,base.toString());assertEquals(45,out.getJSONObject("readingPreferences").getInt("duckLevel"));
        assertEquals("vi",out.getJSONObject("readingPreferences").getJSONArray("languagePriority").getString(0));
        assertFalse(out.getBoolean("aiVoice"));assertTrue(out.toString().length()<=16384);
    }
    @Test public void uninstalledSystemVoiceNeverAdvertisesInstalled() throws Exception {
        VoiceCatalog catalog=VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")),StandardCharsets.UTF_8));
        List<VoiceRegistry.SystemVoice> systems=new ArrayList<>();
        systems.add(new VoiceRegistry.SystemVoice("test.engine","absent","vi",false));
        VoiceRegistry registry=new VoiceRegistry(catalog,id->false,systems,preferences());
        JSONObject out=Capabilities.withVoiceMetadata(Capabilities.build(false,"missing",null,"IDLE","0.3.0",3),registry);
        assertFalse(out.getJSONArray("systemVoices").getJSONObject(0).getBoolean("installed"));
        assertFalse(out.getJSONArray("systemVoices").getJSONObject(0).getBoolean("default"));
    }
    @Test public void installedPackDoesNotPromoteNewVoiceAbsentFromItsDescriptor() throws Exception {
        VoiceCatalog catalog=VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")),StandardCharsets.UTF_8));
        VoiceRegistry registry=new VoiceRegistry(catalog,new VoiceRegistry.PackStates(){
            public boolean installed(String pack){return true;}
            public boolean voiceInstalled(String pack,VoiceCatalog.Voice voice){return "vais1000".equals(voice.id);}
        },new ArrayList<>(),preferences());
        assertTrue(registry.find("vais1000").installed);
        assertFalse(registry.find("cake-quang-huy").installed);
        assertEquals("vais1000",registry.defaultVoice(VoiceRegistry.Kind.AI,"vi").id);
    }
    private VoiceRegistry registry(boolean large) throws Exception {
        VoiceCatalog catalog=VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")),StandardCharsets.UTF_8));
        Map<String,String> values=new HashMap<>();
        AniSubPrefs prefs=new AniSubPrefs(new AniSubPrefs.Store(){
            public String getString(String key,String def){return values.containsKey(key)?values.get(key):def;}
            public void putString(String key,String value){values.put(key,value);}
            public void remove(String key){values.remove(key);}
        });
        List<VoiceRegistry.SystemVoice> systems=new ArrayList<>();
        for(int i=0;i<(large?64:1);i++)systems.add(new VoiceRegistry.SystemVoice("test.engine",
                "local-"+i+(large?new String(new char[300]).replace('\0','x'):""),"en",true));
        prefs.setDefaultVoice("SYSTEM","en",systems.get(0).id);
        return new VoiceRegistry(catalog,id->false,systems,prefs);
    }
    @Test public void systemMetadataCannotPromoteLegacyAiOrTranslation() throws Exception {
        JSONObject base=Capabilities.build(false,"missing",null,"IDLE","0.3.0",3);
        String unchanged=base.toString();JSONObject decorated=Capabilities.withVoiceMetadata(base,registry(false));
        assertEquals(unchanged,base.toString());assertFalse(decorated.getBoolean("aiVoice"));
        assertFalse(decorated.getBoolean("translation"));assertFalse(decorated.getBoolean("multiSpeaker"));
        assertEquals("android-system-tts",decorated.getString("engine"));
        assertEquals(0,decorated.getJSONArray("modes").length());
        JSONObject voice=decorated.getJSONArray("systemVoices").getJSONObject(0);
        assertEquals("unknown",voice.getString("gender"));assertTrue(voice.getBoolean("default"));
    }
    @Test public void optionalInventoryFitsWireBudgetWithoutDroppingLegacyFields() throws Exception {
        JSONObject base=Capabilities.build(false,"missing",null,"IDLE","0.3.0",3);
        JSONObject decorated=Capabilities.withVoiceMetadata(base,registry(true));
        assertTrue(decorated.toString().length()<=16384);
        assertTrue(decorated.has("voicePack"));assertTrue(decorated.has("translate"));assertTrue(decorated.has("runtime"));
        assertTrue(decorated.getJSONArray("systemVoices").length()<64);
    }
}
