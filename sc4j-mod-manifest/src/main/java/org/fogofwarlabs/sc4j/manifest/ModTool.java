package org.fogofwarlabs.sc4j.manifest;

import org.jetbrains.annotations.NotNull;

/**
 * Describes a command line tool of a mod, an entry of the manifest's {@code tools}: the tool id and the main class that runs it.
 */
public final class ModTool {
    private final @NotNull String id;
    private final @NotNull String main;

    /**
     * Creates a {@link ModTool} instance.
     *
     * @param id   the tool id: lower case letters, digits and single hyphens
     * @param main the binary name of the class whose {@code main} method runs the tool
     */
    public ModTool(@NotNull String id, @NotNull String main) {
        this.id   = id;
        this.main = main;
    }

    /**
     * @return the tool id ({@code id}): lower case letters, digits and single hyphens
     */
    public @NotNull String getId() {
        return this.id;
    }

    /**
     * @return the binary name of the class whose {@code main} method runs the tool ({@code main})
     */
    public @NotNull String getMain() {
        return this.main;
    }
}
