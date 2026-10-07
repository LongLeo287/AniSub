package com.anibox.tv.anisub;

import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import com.anibox.tv.anisub.protocol.AniCue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts Media3 active cues. CueGroup gives no per-cue end, so never invent a duration. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class Media3CueSource implements CueSource {
    private Sink sink;
    @Override public void setSink(Sink sink) { this.sink = sink; }
    @Override public void reset() { if (sink != null) sink.onCues(Collections.emptyList()); }

    public void onCues(CueGroup group, long positionMs, String language, String provenance) {
        if (sink == null || group == null) return;
        List<AniCue> snapshot = new ArrayList<>();
        for (Cue cue : group.cues) {
            // Bitmap tracks need an OCR capability in AniSub; this port accepts direct text only.
            if (cue.text == null || cue.text.toString().trim().isEmpty()) continue;
            String text = cue.text.toString();
            if (text.length() > 4096 || snapshot.size() >= 32) continue;
            snapshot.add(new AniCue(text, Math.max(0, positionMs), AniCue.UNKNOWN_END_MS, language, provenance));
        }
        sink.onCues(Collections.unmodifiableList(snapshot));
    }
}
