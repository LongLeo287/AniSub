package com.anisub.runtime.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Internal typed body; the JSON contract stays flat at the envelope root. */
public final class Envelope {
    public final Integer major;
    public final String type,requestId,sessionId;
    public final Long revision,seq;
    public final Body body;
    public Envelope(Integer major,String type,String requestId,String sessionId,Long revision,Long seq,Body body) {
        this.major=major;this.type=type;this.requestId=requestId;this.sessionId=sessionId;this.revision=revision;this.seq=seq;this.body=body;
    }
    public abstract static class Body { private Body() { } }
    public static final class Empty extends Body { public static final Empty INSTANCE=new Empty(); private Empty() { } }
    public static final class Hello extends Body { public final Integer minor; public Hello(Integer minor) {this.minor=minor;} }
    public static final class Clock extends Body { public final ClockAnchor clock; public Clock(ClockAnchor clock) {this.clock=clock;} }
    public static final class Open extends Body {
        public final SessionDescriptor descriptor;public final ClockAnchor clock;
        public Open(SessionDescriptor descriptor,ClockAnchor clock) {this.descriptor=descriptor;this.clock=clock;}
    }
    public static final class Cues extends Body {
        public final List<Cue> cues;
        public Cues(List<Cue> cues) {
            if(cues!=null && cues.size()>16) throw new IllegalArgumentException("MALFORMED");
            this.cues=cues==null?null:Collections.unmodifiableList(new ArrayList<>(cues));
        }
    }
    public static final class SetSettings extends Body {
        public final Long expectedSettingsRevision;public final SessionDescriptor.Settings settings;
        public SetSettings(Long expectedSettingsRevision,SessionDescriptor.Settings settings) {this.expectedSettingsRevision=expectedSettingsRevision;this.settings=settings;}
    }
    public static final class Page extends Body {
        public final String modelId,version,pageToken;public final Integer pageSize;
        public Page(String modelId,String version,String pageToken,Integer pageSize) {this.modelId=modelId;this.version=version;this.pageToken=pageToken;this.pageSize=pageSize;}
    }
    public static final class Model extends Body {
        public final String modelId,version;public final Boolean consent;
        public Model(String modelId,String version,Boolean consent) {this.modelId=modelId;this.version=version;this.consent=consent;}
    }
    public static final class Operation extends Body {public final String operationId;public Operation(String operationId) {this.operationId=operationId;} }
}
