package org.fogofwarlabs.sc4j.translate.service.indextranslate;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.service.llama.LlamaTextGeneratorFactory;
import org.fogofwarlabs.sc4j.translate.service.llama.TextGeneratorLoader;
import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.fogofwarlabs.sc4j.translate.service.nllb.NllbPreset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Describes the Index-Translate-2B preset: the Q4_K_M GGUF of {@code IndexTeam/Index-Translate-2B-GGUF} at a pinned revision, run with
 * llama.cpp on the GPU or the CPU by the device option, and the lid218e fastText language identifier of the NLLB presets, which an
 * installation shares with them. It translates between the 32 languages of the model; its codes are the FLORES-200 codes of the identifier.
 */
public final class IndexTranslatePreset implements TranslationPreset {
    /**
     * The file name of the model.
     */
    public static final @NotNull String MODEL_FILE = "Index-Translate-2B.Q4_K_M.gguf";

    private static final @NotNull String ID = "index-translate-2b";
    private static final @NotNull String DISPLAY_NAME = "Index-Translate-2B";
    private static final @NotNull String LICENSE_NAME = "Apache-2.0";
    private static final @NotNull URI LICENSE_ADDRESS = URI.create("https://www.apache.org/licenses/LICENSE-2.0");
    private static final @NotNull String MODEL_REVISION =
            "https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/3a0eca6ce931d88d00614d427ab3ed8ff0743902/";
    private static final @NotNull PresetFile MODEL = new PresetFile(MODEL_FILE, URI.create(MODEL_REVISION + MODEL_FILE), 1_312_164_352L,
                                                                    "044b313d29342bd3b2c77cbb64023ca0d209bd9b3247763f9d162767ef2d746a");

    /**
     * The Index-Translate-2B preset.
     */
    public static final @NotNull IndexTranslatePreset INDEX_TRANSLATE_2B = new IndexTranslatePreset();

    private final @NotNull List<@NotNull PresetFile> files;

    private IndexTranslatePreset() {
        this.files = List.of(MODEL, NllbPreset.IDENTIFIER);
    }

    /**
     * @return the id of the preset, also the name of its folder, such as {@code nllb-200-distilled-600m}
     */
    @Override
    public @NotNull String getId() {
        return ID;
    }

    /**
     * @return the name shown to the user
     */
    @Override
    public @NotNull String getDisplayName() {
        return DISPLAY_NAME;
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
        return IndexTranslateLanguages.getLocales();
    }

    /**
     * Finds the preset's code of a language.
     *
     * @param language the language; every script and country of a language has the same code
     * @return the preset's code, such as {@code kor_Hang}, or {@code null} when the preset does not support the language
     */
    @Override
    public @Nullable String findCode(@NotNull Locale language) {
        return IndexTranslateLanguages.findCode(language);
    }

    /**
     * Finds the language of one of the preset's codes, such as a code its {@link LanguageIdentifier} gives.
     *
     * @param code the preset's code, or a FLORES-200 code of a variant of one of its languages
     * @return the language, one of {@link #getSupportedLanguages()}, or {@code null} when the code is not one of the preset's
     */
    @Override
    public @Nullable Locale findLanguage(@NotNull String code) {
        return IndexTranslateLanguages.findLocale(code);
    }

    /**
     * Loads the translation model from the installed files.
     * <p>
     * The model runs on the GPU when the device of the options is {@link TranslationDevice#AUTOMATIC} and the machine has one, else on the
     * CPU, also when the GPU fails.
     *
     * @param folder  the folder holding the preset's files
     * @param options the options of the model, such as its device; the preset applies those its model supports
     * @return the loaded translation model, which the caller closes
     * @throws ModelException if a file is missing or cannot be loaded
     */
    @Override
    public @NotNull TranslationModel createTranslationModel(@NotNull Path folder, @NotNull TranslationModelOptions options) throws ModelException {
        TextGeneratorLoader loader = new TextGeneratorLoader(LlamaTextGeneratorFactory.INSTANCE);

        return new IndexTranslationModel(loader.load(folder.resolve(MODEL_FILE), options.getDevice()));
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
            return NllbPreset.openLanguageIdentifier(folder.resolve(NllbPreset.IDENTIFIER_FILE));
        } catch (IOException exception) {
            throw new ModelException(Exceptions.format("Cannot read the language identifier of {0}: {1}", ID, exception.getMessage()), exception);
        }
    }
}
