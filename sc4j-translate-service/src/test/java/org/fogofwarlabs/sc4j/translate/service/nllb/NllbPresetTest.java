package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.fogofwarlabs.sc4j.translate.service.TranslationTestResources;
import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tests the NLLB preset: its language table and locale mapping, its file list, and, with the installed files, its translation model and
 * language identifier.
 */
final class NllbPresetTest {
    @Test
    void supported_languages_should_have_one_distinct_locale_per_code() {
        NllbPreset preset = NllbPreset.DISTILLED_600M;
        List<Locale> languages = preset.getSupportedLanguages();
        Set<Locale> distinct = new HashSet<>(languages);

        Assertions.assertEquals(NllbLanguages.CODES.size(), languages.size());
        Assertions.assertEquals(languages.size(), distinct.size());

        for (int index = 0; index < languages.size(); index++) {
            Assertions.assertEquals(NllbLanguages.CODES.get(index), preset.findCode(languages.get(index)), languages.get(index).toLanguageTag());
            Assertions.assertEquals(languages.get(index), preset.findLanguage(NllbLanguages.CODES.get(index)));
        }
    }

    @Test
    void find_code_should_map_common_locales() {
        NllbPreset preset = NllbPreset.DISTILLED_600M;

        Assertions.assertEquals("kor_Hang", preset.findCode(Locale.KOREAN));
        Assertions.assertEquals("kor_Hang", preset.findCode(Locale.KOREA));
        Assertions.assertEquals("eng_Latn", preset.findCode(Locale.US));
        Assertions.assertEquals("jpn_Jpan", preset.findCode(Locale.JAPANESE));
        Assertions.assertEquals("zho_Hans", preset.findCode(Locale.SIMPLIFIED_CHINESE));
        Assertions.assertEquals("zho_Hant", preset.findCode(Locale.TRADITIONAL_CHINESE));
        Assertions.assertEquals("zho_Hant", preset.findCode(Locale.forLanguageTag("zh-HK")));
        Assertions.assertEquals("zho_Hans", preset.findCode(Locale.forLanguageTag("zh-Hans-TW")));
        Assertions.assertEquals("spa_Latn", preset.findCode(Locale.forLanguageTag("es-MX")));
        Assertions.assertEquals("arb_Arab", preset.findCode(Locale.forLanguageTag("ar")));
        Assertions.assertEquals("pes_Arab", preset.findCode(Locale.forLanguageTag("fa")));
        Assertions.assertEquals("nob_Latn", preset.findCode(Locale.forLanguageTag("no")));
        Assertions.assertEquals("heb_Hebr", preset.findCode(Locale.forLanguageTag("iw")));
        Assertions.assertEquals("tgl_Latn", preset.findCode(Locale.forLanguageTag("fil")));
        Assertions.assertEquals("kor_Hang", preset.findCode(Locale.forLanguageTag("kor")));
        Assertions.assertEquals("ace_Latn", preset.findCode(Locale.forLanguageTag("ace")));
        Assertions.assertEquals("ace_Arab", preset.findCode(Locale.forLanguageTag("ace-Arab")));
        Assertions.assertNull(preset.findCode(Locale.forLanguageTag("tlh")));
        Assertions.assertNull(preset.findLanguage("xyz_Latn"));
    }

    @Test
    void files_should_have_distinct_names_sizes_and_checksums() {
        for (NllbPreset preset : List.of(NllbPreset.DISTILLED_600M, NllbPreset.DISTILLED_1_3B)) {
            Set<String> names = new HashSet<>();

            for (PresetFile file : preset.getFiles()) {
                Assertions.assertTrue(names.add(file.getName()), file.getName());
                Assertions.assertTrue(file.getSize() > 0, file.getName());
                Assertions.assertTrue(file.getSha256().matches("[0-9a-f]{64}"), file.getName());
                Assertions.assertTrue(file.getAddress().toString().startsWith("https://huggingface.co/"), file.getName());
            }

            Assertions.assertEquals(Set.of(NllbPreset.MODEL_FILE, NllbPreset.CONFIG_FILE, NllbPreset.VOCABULARY_FILE, NllbPreset.TOKENIZER_FILE,
                                           NllbPreset.IDENTIFIER_FILE), names, preset.getId());
        }
    }

    @Test
    void presets_should_have_distinct_ids_and_share_the_tokenizer_and_the_identifier() {
        NllbPreset small = NllbPreset.DISTILLED_600M;
        NllbPreset large = NllbPreset.DISTILLED_1_3B;

        Assertions.assertNotEquals(small.getId(), large.getId());
        Assertions.assertEquals(small.getFiles().subList(3, 5), large.getFiles().subList(3, 5));
        Assertions.assertNotEquals(small.getFiles().getFirst().getSha256(), large.getFiles().getFirst().getSha256());
    }

    @Test
    void installed_models_should_translate_and_identify() throws ModelException {
        NllbPreset preset = NllbPreset.DISTILLED_600M;
        Path folder = TranslationTestResources.requirePresetFolder(NllbPreset.DISTILLED_600M.getId());

        try (TranslationModel model = preset.createTranslationModel(folder); LanguageIdentifier identifier = preset.createLanguageIdentifier(folder)) {
            List<String> batch = model.translate(List.of(new TranslationInput("Let's play another game.", Locale.ENGLISH, Locale.KOREAN),
                                                         new TranslationInput("안녕하세요, 같이 게임 하실 분?", Locale.KOREAN, Locale.ENGLISH)));
            String korean = batch.get(0);
            String english = batch.get(1);

            Assertions.assertTrue(korean.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL),
                                  korean);
            Assertions.assertTrue(english.matches(".*[A-Za-z]{3}.*"), english);
            Assertions.assertNotNull(identifier);

            IdentifiedLanguage identified = identifier.identify(korean);

            Assertions.assertNotNull(identified);
            Assertions.assertEquals("kor_Hang", identified.getCode());
            Assertions.assertEquals(6, model.countTokens("Hello, world!"));
        }
    }
}
