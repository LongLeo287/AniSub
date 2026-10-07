package com.anisub.runtime.translate;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LanguageTagsTest {
    @Test public void validTags() {
        for (String ok : new String[]{"vi", "en", "und", "ja", "zh-Hant", "pt-BR", "en-US", "fil", "zh-Hans-CN"}) assertTrue(ok, LanguageTags.valid(ok));
        for (String bad : new String[]{null, "", "v", "vietnamese", "en_US", "en-", "-en", "1a", "en-toolongsubtag", "e n"}) assertFalse(String.valueOf(bad), LanguageTags.valid(bad));
    }

    @Test public void baseMapsToMlKitCodes() {
        assertEquals("vi", LanguageTags.base("vi-VN"));
        assertEquals("en", LanguageTags.base("EN-us"));
        assertEquals("zh", LanguageTags.base("zh-Hant-TW"));
        assertEquals("zh", LanguageTags.base("cmn"));
        assertEquals("he", LanguageTags.base("iw"));
        assertEquals("id", LanguageTags.base("in"));
        assertEquals("tl", LanguageTags.base("fil"));
        assertEquals("no", LanguageTags.base("nb"));
        assertEquals("ja", LanguageTags.base("jpn"));
        assertEquals("und", LanguageTags.base("und"));
        assertEquals("und", LanguageTags.base("not a tag"));
    }

    @Test public void modelsNeededSkipBuiltInEnglish() {
        assertEquals(Arrays.asList("vi"), LanguageTags.modelsFor("en", "vi"));
        assertEquals(Arrays.asList("ja", "vi"), LanguageTags.modelsFor("ja", "vi"));
        assertEquals(Arrays.asList("vi"), LanguageTags.modelsFor("vi", "en"));
        assertEquals(Arrays.asList("ja"), LanguageTags.modelsFor("ja", "en"));
    }

    @Test public void priorityPairsAreTranslatableAndOffered() {
        for (String l : new String[]{"en", "vi", "ja"}) { assertTrue(LanguageTags.translatable(l)); assertTrue(LanguageTags.OFFERED.contains(l)); }
        assertEquals(59, LanguageTags.MLKIT.size());
        for (String l : LanguageTags.OFFERED) assertTrue(l, LanguageTags.translatable(l));
        assertFalse(LanguageTags.translatable("yi"));
    }

    @Test public void cleanerStripsAssHtmlAndBreaks() {
        assertEquals("Don't give up now, we're almost there.", TextCleaner.clean("{\\an8}Don't give up now,\\Nwe're almost there."));
        assertEquals("Hello world", TextCleaner.clean("<i>Hello</i>\r\n<font color=\"#fff\">world</font>"));
        assertEquals("a b", TextCleaner.clean("a\\hb"));
        assertEquals("Shape gone", TextCleaner.clean("{\\p1}m 0 0 l 100 0 100 100{\\p0}Shape gone"));
        assertEquals("1 < 2 and 3 > 2", TextCleaner.clean("1 < 2 and 3 > 2"));
        assertEquals("", TextCleaner.clean("  \n "));
        assertEquals("", TextCleaner.clean(null));
        assertEquals("{unclosed", TextCleaner.clean("{unclosed"));
    }
}
