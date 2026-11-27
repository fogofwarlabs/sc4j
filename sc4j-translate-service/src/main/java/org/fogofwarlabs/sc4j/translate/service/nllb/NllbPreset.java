package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.service.ctranslate2.CTranslate2SequenceModel;
import org.fogofwarlabs.sc4j.translate.service.fasttext.FastTextLanguageIdentifier;
import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.fogofwarlabs.sc4j.translate.service.sentencepiece.SentencePieceTokenizer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Describes an NLLB-200 preset: the int8 CTranslate2 conversion of an NLLB-200 model from the {@code fogofwarlabs} organization, the
 * SentencePiece model of {@code facebook/nllb-200-distilled-600M}, which every NLLB-200 model shares, and the lid218e fastText language
 * identifier of {@code facebook/fasttext-language-identification}, each at a pinned revision. It translates between the 202 FLORES-200
 * languages of NLLB; the identifier's labels are the same codes.
 * <p>
 * The CTranslate2 files lie directly in the preset folder, which is the model folder of {@link CTranslate2SequenceModel}.
 */
public final class NllbPreset implements TranslationPreset {
    /**
     * The file name of the CTranslate2 model weights.
     */
    public static final @NotNull String MODEL_FILE = "model.bin";

    /**
     * The file name of the CTranslate2 model configuration.
     */
    public static final @NotNull String CONFIG_FILE = "config.json";

    /**
     * The file name of the CTranslate2 vocabulary.
     */
    public static final @NotNull String VOCABULARY_FILE = "shared_vocabulary.json";

    /**
     * The file name of the SentencePiece model.
     */
    public static final @NotNull String TOKENIZER_FILE = "sentencepiece.bpe.model";

    /**
     * The file name of the fastText language identifier.
     */
    public static final @NotNull String IDENTIFIER_FILE = "lid218e.bin";

    private static final @NotNull String LICENSE_NAME = "CC-BY-NC 4.0";
    private static final @NotNull URI LICENSE_ADDRESS = URI.create("https://creativecommons.org/licenses/by-nc/4.0/");
    private static final @NotNull String TOKENIZER_REVISION = "https://huggingface.co/facebook/nllb-200-distilled-600M/resolve/f8d333a098d19b4fd9a8b18f94170487ad3f821d/";
    private static final @NotNull String IDENTIFIER_REVISION =
            "https://huggingface.co/facebook/fasttext-language-identification/resolve/3af127d4124fc58b75666f3594bb5143b9757e78/";
    private static final @NotNull PresetFile TOKENIZER = new PresetFile(TOKENIZER_FILE, URI.create(TOKENIZER_REVISION + TOKENIZER_FILE), 4_852_054L,
                                                                        "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a");
    /**
     * The lid218e fastText language identifier, whose labels are the FLORES-200 codes; {@link #openLanguageIdentifier} reads it.
     */
    public static final @NotNull PresetFile IDENTIFIER = new PresetFile(IDENTIFIER_FILE, URI.create(IDENTIFIER_REVISION + "model.bin"),
                                                                        1_176_355_829L,
                                                                        "8ded5749a2ad79ae9ab7c9190c7c8b97ff20d54ad8b9527ffa50107238fc7f6a");
    private static final @NotNull String DISTILLED_600M_REVISION =
            "https://huggingface.co/fogofwarlabs/nllb-200-distilled-600M-ct2-int8/resolve/6790bbb31d215f6cbdc3155b06d90c4b22d90716/";
    private static final @NotNull String DISTILLED_1_3B_REVISION =
            "https://huggingface.co/fogofwarlabs/nllb-200-distilled-1.3B-ct2-int8/resolve/dd671f31f2893507bccd0b2a829fc8fa610f0cde/";

    /**
     * The NLLB-200 distilled 600M preset.
     */
    public static final @NotNull NllbPreset DISTILLED_600M = new NllbPreset(
            "nllb-200-distilled-600m", "NLLB-200 distilled 600M",
            new PresetFile(MODEL_FILE, URI.create(DISTILLED_600M_REVISION + MODEL_FILE), 622_596_105L,
                           "398726640cc2a02cc6a35277fa3cf2159ce8a1a66b48aa1b6c8837a47e3dd00c"),
            new PresetFile(CONFIG_FILE, URI.create(DISTILLED_600M_REVISION + CONFIG_FILE), 233L,
                           "72901fbd8abd89fb5cf4a388f26fc681f5c4c58a1e1a88b30b879f107270e7ee"),
            new PresetFile(VOCABULARY_FILE, URI.create(DISTILLED_600M_REVISION + VOCABULARY_FILE), 6_177_383L,
                           "768aa4170693765cb4c62fc485ea4fd954cd3e5a44bc645d11d83f41f2393776"));

    /**
     * The NLLB-200 distilled 1.3B preset.
     */
    public static final @NotNull NllbPreset DISTILLED_1_3B = new NllbPreset(
            "nllb-200-distilled-1.3b", "NLLB-200 distilled 1.3B",
            new PresetFile(MODEL_FILE, URI.create(DISTILLED_1_3B_REVISION + MODEL_FILE), 1_381_827_201L,
                           "d02c1ceb6d79dcd3ac06583d4f84060a49d248939ad6f7b00d60853b09c18702"),
            new PresetFile(CONFIG_FILE, URI.create(DISTILLED_1_3B_REVISION + CONFIG_FILE), 233L,
                           "72901fbd8abd89fb5cf4a388f26fc681f5c4c58a1e1a88b30b879f107270e7ee"),
            new PresetFile(VOCABULARY_FILE, URI.create(DISTILLED_1_3B_REVISION + VOCABULARY_FILE), 6_177_383L,
                           "768aa4170693765cb4c62fc485ea4fd954cd3e5a44bc645d11d83f41f2393776"));

