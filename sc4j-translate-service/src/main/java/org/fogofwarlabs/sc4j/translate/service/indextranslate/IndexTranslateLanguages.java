package org.fogofwarlabs.sc4j.translate.service.indextranslate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Maps the languages of Index-Translate to locales, to the FLORES-200 codes of the fastText language identifier and to the Chinese
 * names the prompts use. The code of a language is its first FLORES-200 code; the other codes are variants the identifier gives, such as
 * the regional varieties of Arabic or traditional Chinese, which the model translates as the language.
 */
final class IndexTranslateLanguages {
    /**
     * One language: its locale, its FLORES-200 codes, the first one its code, and its Chinese name.
     */
    private static final class Language {
        private final @NotNull Locale locale;
        private final @NotNull List<@NotNull String> codes;
        private final @NotNull String name;

        private Language(@NotNull String languageTag, @NotNull String name, @NotNull String @NotNull ... codes) {
            this.locale = Locale.forLanguageTag(languageTag);
            this.codes  = List.of(codes);
            this.name   = name;
        }
    }

    private static final @NotNull List<@NotNull Language> LANGUAGES = List.of(
            new Language("en", "英语", "eng_Latn"),
            new Language("zh", "中文", "zho_Hans", "zho_Hant"),
            new Language("de", "德语", "deu_Latn"),
            new Language("fr", "法语", "fra_Latn"),
            new Language("es", "西班牙语", "spa_Latn"),
            new Language("ja", "日语", "jpn_Jpan"),
            new Language("ko", "韩语", "kor_Hang"),
            new Language("pt", "葡萄牙语", "por_Latn"),
            new Language("ru", "俄语", "rus_Cyrl"),
            new Language("ar", "阿拉伯语", "arb_Arab",
                         "ars_Arab", "ary_Arab", "arz_Arab", "acm_Arab", "acq_Arab", "aeb_Arab", "ajp_Arab", "apc_Arab"),
            new Language("it", "意大利语", "ita_Latn"),
            new Language("nl", "荷兰语", "nld_Latn"),
            new Language("pl", "波兰语", "pol_Latn"),
            new Language("ro", "罗马尼亚语", "ron_Latn"),
            new Language("sv", "瑞典语", "swe_Latn"),
            new Language("tr", "土耳其语", "tur_Latn"),
            new Language("hi", "印地语", "hin_Deva"),
            new Language("vi", "越南语", "vie_Latn"),
            new Language("th", "泰语", "tha_Thai"),
            new Language("id", "印尼语", "ind_Latn"),
            new Language("ms", "马来语", "zsm_Latn"),
            new Language("fil", "菲律宾语", "tgl_Latn"),
            new Language("uk", "乌克兰语", "ukr_Cyrl"),
            new Language("fa", "波斯语", "pes_Arab", "prs_Arab"),
            new Language("cs", "捷克语", "ces_Latn"),
            new Language("el", "希腊语", "ell_Grek"),
            new Language("da", "丹麦语", "dan_Latn"),
            new Language("hu", "匈牙利语", "hun_Latn"),
            new Language("fi", "芬兰语", "fin_Latn"),
            new Language("nb", "书面挪威语", "nob_Latn"),
            new Language("sk", "斯洛伐克语", "slk_Latn"),
            new Language("bg", "保加利亚语", "bul_Cyrl")
    );

    /**
     * The language subtags that name a language of {@link #LANGUAGES} under another code.
     */
    private static final @NotNull Map<@NotNull String, @NotNull String> LANGUAGE_ALIASES = Map.of("no", "nb", "tl", "fil", "in", "id");

    private static final @NotNull List<@NotNull Locale> LOCALES;
    private static final @NotNull Map<@NotNull String, @NotNull Language> BY_LANGUAGE;
    private static final @NotNull Map<@NotNull String, @NotNull Language> BY_CODE;

    static {
        List<Locale> locales = new ArrayList<>(LANGUAGES.size());
        Map<String, Language> byLanguage = new HashMap<>();
        Map<String, Language> byCode = new HashMap<>();

        for (Language language : LANGUAGES) {
            locales.add(language.locale);
            byLanguage.put(language.locale.getLanguage(), language);

            for (String code : language.codes) {
                byCode.put(code, language);
            }
        }

        LOCALES     = List.copyOf(locales);
        BY_LANGUAGE = Map.copyOf(byLanguage);
        BY_CODE     = Map.copyOf(byCode);
    }

    /**
     * @return the locales of the languages; unmodifiable
     */
    static @NotNull List<@NotNull Locale> getLocales() {
        return LOCALES;
    }

    /**
     * Finds the code of a locale; its language decides, every script and country of a language has the same code.
     *
     * @param locale the locale
     * @return the FLORES-200 code of the language, or {@code null} when the model does not have the language
     */
    static @Nullable String findCode(@NotNull Locale locale) {
        Language language = findLanguage(locale);

        return language == null ? null : language.codes.getFirst();
    }

    /**
     * Finds the locale of a FLORES-200 code, the code of a language or one of its variants.
     *
     * @param code the FLORES-200 code
     * @return the locale, one of {@link #getLocales()}, or {@code null} when the code is no language of the model
     */
    static @Nullable Locale findLocale(@NotNull String code) {
        Language language = BY_CODE.get(code);

        return language == null ? null : language.locale;
    }

    /**
     * Finds the Chinese name of a locale's language, as the prompts name it.
     *
     * @param locale the locale
     * @return the name, such as {@code 英语}, or {@code null} when the model does not have the language
     */
    static @Nullable String findName(@NotNull Locale locale) {
        Language language = findLanguage(locale);

        return language == null ? null : language.name;
    }

    private static @Nullable Language findLanguage(@NotNull Locale locale) {
        String subtag = locale.getLanguage().toLowerCase(Locale.ROOT);

        return BY_LANGUAGE.get(LANGUAGE_ALIASES.getOrDefault(subtag, subtag));
    }

    private IndexTranslateLanguages() {
    }
}
