package com.anisub.runtime.translate;

/**
 * Plain spoken text from a subtitle cue: ASS/SSA override blocks ({\an8}, {\i1}...), ASS drawing
 * commands ({\p1}m 0 0 l ...{\p0}), HTML-style tags (<i>, <font ...>), ASS line breaks (\N, \n)
 * and hard spaces (\h) and real line breaks are removed or turned into single spaces. Used before
 * translating and before speaking in AI mode. Pure Java; never logs.
 */
public final class TextCleaner {
    private TextCleaner() { }

    public static String clean(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        boolean drawing = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '{') {
                int close = text.indexOf('}', i + 1);
                if (close > i) {
                    String block = text.substring(i + 1, close);
                    int p = block.indexOf("\\p");
                    if (p >= 0 && p + 2 < block.length() && Character.isDigit(block.charAt(p + 2))) drawing = block.charAt(p + 2) != '0';
                    i = close;
                    continue;
                }
            }
            if (drawing) continue;
            if (c == '<') {
                int close = text.indexOf('>', i + 1);
                if (close > i && close - i <= 64 && looksLikeTag(text, i + 1, close)) { i = close; continue; }
            }
            if (c == '\\' && i + 1 < n) {
                char d = text.charAt(i + 1);
                if (d == 'N' || d == 'n' || d == 'h') { out.append(' '); i++; continue; }
            }
            if (c == '\r' || c == '\n' || c == '\t' || c == ' ') { out.append(' '); continue; }
            out.append(c);
        }
        return collapse(out);
    }

    private static boolean looksLikeTag(String s, int from, int to) {
        if (from >= to) return false;
        int i = from;
        if (s.charAt(i) == '/') i++;
        if (i >= to || !Character.isLetter(s.charAt(i))) return false;
        for (int k = i; k < to; k++) { char c = s.charAt(k); if (c == '<') return false; }
        return true;
    }

    private static String collapse(CharSequence s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { space = out.length() > 0; continue; }
            if (space) { out.append(' '); space = false; }
            out.append(c);
        }
        return out.toString();
    }
}
