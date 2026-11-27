package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Maps the FLORES-200 codes of the NLLB languages to locales and back. The codes are in the order of the NLLB language tokens: the token of
 * the code at index {@code i} is the vocabulary size plus the fairseq offset plus {@code i}.
 * <p>
 * The locale of a code has the two-letter ISO 639-1 language where one exists (individual languages such as {@code arb} or {@code pes}
 * take the code of their macrolanguage), else the three-letter code, and the script of the code when the language appears with more than
 * one script.
 */
final class NllbLanguages {
    /**
     * The FLORES-200 codes, in the order of their language tokens.
     */
    static final @NotNull List<@NotNull String> CODES = List.of(
            "ace_Arab", "ace_Latn", "acm_Arab", "acq_Arab", "aeb_Arab", "afr_Latn", "ajp_Arab", "aka_Latn", "amh_Ethi", "apc_Arab",
            "arb_Arab", "ars_Arab", "ary_Arab", "arz_Arab", "asm_Beng", "ast_Latn", "awa_Deva", "ayr_Latn", "azb_Arab", "azj_Latn",
            "bak_Cyrl", "bam_Latn", "ban_Latn", "bel_Cyrl", "bem_Latn", "ben_Beng", "bho_Deva", "bjn_Arab", "bjn_Latn", "bod_Tibt",
            "bos_Latn", "bug_Latn", "bul_Cyrl", "cat_Latn", "ceb_Latn", "ces_Latn", "cjk_Latn", "ckb_Arab", "crh_Latn", "cym_Latn",
            "dan_Latn", "deu_Latn", "dik_Latn", "dyu_Latn", "dzo_Tibt", "ell_Grek", "eng_Latn", "epo_Latn", "est_Latn", "eus_Latn",
            "ewe_Latn", "fao_Latn", "pes_Arab", "fij_Latn", "fin_Latn", "fon_Latn", "fra_Latn", "fur_Latn", "fuv_Latn", "gla_Latn",
            "gle_Latn", "glg_Latn", "grn_Latn", "guj_Gujr", "hat_Latn", "hau_Latn", "heb_Hebr", "hin_Deva", "hne_Deva", "hrv_Latn",
            "hun_Latn", "hye_Armn", "ibo_Latn", "ilo_Latn", "ind_Latn", "isl_Latn", "ita_Latn", "jav_Latn", "jpn_Jpan", "kab_Latn",
            "kac_Latn", "kam_Latn", "kan_Knda", "kas_Arab", "kas_Deva", "kat_Geor", "knc_Arab", "knc_Latn", "kaz_Cyrl", "kbp_Latn",
            "kea_Latn", "khm_Khmr", "kik_Latn", "kin_Latn", "kir_Cyrl", "kmb_Latn", "kon_Latn", "kor_Hang", "kmr_Latn", "lao_Laoo",
            "lvs_Latn", "lij_Latn", "lim_Latn", "lin_Latn", "lit_Latn", "lmo_Latn", "ltg_Latn", "ltz_Latn", "lua_Latn", "lug_Latn",
            "luo_Latn", "lus_Latn", "mag_Deva", "mai_Deva", "mal_Mlym", "mar_Deva", "min_Latn", "mkd_Cyrl", "plt_Latn", "mlt_Latn",
            "mni_Beng", "khk_Cyrl", "mos_Latn", "mri_Latn", "zsm_Latn", "mya_Mymr", "nld_Latn", "nno_Latn", "nob_Latn", "npi_Deva",
            "nso_Latn", "nus_Latn", "nya_Latn", "oci_Latn", "gaz_Latn", "ory_Orya", "pag_Latn", "pan_Guru", "pap_Latn", "pol_Latn",
            "por_Latn", "prs_Arab", "pbt_Arab", "quy_Latn", "ron_Latn", "run_Latn", "rus_Cyrl", "sag_Latn", "san_Deva", "sat_Beng",
            "scn_Latn", "shn_Mymr", "sin_Sinh", "slk_Latn", "slv_Latn", "smo_Latn", "sna_Latn", "snd_Arab", "som_Latn", "sot_Latn",
            "spa_Latn", "als_Latn", "srd_Latn", "srp_Cyrl", "ssw_Latn", "sun_Latn", "swe_Latn", "swh_Latn", "szl_Latn", "tam_Taml",
            "tat_Cyrl", "tel_Telu", "tgk_Cyrl", "tgl_Latn", "tha_Thai", "tir_Ethi", "taq_Latn", "taq_Tfng", "tpi_Latn", "tsn_Latn",
            "tso_Latn", "tuk_Latn", "tum_Latn", "tur_Latn", "twi_Latn", "tzm_Tfng", "uig_Arab", "ukr_Cyrl", "umb_Latn", "urd_Arab",
            "uzn_Latn", "vec_Latn", "vie_Latn", "war_Latn", "wol_Latn", "xho_Latn", "ydd_Hebr", "yor_Latn", "yue_Hant", "zho_Hans",
            "zho_Hant", "zul_Latn"
    );

