package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Describes a downloadable set of models: its id, name, license, files and languages, and creates its models from the folder its files are
 * installed in.
 */
public interface TranslationPreset {
    /**
     * @return the id of the preset, also the name of its folder, such as {@code nllb-200-distilled-600m}
     */
    @NotNull String getId();

    /**
     * @return the name shown to the user
     */
    @NotNull String getDisplayName();

    /**
     * @return the name of the license of the model files, shown before the download
     */
    @NotNull String getLicenseName();

    /**
     * @return the address of the license text
     */
    @NotNull URI getLicenseAddress();

    /**
     * @return the files of the preset; unmodifiable
     */
    @NotNull List<@NotNull PresetFile> getFiles();

    /**
     * @return the languages the preset translates between, one locale per preset language; unmodifiable
     */
    @NotNull List<@NotNull Locale> getSupportedLanguages();

    /**
     * Finds the preset's code of a language.
     *
     * @param language the language; its script and country choose between the preset's variants of the language
     * @return the preset's code, such as {@code kor_Hang}, or {@code null} when the preset does not support the language
     */
    @Nullable String findCode(@NotNull Locale language);

    /**
     * Finds the language of one of the preset's codes, such as a code its {@link LanguageIdentifier} gives.
     *
     * @param code the preset's code
     * @return the language, one of {@link #getSupportedLanguages()}, or {@code null} when the code is not one of the preset's
     */
    @Nullable Locale findLanguage(@NotNull String code);

    /**
     * Loads the translation model from the installed files.
     *
     * @param folder  the folder holding the preset's files
     * @param options the options of the model, such as its device; the preset applies those its model supports
     * @return the loaded translation model, which the caller closes
     * @throws ModelException if a file is missing or cannot be loaded
     */
    @NotNull TranslationModel createTranslationModel(@NotNull Path folder, @NotNull TranslationModelOptions options) throws ModelException;

    /**
     * Loads the language identifier from the installed files.
     *
     * @param folder the folder holding the preset's files
     * @return the loaded language identifier, which the caller closes, or {@code null} when the preset has none
     * @throws ModelException if a file is missing or cannot be loaded
     */
    @Nullable LanguageIdentifier createLanguageIdentifier(@NotNull Path folder) throws ModelException;
}
