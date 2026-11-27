package org.fogofwarlabs.sc4j.translate.service;

import org.fogofwarlabs.sc4j.translate.service.model.TranslationPreset;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Resolves the folders of translation below the user data folder of an install:
 * {@snippet lang = "text":
 * <userdata>/translation/presets/<preset id>/   the installed files of each preset
 * <userdata>/translation/cache/                 the disk cache of translations
 *}
 */
public final class TranslationStorage {
    /**
     * The translation folder below the user data folder.
     */
    public static final @NotNull String TRANSLATION_FOLDER = "translation";

    private static final @NotNull String PRESETS_FOLDER = "presets";
    private static final @NotNull String CACHE_FOLDER = "cache";

    /**
     * Creates a {@link TranslationStorage} instance below a user data folder.
     *
     * @param userDataFolder the user data folder of the install
     * @return the created {@link TranslationStorage} instance
     */
    public static @NotNull TranslationStorage of(@NotNull Path userDataFolder) {
        return new TranslationStorage(userDataFolder.resolve(TRANSLATION_FOLDER));
    }


    private final @NotNull Path folder;

    private TranslationStorage(@NotNull Path folder) {
        this.folder = folder;
    }

    /**
     * @return the translation folder
     */
    public @NotNull Path getFolder() {
        return this.folder;
    }

    /**
     * Returns the folder of a preset's installed files.
     *
     * @param preset the preset
     * @return the folder named by the preset id
     */
    public @NotNull Path getPresetFolder(@NotNull TranslationPreset preset) {
        return this.folder.resolve(PRESETS_FOLDER).resolve(preset.getId());
    }

    /**
     * @return the folder of the disk cache of translations
     */
    public @NotNull Path getCacheFolder() {
        return this.folder.resolve(CACHE_FOLDER);
    }
}