    /**
     * The languages whose code names an individual language of a macrolanguage, or a three-letter code with a two-letter one that Java
     * does not map, with the two-letter code they take.
     */
    private static final @NotNull Map<@NotNull String, @NotNull String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("arb", "ar"), Map.entry("pes", "fa"), Map.entry("zsm", "ms"), Map.entry("lvs", "lv"), Map.entry("khk", "mn"),
            Map.entry("swh", "sw"), Map.entry("npi", "ne"), Map.entry("ory", "or"), Map.entry("plt", "mg"), Map.entry("uzn", "uz"),
            Map.entry("azj", "az"), Map.entry("gaz", "om"), Map.entry("als", "sq"), Map.entry("pbt", "ps"), Map.entry("quy", "qu"),
            Map.entry("ydd", "yi"), Map.entry("kmr", "ku"), Map.entry("knc", "kr"), Map.entry("ayr", "ay"), Map.entry("fuv", "ff"),
            Map.entry("fil", "tl"), Map.entry("no", "nb")
    );

    private static final char CODE_SEPARATOR = '_';
    private static final int TWO_LETTER_LENGTH = 2;
    private static final @NotNull String LATIN_SCRIPT = "Latn";
    private static final @NotNull String CHINESE = "zh";
    private static final @NotNull String TRADITIONAL_HAN_SCRIPT = "Hant";
    private static final @NotNull Set<@NotNull String> TRADITIONAL_CHINESE_COUNTRIES = Set.of("TW", "HK", "MO");

    /**
     * The two-letter codes of the three-letter ISO 639-2 codes Java knows.
     */
    private static final @NotNull Map<@NotNull String, @NotNull String> TWO_LETTER_CODES;
    private static final @NotNull List<@NotNull Locale> LOCALES;
    private static final @NotNull Map<@NotNull String, @NotNull Integer> CODE_INDEXES;
    private static final @NotNull Map<@NotNull String, @NotNull List<@NotNull Integer>> LANGUAGE_INDEXES;

    static {
        Map<String, String> twoLetterCodes = new HashMap<>();

        for (String language : Locale.getISOLanguages()) {
            twoLetterCodes.putIfAbsent(Locale.of(language).getISO3Language(), language);
        }

        TWO_LETTER_CODES = Map.copyOf(twoLetterCodes);

        Map<String, List<Integer>> languageIndexes = new HashMap<>();
        Map<String, Integer> codeIndexes = new HashMap<>();

        for (int index = 0; index < CODES.size(); index++) {
            String code = CODES.get(index);

            languageIndexes.computeIfAbsent(toLanguage(code.substring(0, code.indexOf(CODE_SEPARATOR))), language -> new ArrayList<>()).add(index);
            codeIndexes.put(code, index);
        }

        List<Locale> locales = new ArrayList<>(CODES.size());

        for (String code : CODES) {
            int separator = code.indexOf(CODE_SEPARATOR);
            String language = toLanguage(code.substring(0, separator));
            Locale.Builder builder = new Locale.Builder().setLanguage(language);

            if (languageIndexes.get(language).size() > 1) {
                builder.setScript(code.substring(separator + 1));
            }

            locales.add(builder.build());
        }

        LOCALES          = List.copyOf(locales);
        CODE_INDEXES     = Map.copyOf(codeIndexes);
        LANGUAGE_INDEXES = Map.copyOf(languageIndexes);
    }

    /**
     * @return the locales of the codes, in the order of {@link #CODES}
     */
    static @NotNull List<@NotNull Locale> getLocales() {
        return LOCALES;
    }

    /**
     * Finds the index of a code.
     *
     * @param code a FLORES-200 code
     * @return the index in {@link #CODES}, or {@code -1} when it is not an NLLB code
     */
    static int findIndex(@NotNull String code) {
        return CODE_INDEXES.getOrDefault(code, -1);
    }

    /**
     * Finds the code of a locale: the language decides, the script chooses between the scripts of a language, else Chinese of Taiwan, Hong
     * Kong and Macao takes traditional Han and other languages the Latin script or their first script.
     *
     * @param locale the locale
     * @return the FLORES-200 code, or {@code null} when NLLB does not have the language
     */
    static @Nullable String findCode(@NotNull Locale locale) {
        List<Integer> indexes = LANGUAGE_INDEXES.get(toLanguage(locale.getLanguage()));

        if (indexes == null) {
            return null;
        }

        if (indexes.size() == 1) {
            return CODES.get(indexes.getFirst());
        }

        String script = locale.getScript();

        if (script.isEmpty() && toLanguage(locale.getLanguage()).equals(CHINESE) && TRADITIONAL_CHINESE_COUNTRIES.contains(locale.getCountry())) {
            script = TRADITIONAL_HAN_SCRIPT;
        }

        String fallback = CODES.get(indexes.getFirst());

        for (int index : indexes) {
            String codeScript = CODES.get(index).substring(CODES.get(index).indexOf(CODE_SEPARATOR) + 1);

            if (codeScript.equals(script)) {
                return CODES.get(index);
            }

            if (script.isEmpty() && codeScript.equals(LATIN_SCRIPT)) {
                fallback = CODES.get(index);
            }
        }

        return fallback;
    }

    /**
     * Returns the language subtag of a language code: aliases and three-letter codes with a two-letter one become the two-letter code.
     */
    private static @NotNull String toLanguage(@NotNull String language) {
        String lowerCase = language.toLowerCase(Locale.ROOT);
        String alias = LANGUAGE_ALIASES.get(lowerCase);

        if (alias != null) {
            return alias;
        }

        return lowerCase.length() == TWO_LETTER_LENGTH ? lowerCase : TWO_LETTER_CODES.getOrDefault(lowerCase, lowerCase);
    }

    private NllbLanguages() {
    }
}
