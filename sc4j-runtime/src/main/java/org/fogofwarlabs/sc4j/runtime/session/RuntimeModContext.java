package org.fogofwarlabs.sc4j.runtime.session;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.mod.ModContext;
import org.fogofwarlabs.sc4j.engine.mod.ModSettings;
import org.fogofwarlabs.sc4j.engine.mod.ModStorage;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.translate.Translator;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Gives a mod what the runtime session prepared for it.
 */
final class RuntimeModContext implements ModContext {
    private final @NotNull ModManifest manifest;
    private final @NotNull ModSettings settings;
    private final @NotNull ModStorage storage;
    private final @NotNull Path installDirectory;
    private final @NotNull List<@NotNull String> arguments;
    private final @NotNull Logger logger;
    private final @NotNull Translator translator;

    /**
     * Creates a {@link RuntimeModContext} instance.
     *
     * @param manifest         the validated manifest
     * @param settings         the validated settings
     * @param storage          the mod's storage
     * @param installDirectory the install folder
     * @param arguments        the arguments after {@code --}
     * @param logger           the logger of the mod
     * @param translator       the translator of the run
     */
    RuntimeModContext(@NotNull ModManifest manifest, @NotNull ModSettings settings, @NotNull ModStorage storage, @NotNull Path installDirectory,
                      @NotNull List<@NotNull String> arguments, @NotNull Logger logger, @NotNull Translator translator) {
        this.manifest         = manifest;
        this.settings         = settings;
        this.storage          = storage;
        this.installDirectory = installDirectory;
        this.arguments        = List.copyOf(arguments);
        this.logger           = logger;
        this.translator       = translator;
    }

    /**
     * @return the validated manifest of the mod
     */
    @Override
    public @NotNull ModManifest getManifest() {
        return this.manifest;
    }

    /**
     * @return the validated values of the pre-run settings the launcher saved
     */
    @Override
    public @NotNull ModSettings getSettings() {
        return this.settings;
    }

    /**
     * @return the mod's own storage, {@code <install>/userdata/mods/<modId>/} unless the command line names another folder
     */
    @Override
    public @NotNull ModStorage getStorage() {
        return this.storage;
    }

    /**
     * @return the SC4J install folder
     */
    @Override
    public @NotNull Path getInstallDirectory() {
        return this.installDirectory;
    }

    /**
     * @return the command line arguments after {@code --}, which only the mod interprets
     */
    @Override
    public @NotNull List<@NotNull String> getArguments() {
        return this.arguments;
    }

    /**
     * @return the logger of the mod, writing to the runtime's log
     */
    @Override
    public @NotNull Logger getLogger() {
        return this.logger;
    }

    /**
     * Returns the translator of the run: the launcher's translation model when the launcher started the run, else the model the command line
     * names, else a translator that answers every request with
     * {@link org.fogofwarlabs.sc4j.translate.TranslationStatus#UNAVAILABLE}. Translations are for display only.
     *
     * @return the translator of the run
     */
    @Override
    public @NotNull Translator getTranslator() {
        return this.translator;
    }
}