    /**
     * Opens the lid218e fastText language identifier with the script rules of the NLLB languages: a text whose letters are mostly of a
     * script that one language owns takes that language, other texts the model's most probable label. Its codes are FLORES-200 codes.
     *
     * @param file the lid218e file
     * @return the opened language identifier, which the caller closes
     * @throws IOException if the file cannot be read or is no fastText model
     */
    public static @NotNull LanguageIdentifier openLanguageIdentifier(@NotNull Path file) throws IOException {
        return new NllbLanguageIdentifier(FastTextLanguageIdentifier.open(file));
    }


    private final @NotNull String id;
    private final @NotNull String displayName;
    private final @NotNull List<@NotNull PresetFile> files;

    /**
     * Creates a {@link NllbPreset} instance.
     *
     * @param id          the id of the preset
     * @param displayName the name shown to the user
     * @param model       the CTranslate2 model weights
     * @param config      the CTranslate2 model configuration
     * @param vocabulary  the CTranslate2 vocabulary
     */
    private NllbPreset(@NotNull String id, @NotNull String displayName, @NotNull PresetFile model, @NotNull PresetFile config,
                       @NotNull PresetFile vocabulary) {
        this.id          = id;
        this.displayName = displayName;
        this.files       = List.of(model, config, vocabulary, TOKENIZER, IDENTIFIER);
    }

    /**
     * @return the id of the preset, also the name of its folder, such as {@code nllb-200-distilled-600m}
     */
    @Override
    public @NotNull String getId() {
        return this.id;
    }

    /**
     * @return the name shown to the user
     */
    @Override
    public @NotNull String getDisplayName() {
        return this.displayName;
    }

    /**
     * @return the name of the license of the model files, shown before the download
     */
    @Override
    public @NotNull String getLicenseName() {
        return LICENSE_NAME;
    }

    /**
     * @return the address of the license text
     */
    @Override
    public @NotNull URI getLicenseAddress() {
        return LICENSE_ADDRESS;
    }

    /**
     * @return the files of the preset; unmodifiable
     */
    @Override
    public @NotNull List<@NotNull PresetFile> getFiles() {
        return this.files;
    }

    /**
     * @return the languages the preset translates between, one locale per preset language; unmodifiable
     */
    @Override
    public @NotNull List<@NotNull Locale> getSupportedLanguages() {
        return NllbLanguages.getLocales();
    }

    /**
     * Finds the preset's code of a language.
     *
     * @param language the language; its script and country choose between the preset's variants of the language
     * @return the preset's code, such as {@code kor_Hang}, or {@code null} when the preset does not support the language
     */
    @Override
    public @Nullable String findCode(@NotNull Locale language) {
        return NllbLanguages.findCode(language);
    }

    /**
     * Finds the language of one of the preset's codes, such as a code its {@link LanguageIdentifier} gives.
     *
     * @param code the preset's code
     * @return the language, one of {@link #getSupportedLanguages()}, or {@code null} when the code is not one of the preset's
     */
    @Override
    public @Nullable Locale findLanguage(@NotNull String code) {
        int index = NllbLanguages.findIndex(code);

        return index < 0 ? null : NllbLanguages.getLocales().get(index);
    }

    /**
     * Loads the translation model from the installed files.
     * <p>
     * The model runs on the CPU, whatever the device of the options.
     *
     * @param folder  the folder holding the preset's files
     * @param options the options of the model, such as its device; the preset applies those its model supports
     * @return the loaded translation model, which the caller closes
     * @throws ModelException if a file is missing or cannot be loaded
     */
    @Override
    public @NotNull TranslationModel createTranslationModel(@NotNull Path folder, @NotNull TranslationModelOptions options) throws ModelException {
        SentencePieceTokenizer tokenizer;

        try {
            tokenizer = SentencePieceTokenizer.read(folder.resolve(TOKENIZER_FILE));
        } catch (IOException exception) {
            throw new ModelException(Exceptions.format("Cannot read the tokenizer of {0}: {1}", this.id, exception.getMessage()), exception);
        }

        return new NllbTranslationModel(tokenizer, CTranslate2SequenceModel.open(folder));
    }

    /**
     * Loads the language identifier from the installed files.
     *
     * @param folder the folder holding the preset's files
     * @return the loaded language identifier, which the caller closes, or {@code null} when the preset has none
     * @throws ModelException if a file is missing or cannot be loaded
     */
    @Override
    public @Nullable LanguageIdentifier createLanguageIdentifier(@NotNull Path folder) throws ModelException {
        try {
            return openLanguageIdentifier(folder.resolve(IDENTIFIER_FILE));
        } catch (IOException exception) {
            throw new ModelException(Exceptions.format("Cannot read the language identifier of {0}: {1}", this.id, exception.getMessage()), exception);
        }
    }
}
