package com.anisub.runtime.ai;

import org.junit.Test;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class TextSplitterTest {
    private static String join(List<String> parts) { StringBuilder b = new StringBuilder(); for (String p : parts) b.append(p); return b.toString(); }
    private static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

    @Test public void shortTextIsOneSegment() {
        assertEquals(1, TextSplitter.split("Xin chào.").size());
        assertTrue(TextSplitter.split("").isEmpty());
    }

    @Test public void prefersSentenceThenClauseThenWordBoundaries() {
        String s = repeat("Đây là câu thứ nhất khá dài. ", 4) + repeat("rồi một mệnh đề, ", 6) + repeat("chữ ", 40);
        List<String> parts = TextSplitter.split(s, 60);
        assertEquals(s, join(parts));
        assertTrue(parts.get(0).endsWith(". "));
        for (String p : parts) assertTrue(p.length() <= 60);
    }

    @Test public void noPunctuationSplitsAtWhitespaceWithoutLosingCharacters() {
        String s = repeat("không ", 100).trim();
        List<String> parts = TextSplitter.split(s, 40);
        assertEquals(s, join(parts));
        for (int i = 0; i < parts.size() - 1; i++) assertTrue(parts.get(i).endsWith(" "));
    }

    @Test public void neverSplitsSurrogatePairsOrCombiningMarks() {
        // Decomposed Vietnamese (e + circumflex + acute) and emoji surrogate pairs, no spaces at all.
        String unit = "ế😀a";
        String s = repeat(unit, 120);
        List<String> parts = TextSplitter.split(s, 17);
        assertEquals(s, join(parts));
        for (String p : parts) {
            assertFalse("starts with low surrogate", Character.isLowSurrogate(p.charAt(0)));
            assertFalse("ends with high surrogate", Character.isHighSurrogate(p.charAt(p.length() - 1)));
            int type = Character.getType(p.codePointAt(0));
            assertNotEquals(Character.NON_SPACING_MARK, type);
        }
    }

    @Test public void punctuationInsideNumbersIsNotABoundary() {
        String s = "Giá là 3.5 triệu và 1,2 tỷ " + repeat("đồng ", 30);
        List<String> parts = TextSplitter.split(s, 24);
        assertEquals(s, join(parts));
        for (String p : parts) assertFalse(p.endsWith("3.") || p.endsWith("1,"));
    }

    @Test public void randomUnicodeRoundTrips() {
        Random r = new Random(7);
        String alphabet = "aăâbcdđeêghiklmnoôơpqrstuưvxy ÁÀẢÃẠ.,!?;:́̀😀\n";
        for (int iter = 0; iter < 500; iter++) {
            StringBuilder b = new StringBuilder();
            int n = r.nextInt(512) + 1;
            while (b.length() < n) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
            String s = b.toString();
            // Random strings may contain lone surrogates; the splitter must still keep every unit.
            assertEquals(s, join(TextSplitter.split(s, 8 + r.nextInt(200))));
        }
    }

    @Test public void speakableNeedsLetterOrDigit() {
        assertFalse(TextSplitter.speakable("... ♪ !!"));
        assertTrue(TextSplitter.speakable("♪ Ừ"));
        assertTrue(TextSplitter.speakable("100"));
    }
}
