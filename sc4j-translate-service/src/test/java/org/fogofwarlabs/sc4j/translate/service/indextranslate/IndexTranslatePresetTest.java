package org.fogofwarlabs.sc4j.translate.service.indextranslate;

import org.fogofwarlabs.sc4j.translate.TranslationContext;
import org.fogofwarlabs.sc4j.translate.service.TranslationPresetUtils;
import org.fogofwarlabs.sc4j.translate.service.TranslationTestResources;
import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.fogofwarlabs.sc4j.translate.service.nllb.NllbPreset;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Tests the Index-Translate preset: its languages and their codes, its files and the identifier it shares with the NLLB presets, and, with
 * the installed files, its translation model on the CPU and on the device the machine offers.
 */
final class IndexTranslatePresetTest {
    private static final int LANGUAGE_COUNT = 32;
    /**
     * A text of about 330 tokens of mixed sentences.
     */
    private static final @NotNull String LONG_TEXT = String.join(" ", List.of(
            "The library opens at nine in the morning and closes at six in the evening on weekdays.",
            "On Saturdays it stays open until noon, and on Sundays it is closed.",
            "Visitors can borrow up to five books at a time for three weeks.",
            "If a book is returned late, a small fee is charged for every day.",
            "The reading room on the second floor is quiet and has large windows facing the park.",
            "Children have their own room with picture books, puzzles and a corner for reading aloud.",
            "Every Wednesday afternoon a volunteer reads stories to the youngest visitors.",
            "Students often come in the evening to prepare for their exams together.",
            "The library also lends laptops, but they must stay inside the building.",
            "Printing costs ten cents per page in black and white and fifty cents in colour.",
            "A new collection of travel guides arrived last month, covering cities in Asia, Europe and South America.",
            "Many readers asked for more audiobooks, so the library now offers them online.",
            "To use the online service, you only need your library card number and a password.",
            "The café next to the entrance serves coffee, tea and fresh sandwiches until four o'clock.",
            "Food is not allowed in the reading rooms, but water bottles with a lid are fine.",
            "Lost items are kept at the front desk for one month before they are given away.",
            "Next spring the building will be renovated, and some rooms will be closed for a few weeks.",
            "During that time, books can still be picked up and returned at the side door.",
            "The staff thanks everyone for their patience and hopes to see you soon.",
            "If you have questions, please ask at the front desk or write to us by email."
    ));

    @Test
    void supported_languages_should_map_to_codes_and_back() {
        IndexTranslatePreset preset = IndexTranslatePreset.INDEX_TRANSLATE_2B;
        List<Locale> languages = preset.getSupportedLanguages();

        Assertions.assertEquals(LANGUAGE_COUNT, languages.size());
        Assertions.assertEquals(LANGUAGE_COUNT, new HashSet<>(languages).size());

        for (Locale language : languages) {
            String code = preset.findCode(language);

            Assertions.assertNotNull(code, language.toLanguageTag());
            Assertions.assertEquals(language, preset.findLanguage(code), code);
        }
    }

    @Test
    void find_code_and_find_language_should_map_variants_to_the_model_languages() {
        IndexTranslatePreset preset = IndexTranslatePreset.INDEX_TRANSLATE_2B;

        Assertions.assertEquals("kor_Hang", preset.findCode(Locale.KOREA));
        Assertions.assertEquals("zho_Hans", preset.findCode(Locale.TRADITIONAL_CHINESE));
        Assertions.assertEquals("nob_Latn", preset.findCode(Locale.forLanguageTag("no")));
        Assertions.assertEquals("tgl_Latn", preset.findCode(Locale.forLanguageTag("fil")));
        Assertions.assertEquals("tgl_Latn", preset.findCode(Locale.forLanguageTag("tl")));
        Assertions.assertEquals("ind_Latn", preset.findCode(Locale.forLanguageTag("id")));
        Assertions.assertNull(preset.findCode(Locale.forLanguageTag("sw")));
        Assertions.assertEquals(Locale.CHINESE, preset.findLanguage("zho_Hant"));
        Assertions.assertEquals(Locale.forLanguageTag("ar"), preset.findLanguage("arz_Arab"));
        Assertions.assertEquals(Locale.forLanguageTag("fa"), preset.findLanguage("prs_Arab"));
        Assertions.assertNull(preset.findLanguage("swh_Latn"));
    }

    @Test
    void files_should_be_the_model_and_the_identifier_of_the_nllb_presets() {
        IndexTranslatePreset preset = IndexTranslatePreset.INDEX_TRANSLATE_2B;
        PresetFile model = preset.getFiles().getFirst();

        Assertions.assertEquals(List.of(model, NllbPreset.IDENTIFIER), preset.getFiles());
        Assertions.assertEquals(IndexTranslatePreset.MODEL_FILE, model.getName());
        Assertions.assertTrue(model.getAddress().toString().startsWith("https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/"));
        Assertions.assertTrue(model.getSha256().matches("[0-9a-f]{64}"));
        Assertions.assertEquals("index-translate-2b", preset.getId());
        Assertions.assertSame(preset, TranslationPresetUtils.getPresets().getFirst());
    }

    @Test
    void installed_model_should_translate_on_the_cpu_and_on_the_automatic_device() throws ModelException {
        IndexTranslatePreset preset = IndexTranslatePreset.INDEX_TRANSLATE_2B;
        Path folder = TranslationTestResources.requirePresetFolder(preset.getId());
        TranslationContext context = new TranslationContext.Builder().setTextKind("聊天消息")
                                                                     .setDescription("Short chat messages between players of a strategy game.")
                                                                     .addGlossaryEntry("tower", Locale.KOREAN, "타워")
                                                                     .build();

        for (TranslationDevice device : List.of(TranslationDevice.CPU, TranslationDevice.AUTOMATIC)) {
            try (TranslationModel model = preset.createTranslationModel(folder, new TranslationModelOptions(device));
                 LanguageIdentifier identifier = preset.createLanguageIdentifier(folder)) {
                List<String> batch = model.translate(List.of(new TranslationInput("Let's play another game.", Locale.ENGLISH, Locale.KOREAN),
                                                             new TranslationInput("안녕하세요, 같이 게임 하실 분?", Locale.KOREAN, Locale.ENGLISH),
                                                             new TranslationInput("Build a tower here.", Locale.ENGLISH, Locale.KOREAN, context)));
                String longTranslation = model.translate(List.of(new TranslationInput(LONG_TEXT, Locale.ENGLISH, Locale.KOREAN))).getFirst();

                Assertions.assertTrue(model.countTokens(LONG_TEXT) <= model.getMaximumInputTokens());
                assertHangul(longTranslation);
                Assertions.assertTrue(longTranslation.length() > LONG_TEXT.length() / 4, longTranslation);

                assertHangul(batch.get(0));
                Assertions.assertTrue(batch.get(1).matches(".*[A-Za-z]{3}.*"), batch.get(1));
                assertHangul(batch.get(2));
                Assertions.assertTrue(batch.get(2).contains("타워"), batch.get(2));
                Assertions.assertNotNull(identifier);

                IdentifiedLanguage identified = identifier.identify(batch.get(0));

                Assertions.assertNotNull(identified);
                Assertions.assertEquals(Locale.KOREAN, preset.findLanguage(identified.getCode()));
            }
        }
    }

    private static void assertHangul(@NotNull String text) {
        Assertions.assertTrue(text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL), text);
    }
}
