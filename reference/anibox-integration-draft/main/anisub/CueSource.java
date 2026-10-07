package com.anibox.tv.anisub;

import com.anibox.tv.anisub.protocol.AniCue;
import java.util.List;

/** Engine-neutral active text snapshot boundary. Empty snapshots clear subtitle state. */
public interface CueSource {
    interface Sink { void onCues(List<AniCue> cues); }
    void setSink(Sink sink);
    void reset();
}
