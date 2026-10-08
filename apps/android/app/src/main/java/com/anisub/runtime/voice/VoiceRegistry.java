package com.anisub.runtime.voice;

import com.anisub.runtime.settings.AniSubPrefs;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable catalog/readiness snapshot. AI and SYSTEM defaults are deliberately independent. */
public final class VoiceRegistry {
    public enum Kind { AI, SYSTEM }
    public interface PackStates {
        boolean installed(String packId);
        default boolean voiceInstalled(String packId, VoiceCatalog.Voice voice){return installed(packId);}
    }
    public static final class Entry {
        public final String id, language, name, gender, accent, packId, engine, voiceName;
        public final Kind kind; public final boolean installed, enabled; public final int speakerId;
        Entry(String id, Kind kind, String language, String name, String gender, String accent, String packId,
              String engine, String voiceName, boolean installed, boolean enabled, int speakerId) {
            this.id=id; this.kind=kind; this.language=language; this.name=name; this.gender=gender; this.accent=accent;
            this.packId=packId; this.engine=engine; this.voiceName=voiceName; this.installed=installed; this.enabled=enabled; this.speakerId=speakerId;
        }
        public boolean usable() { return installed && enabled; }
    }
    public static final class SystemVoice {
        public final String id, engine, voiceName, language; public final boolean installed;
        public final SystemPitchEstimator.Result pitchEstimate;
        public SystemVoice(String engine, String voiceName, String language, boolean installed) {
            this(engine,voiceName,language,installed,null);
        }
        public SystemVoice(String engine, String voiceName, String language, boolean installed, SystemPitchEstimator.Result estimate) {
            this.id=systemId(engine,voiceName); this.engine=engine; this.voiceName=voiceName; this.language=language; this.installed=installed;
            pitchEstimate=estimate;
        }
    }
    public static String systemId(String engine, String name) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest((engine+"\0"+name).getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder("sys:"); for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255)); return b.toString();
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private final List<Entry> entries; private final AniSubPrefs prefs;
    public VoiceRegistry(VoiceCatalog catalog, PackStates states, List<SystemVoice> systems, AniSubPrefs prefs) {
        this.prefs=prefs; List<Entry> all=new ArrayList<>();
        for(VoiceCatalog.Pack p:catalog.packs)for(VoiceCatalog.Voice v:p.voices)
            all.add(new Entry(v.id,Kind.AI,p.language,v.name,v.gender,v.accent,p.id,null,null,states.voiceInstalled(p.id,v),prefs.voiceEnabled(v.id),v.speakerId));
        if(systems!=null)for(SystemVoice s:systems)all.add(new Entry(s.id,Kind.SYSTEM,s.language,s.voiceName,
                s.pitchEstimate==null?"unknown":s.pitchEstimate.gender,"unknown",null,s.engine,s.voiceName,s.installed,prefs.voiceEnabled(s.id),0));
        entries=Collections.unmodifiableList(all);
    }
    public List<Entry> all(){return entries;}
    public Entry find(String id){for(Entry e:entries)if(e.id.equals(id))return e;return null;}
    public Entry defaultVoice(Kind kind,String language){
        Entry chosen=find(prefs.defaultVoice(kind.name(),language));
        if(chosen!=null&&chosen.kind==kind&&chosen.language.equals(language)&&chosen.usable())return chosen;
        for(Entry e:entries)if(e.kind==kind&&e.language.equals(language)&&e.usable())return e;
        return null;
    }
    public boolean canMakeDefault(String id){Entry e=find(id);return e!=null&&e.usable();}
    /** Removing a default atomically chooses another usable voice of the same kind/language. */
    public boolean canDeletePack(String id){
        boolean affectsVi=false;for(Entry e:entries)if(e.kind==Kind.AI&&id.equals(e.packId)&&e.usable()&&"vi".equals(e.language))affectsVi=true;
        if(!affectsVi)return true;
        for(Entry e:entries)if(e.kind==Kind.AI&&e.usable()&&"vi".equals(e.language)&&!id.equals(e.packId))return true;
        return false;
    }
    public boolean canDisable(String id){
        Entry e=find(id);if(e==null||!e.enabled)return false;
        if(e.kind!=Kind.AI||!"vi".equals(e.language)||!e.installed)return true;
        for(Entry v:entries)if(v.kind==Kind.AI&&v.usable()&&"vi".equals(v.language)&&!v.id.equals(id))return true;
        return false;
    }
    public Entry replacement(Entry removed,String removedPack){
        for(Entry e:entries)if(e.kind==removed.kind&&e.language.equals(removed.language)&&e.usable()&&!e.id.equals(removed.id)
                &&(removedPack==null||!removedPack.equals(e.packId)))return e;return null;
    }
}
