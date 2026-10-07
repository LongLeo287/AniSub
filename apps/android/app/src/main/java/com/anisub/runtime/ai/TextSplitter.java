package com.anisub.runtime.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits cue text into bounded synthesis segments without losing or reordering a single
 * UTF-16 unit: concatenating the result always equals the input. Preference order is
 * sentence punctuation, clause punctuation, whitespace, then a hard cut that never separates
 * a surrogate pair or a base character from its combining marks.
 */
public final class TextSplitter {
    public static final int DEFAULT_MAX_UNITS = 180;
    private TextSplitter() { }

    public static List<String> split(String text) { return split(text, DEFAULT_MAX_UNITS); }

    public static List<String> split(String text, int maxUnits) {
        if (text == null) throw new IllegalArgumentException("text");
        if (maxUnits < 8) throw new IllegalArgumentException("maxUnits");
        if (text.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        int start = 0, n = text.length();
        while (n - start > maxUnits) {
            int limit = start + maxUnits;
            int cut = lastBoundary(text, start, limit, true);
            if (cut <= start) cut = lastBoundary(text, start, limit, false);
            if (cut <= start) cut = lastWhitespace(text, start, limit);
            if (cut <= start) cut = safeHardCut(text, start, limit);
            out.add(text.substring(start, cut));
            start = cut;
        }
        if (start < n) out.add(text.substring(start));
        return out;
    }

    /** Index just after a punctuation run and its trailing spaces, within (start, limit]. */
    private static int lastBoundary(String s, int start, int limit, boolean sentence) {
        for (int i = limit - 1; i > start; i--) {
            char c = s.charAt(i - 1);
            if (sentence ? isSentenceEnd(c) : isClauseEnd(c)) {
                int j = i;
                while (j < limit && Character.isWhitespace(s.charAt(j))) j++;
                if (j == i && i < s.length() && !Character.isWhitespace(s.charAt(i)) && !isSentenceEnd(s.charAt(i)) && !isClauseEnd(s.charAt(i))) {
                    // Punctuation glued to the next word (e.g. "3.5", "a,b"): not a boundary.
                    continue;
                }
                if (j > start && j <= limit && safe(s, j)) return j;
            }
        }
        return -1;
    }

    private static int lastWhitespace(String s, int start, int limit) {
        for (int i = limit; i > start; i--) {
            if (Character.isWhitespace(s.charAt(i - 1)) && safe(s, i)) return i;
        }
        return -1;
    }

    private static int safeHardCut(String s, int start, int limit) {
        for (int i = limit; i > start; i--) if (safe(s, i)) return i;
        // A single grapheme longer than the limit (pathological combining run): extend forward.
        for (int i = limit + 1; i < s.length(); i++) if (safe(s, i)) return i;
        return s.length();
    }

    /** True when cutting before index i keeps surrogate pairs and combining sequences intact. */
    static boolean safe(String s, int i) {
        if (i <= 0 || i >= s.length()) return true;
        if (Character.isHighSurrogate(s.charAt(i - 1)) && Character.isLowSurrogate(s.charAt(i))) return false;
        int cp = s.codePointAt(i);
        int type = Character.getType(cp);
        return type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
                && type != Character.ENCLOSING_MARK && cp != 0x200D && !(cp >= 0xFE00 && cp <= 0xFE0F);
    }

    private static boolean isSentenceEnd(char c) { return c == '.' || c == '!' || c == '?' || c == '…' || c == '\n' || c == '。'; }
    private static boolean isClauseEnd(char c) { return c == ',' || c == ';' || c == ':' || c == '—' || c == '–' || c == ')' || c == '"' || c == '”'; }

    /** True when a segment has something pronounceable; blank segments keep their text but skip synthesis. */
    public static boolean speakable(String segment) {
        for (int i = 0; i < segment.length(); i++) if (Character.isLetterOrDigit(segment.charAt(i))) return true;
        return false;
    }
}
