package com.anisub.runtime.protocol;

/** Reply/event identities are distinct from client command seq. No free-form error text. */
public final class Reply {
    public enum Kind { ACK, ERROR, HELLO_ACK, CAPABILITIES, SETTINGS, MODELS, VOICES, MODEL_PROGRESS, MODEL_STATE, SPEECH_STARTED, SPEECH_FINISHED, REJECTED_CUE }
    public final int major=2;
    public final Kind kind;
    public final String requestId,sessionId,operationId,speechId,cueId,modelVersion,reason;
    public final Long revision,eventSeq;
    public final Integer segmentIndex;
    public final RuntimeError code;
    public final Boolean recoverable;
    public final ReplyBodies.Body body;
    public Reply(Kind kind,String requestId,String sessionId,Long revision,Long eventSeq,String operationId,String speechId,String cueId,Integer segmentIndex,String modelVersion,String reason,RuntimeError code,Boolean recoverable) {
        this(kind,requestId,sessionId,revision,eventSeq,operationId,speechId,cueId,segmentIndex,modelVersion,reason,code,recoverable,null);
    }
    public Reply(Kind kind,String requestId,String sessionId,Long revision,Long eventSeq,String operationId,String speechId,String cueId,Integer segmentIndex,String modelVersion,String reason,RuntimeError code,Boolean recoverable,ReplyBodies.Body body) {
        if(kind==null||!optionalId(requestId)||!optionalId(sessionId)||!optionalId(operationId)||!optionalId(speechId)||!optionalId(cueId)||!optionalId(modelVersion)
            ||revision!=null&&!WireRules.safe(revision)||eventSeq!=null&&(!WireRules.safe(eventSeq)||eventSeq<1)||segmentIndex!=null&&segmentIndex<0) throw malformed();
        if(kind==Kind.ACK&&!WireRules.id(requestId)||kind==Kind.ERROR&&(code==null||recoverable==null)) throw malformed();
        switch(kind) {
            case HELLO_ACK:need(body instanceof ReplyBodies.HelloAck&&WireRules.id(requestId));break;
            case SETTINGS:need(body instanceof ReplyBodies.Settings&&WireRules.id(requestId));break;
            case CAPABILITIES:need(body instanceof ReplyBodies.Capabilities);break;
            case MODELS:need(body instanceof ReplyBodies.Models&&WireRules.id(requestId));break;
            case VOICES:need(body instanceof ReplyBodies.Voices&&WireRules.id(requestId));break;
            case MODEL_PROGRESS:need(body instanceof ReplyBodies.ModelProgress&&WireRules.id(operationId));break;
            case MODEL_STATE:need(body instanceof ReplyBodies.ModelState);break;
            case ERROR:need(code==RuntimeError.SETTINGS_CONFLICT?body instanceof ReplyBodies.Settings&&WireRules.id(requestId):body==null);break;
            default:need(body==null);
        }
        if(kind==Kind.SPEECH_STARTED||kind==Kind.SPEECH_FINISHED||kind==Kind.REJECTED_CUE) {
            if(!WireRules.id(sessionId)||!WireRules.safe(revision)||eventSeq==null||!WireRules.id(cueId)) throw malformed();
        }
        if(kind==Kind.SPEECH_STARTED||kind==Kind.SPEECH_FINISHED) {
            if(!WireRules.id(speechId)||segmentIndex==null||!WireRules.id(modelVersion)) throw malformed();
        }
        if(kind==Kind.SPEECH_FINISHED&&!("COMPLETED".equals(reason)||"CANCELLED".equals(reason)||"FAILED".equals(reason))) throw malformed();
        if(kind==Kind.REJECTED_CUE) {
            try {RuntimeError.valueOf(reason);}catch(RuntimeException invalid) {throw malformed();}
        } else if(kind!=Kind.SPEECH_FINISHED&&reason!=null) throw malformed();
        this.kind=kind;this.requestId=requestId;this.sessionId=sessionId;this.revision=revision;this.eventSeq=eventSeq;this.operationId=operationId;
        this.speechId=speechId;this.cueId=cueId;this.segmentIndex=segmentIndex;this.modelVersion=modelVersion;this.reason=reason;this.code=code;this.recoverable=recoverable;
        this.body=body;
    }
    private static boolean optionalId(String value) {return value==null||WireRules.id(value);}
    private static IllegalArgumentException malformed() {return new IllegalArgumentException("MALFORMED");}
    private static void need(boolean value) {if(!value)throw malformed();}
}
