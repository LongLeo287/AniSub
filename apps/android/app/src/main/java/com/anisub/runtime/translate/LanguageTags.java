package com.anisub.runtime.translate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wire language tags (BCP-47, "und" allowed) and their ML Kit Translate codes. Pure Java.
 * The cue language on the wire is the SOURCE language; the voice language is "vi" or "en".
 */
public final class LanguageTags {
    public static final String UND = "und", VI = "vi", EN = "en";
    public static final int MAX_TAG = 35;
    /** Voice languages with an on-device voice pack. */
    public static final List<String> VOICE = Collections.unmodifiableList(Arrays.asList(VI, EN));
    /** ML Kit Translate 17.0.3 languages (TranslateLanguage codes). English is built in. */
    public static final Set<String> MLKIT = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "et", "fa", "fi",
            "fr", "ga", "gl", "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja", "ka", "kn", "ko", "lt",
            "lv", "mk", "mr", "ms", "mt", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw", "ta",
            "te", "th", "tl", "tr", "uk", "ur", "vi", "zh")));
    /**
     * Models offered in AniSub settings and reported in CAPABILITIES: the voice languages first, then
     * the subtitle languages a film/anime source is most likely to carry. Any other ML Kit language
     * still works once its model is installed (it is then reported too).
     */
    public static final List<String> OFFERED = Collections.unmodifiableList(Arrays.asList(
            VI, EN, "ja", "zh", "ko", "th", "id", "ms", "es", "fr", "de", "pt", "it", "ru", "ar", "hi"));

    private LanguageTags() { }

    /** A syntactically valid BCP-47-style tag (language + optional subtags) or "und". */
    public static boolean valid(String tag) {
        if (tag == null || tag.isEmpty() || tag.length() > MAX_TAG) return false;
        return tag.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,7}");
    }

    /**
     * The ML Kit code for a wire tag: primary subtag, lower case, legacy aliases mapped
     * ("iw" -> "he", "in" -> "id", "fil" -> "tl", "nb"/"nn" -> "no"); every Chinese tag -> "zh".
     * Returns "und" for "und" and for invalid input.
     */
    public static String base(String tag) {
        if (!valid(tag)) return UND;
        String primary = tag.split("-", 2)[0].toLowerCase(Locale.ROOT);
        switch (primary) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            case "fil": return "tl";
            case "nb": case "nn": return "no";
            case "cmn": case "yue": case "zho": case "chi": return "zh";
            case "jpn": return "ja";
            case "eng": return EN;
            case "vie": return VI;
            default: return primary;
        }
    }

    public static boolean translatable(String code) { return MLKIT.contains(code); }
    public static boolean voice(String code) { return VOICE.contains(code); }

    /**
     * ML Kit models a translation needs (English is built in): source and target, minus "en".
     * ML Kit pivots through English, so ja -> vi needs both the ja and the vi model.
     */
    public static List<String> modelsFor(String from, String to) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!EN.equals(from)) out.add(from);
        if (!EN.equals(to)) out.add(to);
        return Collections.unmodifiableList(Arrays.asList(out.toArray(new String[0])));
    }

    /** Vietnamese display name for the settings screen (falls back to the code). */
    public static String displayName(String code) {
        switch (code) {
            case VI: return "Tiếng Việt";
            case EN: return "Tiếng Anh";
            case "ja": return "Tiếng Nhật";
            case "zh": return "Tiếng Trung";
            case "ko": return "Tiếng Hàn";
            case "th": return "Tiếng Thái";
            case "id": return "Tiếng Indonesia";
            case "ms": return "Tiếng Mã Lai";
            case "es": return "Tiếng Tây Ban Nha";
            case "fr": return "Tiếng Pháp";
            case "de": return "Tiếng Đức";
            case "pt": return "Tiếng Bồ Đào Nha";
            case "it": return "Tiếng Ý";
            case "ru": return "Tiếng Nga";
            case "ar": return "Tiếng Ả Rập";
            case "hi": return "Tiếng Hindi";
            default: return code;
        }
    }
}
