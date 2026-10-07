package com.anisub.runtime.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded protocol results only: no model storage, provider or Android dependencies. */
public final class ReplyBodies {
    private ReplyBodies() { }
    public abstract static class Body { private Body() { } }
    public enum Readiness { INITIALIZING, MODEL_MISSING, INSTALLED, PREPARING, READY, UNAVAILABLE, ERROR }
    public enum ModelStatus { NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, PREPARING, READY, ERROR, INCOMPATIBLE }
    public enum Accent { UNKNOWN, NORTH, CENTRAL, SOUTH }
    public enum Gender { UNKNOWN, MALE, FEMALE }
    public enum RateMode { NATURAL, AUTO }
    public enum OutputMode { FULL_UTTERANCE, STREAMING_PCM }
    public static final class Limits {
        public final int maxPayloadUtf16Units=16384,maxBundleBytes=65536,maxCues=16,maxTextUtf16Units=512,maxIdUtf16Units=80;
        public final int maxPageSize=16,maxInstalledVersions=64,maxReadyModels=1;
    }
    public static final class HelloAck extends Body {
        public final int minor=0;
        public final Limits limits;
        public HelloAck(Limits limits) {need(limits!=null);this.limits=limits;}
    }
    public static final class Settings extends Body {
        public final Long settingsRevision;
        public final SessionDescriptor.Settings settings;
        public Settings(Long settingsRevision,SessionDescriptor.Settings settings) {
            need(WireRules.safe(settingsRevision));
            need(WireRules.validate(new Envelope(2,"SET_SETTINGS","validation",null,null,null,new Envelope.SetSettings(settingsRevision,settings))).accepted);
            this.settingsRevision=settingsRevision;this.settings=settings;
        }
    }
    public static final class ModelEntry {
        public final String modelId,version;
        public final ModelStatus state;
        public final Long downloadBytes,expandedBytes;
        public final String licenseRef,task;
        public final List<String> languages;
        public final Boolean compatible;
        public ModelEntry(String modelId,String version,ModelStatus state) {
            this(modelId,version,state,null,null,null,null,null,null);
        }
        public ModelEntry(String modelId,String version,ModelStatus state,Long downloadBytes,Long expandedBytes,String licenseRef,String task,List<String> languages,Boolean compatible) {
            need(WireRules.id(modelId)&&WireRules.id(version)&&state!=null);
            boolean catalog=downloadBytes!=null||expandedBytes!=null||licenseRef!=null||task!=null||languages!=null||compatible!=null;
            if(catalog) {
                need(WireRules.safe(downloadBytes)&&downloadBytes>0&&WireRules.safe(expandedBytes)&&expandedBytes>0&&WireRules.id(licenseRef)&&"TTS".equals(task)&&compatible!=null);
                this.languages=copy(languages,16);need(!this.languages.isEmpty());for(String language:this.languages)need("vi".equals(language));
                need(state!=ModelStatus.READY||compatible);need(state!=ModelStatus.INCOMPATIBLE||!compatible);
            } else this.languages=null;
            this.modelId=modelId;this.version=version;this.state=state;
            this.downloadBytes=downloadBytes;this.expandedBytes=expandedBytes;this.licenseRef=licenseRef;this.task=task;this.compatible=compatible;
        }
    }
    public static final class VoiceDescriptor {
        public final String id,modelId,version,language,displayName;
        public final Accent accent;
        public final Gender gender;
        public final Boolean verifiedMetadata;
        public final List<RateMode> supportedRateModes;
        public final OutputMode outputMode;
        public VoiceDescriptor(String id,String modelId,String version,String language,String displayName,Accent accent,Gender gender,Boolean verifiedMetadata,List<RateMode> supportedRateModes,OutputMode outputMode) {
            need(WireRules.id(id)&&WireRules.id(modelId)&&WireRules.id(version)&&"vi".equals(language)&&displayName!=null&&!displayName.isEmpty()&&displayName.length()<=512);
            need(accent!=null&&gender!=null&&verifiedMetadata!=null&&outputMode!=null);
            need(Boolean.TRUE.equals(verifiedMetadata)||accent==Accent.UNKNOWN&&gender==Gender.UNKNOWN);
            this.supportedRateModes=copy(supportedRateModes,2);need(!this.supportedRateModes.isEmpty()&&new java.util.HashSet<>(this.supportedRateModes).size()==this.supportedRateModes.size());
            this.id=id;this.modelId=modelId;this.version=version;this.language=language;this.displayName=displayName;this.accent=accent;this.gender=gender;this.verifiedMetadata=verifiedMetadata;this.outputMode=outputMode;
        }
    }
    public static final class Models extends Body {
        public final List<ModelEntry> models;
        public final String nextPageToken;
        public Models(List<ModelEntry> models,String nextPageToken) {
            this.models=copy(models,16);for(ModelEntry model:this.models)need(model.task!=null);
            need(optionalId(nextPageToken));this.nextPageToken=nextPageToken;
        }
    }
    public static final class Voices extends Body {
        public final List<VoiceDescriptor> voices;
        public final String nextPageToken;
        public Voices(List<VoiceDescriptor> voices,String nextPageToken) {this.voices=copy(voices,16);need(optionalId(nextPageToken));this.nextPageToken=nextPageToken;}
    }
    public static final class ModelProgress extends Body {
        public final Long bytesTransferred,expectedBytes;
        public ModelProgress(Long bytesTransferred,Long expectedBytes) {
            need(WireRules.safe(bytesTransferred)&&WireRules.safe(expectedBytes)&&bytesTransferred<=expectedBytes);
            this.bytesTransferred=bytesTransferred;this.expectedBytes=expectedBytes;
        }
    }
    public static final class ModelState extends Body {
        public final String modelId,version;
        public final ModelStatus state;
        public final RuntimeError reason;
        public ModelState(String modelId,String version,ModelStatus state,RuntimeError reason) {
            need(WireRules.id(modelId)&&WireRules.id(version)&&state!=null&&(state!=ModelStatus.ERROR||reason!=null));
            this.modelId=modelId;this.version=version;this.state=state;this.reason=reason;
        }
    }
    public static final class Capabilities extends Body {
        public final int protocolMajor=2,minor=0;
        public final Readiness state;
        public final Limits limits;
        public final List<String> supportedInputs,translationPairs,speechLanguages;
        public final Boolean aiVoice,offline,multiSpeaker,lookahead,clientHoldSupported;
        public final String engine;
        public final List<ModelEntry> installedModels;
        public Capabilities(Readiness state,Limits limits,List<String> supportedInputs,List<String> translationPairs,List<String> speechLanguages,Boolean aiVoice,Boolean offline,Boolean multiSpeaker,Boolean lookahead,Boolean clientHoldSupported,String engine,List<ModelEntry> installedModels) {
            need(state!=null&&limits!=null&&aiVoice!=null&&offline!=null&&multiSpeaker!=null&&lookahead!=null&&clientHoldSupported!=null&&WireRules.id(engine));
            need(!multiSpeaker&&!clientHoldSupported);
            this.supportedInputs=copy(supportedInputs,16);for(String input:this.supportedInputs)need("DIRECT_TEXT".equals(input));
            this.translationPairs=copy(translationPairs,0);
            this.speechLanguages=copy(speechLanguages,16);for(String language:this.speechLanguages)need("vi".equals(language));
            this.installedModels=copy(installedModels,64);int ready=0;
            java.util.Set<String> versions=new java.util.HashSet<>();
            for(ModelEntry model:this.installedModels) {need(model.state==ModelStatus.INSTALLED||model.state==ModelStatus.PREPARING||model.state==ModelStatus.READY);need(versions.add(model.modelId+"\u0000"+model.version));if(model.state==ModelStatus.READY)ready++;}
            need(ready<=1&&(state!=Readiness.READY||ready==1)&&(ready==0||state==Readiness.READY));
            need(!aiVoice||state==Readiness.READY&&offline&&!this.speechLanguages.isEmpty());
            need(!offline||state==Readiness.READY);
            this.state=state;this.limits=limits;this.aiVoice=aiVoice;this.offline=offline;this.multiSpeaker=multiSpeaker;this.lookahead=lookahead;this.clientHoldSupported=clientHoldSupported;this.engine=engine;
        }
    }
    private static boolean optionalId(String value) {return value==null||WireRules.id(value);}
    private static void need(boolean condition) {if(!condition)throw new IllegalArgumentException("MALFORMED");}
    private static <T> List<T> copy(List<T> list,int max) {
        need(list!=null&&list.size()<=max);List<T> result=new ArrayList<>(list);
        for(T item:result)need(item!=null);return Collections.unmodifiableList(result);
    }
}
