package com.anisub.runtime.protocol;

public final class SessionDescriptor {
    public final String episodeRef,sourceRef,trackRef,inputMode,trackAvailability,originalLanguage,targetLanguage,modelId,version,voiceId;
    public final Long settingsRevision;
    public final Boolean speechEnabled;
    public SessionDescriptor(String episodeRef,String sourceRef,String trackRef,String inputMode,String trackAvailability,String originalLanguage,String targetLanguage,Long settingsRevision,String modelId,String version,String voiceId,Boolean speechEnabled) {
        this.episodeRef=episodeRef;this.sourceRef=sourceRef;this.trackRef=trackRef;this.inputMode=inputMode;this.trackAvailability=trackAvailability;
        this.originalLanguage=originalLanguage;this.targetLanguage=targetLanguage;this.settingsRevision=settingsRevision;
        this.modelId=modelId;this.version=version;this.voiceId=voiceId;this.speechEnabled=speechEnabled;
    }
    public static final class Settings {
        public final String voiceMode,modelId,modelVersion,voiceId,targetLanguage,rateMode,deliveryMode;
        public Settings(String voiceMode,String modelId,String modelVersion,String voiceId,String targetLanguage,String rateMode,String deliveryMode) {
            this.voiceMode=voiceMode;this.modelId=modelId;this.modelVersion=modelVersion;this.voiceId=voiceId;
            this.targetLanguage=targetLanguage;this.rateMode=rateMode;this.deliveryMode=deliveryMode;
        }
    }
}
