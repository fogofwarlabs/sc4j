package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.fogofwarlabs.sc4j.translate.service.fasttext.FastTextLanguageIdentifier;
import org.fogofwarlabs.sc4j.translate.service.model.IdentifiedLanguage;
import org.fogofwarlabs.sc4j.translate.service.model.LanguageIdentifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.Character.UnicodeScript;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identifies the language of a text for the NLLB preset. A text whose letters are mostly of a script that one NLLB language owns takes
 * that language without the model: kana with Han letters is Japanese, Hangul is Korean, Han letters alone are Chinese (the model picks the
 * Chinese variant) and the other single-language scripts are their language. Every other text takes the model's most probable label.
 */
final class NllbLanguageIdentifier implements LanguageIdentifier {
    /**
     * The code of Japanese.
     */
    static final @NotNull String JAPANESE_CODE = "jpn_Jpan";
    /**
     * The code of Korean.
     */
    static final @NotNull String KOREAN_CODE = "kor_Hang";
    /**
     * The code of simplified Chinese, the Chinese variant when the model names none.
     */
    static final @NotNull String CHINESE_CODE = "zho_Hans";

    private static final float CERTAIN_PROBABILITY = 1.0F;
    private static final @NotNull Set<@NotNull String> CHINESE_CODES = Set.of(CHINESE_CODE, "zho_Hant", "yue_Hant");
    private static final @NotNull Map<@NotNull UnicodeScript, @NotNull String> SINGLE_LANGUAGE_SCRIPTS = createSingleLanguageScripts();

    private static @NotNull Map<@NotNull UnicodeScript, @NotNull String> createSingleLanguageScripts() {
        Map<UnicodeScript, String> scripts = new EnumMap<>(UnicodeScript.class);

        scripts.put(UnicodeScript.HANGUL, KOREAN_CODE);
        scripts.put(UnicodeScript.THAI, "tha_Thai");
        scripts.put(UnicodeScript.GREEK, "ell_Grek");
        scripts.put(UnicodeScript.GEORGIAN, "kat_Geor");
        scripts.put(UnicodeScript.ARMENIAN, "hye_Armn");
        scripts.put(UnicodeScript.KHMER, "khm_Khmr");
        scripts.put(UnicodeScript.LAO, "lao_Laoo");
        scripts.put(UnicodeScript.SINHALA, "sin_Sinh");
        scripts.put(UnicodeScript.TAMIL, "tam_Taml");
        scripts.put(UnicodeScript.TELUGU, "tel_Telu");
        scripts.put(UnicodeScript.KANNADA, "kan_Knda");
        scripts.put(UnicodeScript.MALAYALAM, "mal_Mlym");
        scripts.put(UnicodeScript.GUJARATI, "guj_Gujr");
        scripts.put(UnicodeScript.GURMUKHI, "pan_Guru");
        scripts.put(UnicodeScript.ORIYA, "ory_Orya");

        return scripts;
    }

    /**
     * Finds the language that the script of a text decides.
     *
     * @param text the text
     * @return the code of the language, {@link #CHINESE_CODE} for Han letters alone, or {@code null} when the script decides no language
     */
    static @Nullable String findScriptCode(@NotNull String text) {
        Map<UnicodeScript, Integer> counts = new EnumMap<>(UnicodeScript.class);
        int letters = 0;

        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);

            if (Character.isLetter(codePoint)) {
                letters++;
                counts.merge(UnicodeScript.of(codePoint), 1, Integer::sum);
            }

            index += Character.charCount(codePoint);
        }

        int kana = counts.getOrDefault(UnicodeScript.HIRAGANA, 0) + counts.getOrDefault(UnicodeScript.KATAKANA, 0);
        int han = counts.getOrDefault(UnicodeScript.HAN, 0);

        if (kana > 0 && isMajority(kana + han, letters)) {
            return JAPANESE_CODE;
        }

        if (kana == 0 && isMajority(han, letters)) {
            return CHINESE_CODE;
        }

        for (Map.Entry<UnicodeScript, String> script : SINGLE_LANGUAGE_SCRIPTS.entrySet()) {
            if (isMajority(counts.getOrDefault(script.getKey(), 0), letters)) {
                return script.getValue();
            }
        }

        return null;
    }

    private static boolean isMajority(int count, int letters) {
        return count * 2 > letters;
    }


    private final @NotNull FastTextLanguageIdentifier model;

    /**
     * Creates a {@link NllbLanguageIdentifier} instance.
     *
     * @param model the fastText model of the NLLB languages, closed with this identifier
     */
    NllbLanguageIdentifier(@NotNull FastTextLanguageIdentifier model) {
        this.model = model;
    }

    /**
     * Identifies the language of a text: the language its script decides, else the model's most probable label.
     *
     * @param text the text, on one line
     * @return the language, or {@code null} when neither the script nor the model gives one
     */
    @Override
    public @Nullable IdentifiedLanguage identify(@NotNull String text) {
        String scriptCode = findScriptCode(text);

        if (scriptCode == null) {
            return this.model.identify(text);
        }

        if (!scriptCode.equals(CHINESE_CODE)) {
            return new IdentifiedLanguage(scriptCode, CERTAIN_PROBABILITY);
        }

        List<IdentifiedLanguage> predictions = this.model.predict(text, this.model.getLabels().size());

        for (IdentifiedLanguage prediction : predictions) {
            if (CHINESE_CODES.contains(prediction.getCode())) {
                return new IdentifiedLanguage(prediction.getCode(), CERTAIN_PROBABILITY);
            }
        }

        return new IdentifiedLanguage(CHINESE_CODE, CERTAIN_PROBABILITY);
    }

    /**
     * Closes the model.
     */
    @Override
    public void close() {
        this.model.close();
    }
}
