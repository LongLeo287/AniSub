package com.anibox.tv.anisub;

import androidx.media3.common.text.CueGroup;

/** Thin owner of cue-source wiring; subtitle rendering in AniBox remains independent. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class AniSubCueAdapter {
    private final AniSubClient client;
    private CueSource source;

    public AniSubCueAdapter(AniSubClient client) { this.client = client; }
    public void use(CueSource source) {
        if (this.source != null) { this.source.reset(); this.source.setSink(null); }
        this.source = source;
        if (source != null) source.setSink(client::cues);
    }
    public void onCues(CueGroup group, long positionMs, String language, String provenance) {
        if (client.acceptsCues() && source instanceof Media3CueSource)
            ((Media3CueSource) source).onCues(group, positionMs, language, provenance);
    }
    public void reset() { if (source != null) source.reset(); }
    public void release() { use(null); }
}
