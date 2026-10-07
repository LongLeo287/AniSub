package com.anibox.tv.anisub;

import android.graphics.Bitmap;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import com.anibox.tv.anisub.protocol.AniCue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23, manifest = Config.NONE)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class Media3CueSourceTest {
    @Test public void textKeepsProvenanceAndUnknownEndWhileBitmapIsSkipped() {
        Media3CueSource source = new Media3CueSource(); List<List<AniCue>> received = new ArrayList<>();
        source.setSink(received::add);
        Cue text = new Cue.Builder().setText("Xin chào").build();
        Cue bitmap = new Cue.Builder().setBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).build();
        source.onCues(new CueGroup(Arrays.asList(text, bitmap), 0), 1200, "vi", "provider:0");
        assertEquals(1, received.get(0).size()); AniCue cue = received.get(0).get(0);
        assertEquals("Xin chào", cue.text); assertEquals(1200, cue.startMs);
        assertEquals(AniCue.UNKNOWN_END_MS, cue.endMs); assertEquals("vi", cue.language);
        assertEquals("provider:0", cue.provenance);
        source.onCues(new CueGroup(Collections.emptyList(), 0), 1500, "vi", "provider:0");
        assertTrue(received.get(1).isEmpty());
    }

    @Test public void resetClearsAndDetachedSinkReceivesNothing() {
        Media3CueSource source = new Media3CueSource(); List<List<AniCue>> received = new ArrayList<>();
        source.setSink(received::add); source.reset(); source.setSink(null); source.reset();
        assertEquals(1, received.size()); assertTrue(received.get(0).isEmpty());
        assertFalse(new MpvCueSource().supported());
    }
}
