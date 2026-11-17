package org.fogofwarlabs.sc4j.launcher.install;

import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Records where an installed mod came from: its jar in {@code mods/}, the repository and the release tag it was installed from.
 */
public final class ModSource {
    private final @NotNull String jarName;
    private final @NotNull ReleaseRepository repository;
    private final @NotNull String release;

    /**
     * Creates a {@link ModSource} instance.
     *
     * @param jarName    the file name of the mod jar in {@code mods/}, such as {@code my-mod.jar}
     * @param repository the repository the mod was installed from
     * @param release    the tag of the installed release, such as {@code v1.2.0}
     */
    public ModSource(@NotNull String jarName, @NotNull ReleaseRepository repository, @NotNull String release) {
        this.jarName    = jarName;
        this.repository = repository;
        this.release    = release;
    }

    /**
     * @return the file name of the mod jar in {@code mods/}, such as {@code my-mod.jar}
     */
    public @NotNull String getJarName() {
        return this.jarName;
    }

    /**
     * @return the repository the mod was installed from
     */
    public @NotNull ReleaseRepository getRepository() {
        return this.repository;
    }

    /**
     * @return the tag of the installed release, such as {@code v1.2.0}
     */
    public @NotNull String getRelease() {
        return this.release;
    }
}
