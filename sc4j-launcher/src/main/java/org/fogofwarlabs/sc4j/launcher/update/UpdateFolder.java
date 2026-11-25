package org.fogofwarlabs.sc4j.launcher.update;

import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Resolves the folder of downloads and updates below the install, {@code <install>/.cache/update}:
 * {@snippet lang = "text":
 * <install>/.cache/update/
 *   SC4J-<version>-<platform>.zip  the downloaded user package, once its checksum matched
 *   package/                       the unpacked user package, which the updater runs from
 *   backup/                        the replaced programs while the updater runs
 *   mods/                          downloaded mod jars before they join mods/
 *   tmp/                           the updater's temporary files
 *   updater.log                    the updater's output
 *   failure.txt                    why the last update failed, until the launcher shows it
 *}
 * Nothing in it is needed once an update or a mod install has ended; the launcher removes the folder at its start.
 */
public final class UpdateFolder {
    private static final @NotNull String CACHE_FOLDER = ".cache";
    private static final @NotNull String UPDATE_FOLDER = "update";
    private static final @NotNull String PACKAGE_FOLDER = "package";
    private static final @NotNull String BACKUP_FOLDER = "backup";
    private static final @NotNull String MODS_FOLDER = "mods";
    private static final @NotNull String TEMPORARY_FOLDER = "tmp";
    private static final @NotNull String LOG_FILE = "updater.log";
    private static final @NotNull String FAILURE_FILE = "failure.txt";

    /**
     * Creates a {@link UpdateFolder} instance for an install.
     *
     * @param install the install
     * @return the created {@link UpdateFolder} instance
     */
    public static @NotNull UpdateFolder of(@NotNull InstallLayout install) {
        return new UpdateFolder(install.getRoot().resolve(CACHE_FOLDER).resolve(UPDATE_FOLDER));
    }


    private final @NotNull Path root;

    /**
     * Creates a {@link UpdateFolder} instance.
     *
     * @param root the folder {@code <install>/.cache/update}
     */
    private UpdateFolder(@NotNull Path root) {
        this.root = root;
    }

    /**
     * @return the folder {@code <install>/.cache/update}
     */
    public @NotNull Path getRoot() {
        return this.root;
    }

    /**
     * Returns a downloaded file of the folder.
     *
     * @param fileName the file name, such as {@code SC4J-1.2.0-linux-x64.zip}
     * @return {@code <install>/.cache/update/<fileName>}
     */
    public @NotNull Path getDownloadFile(@NotNull String fileName) {
        return this.root.resolve(fileName);
    }

    /**
     * @return {@code <install>/.cache/update/package}, the unpacked user package
     */
    public @NotNull Path getPackageDirectory() {
        return this.root.resolve(PACKAGE_FOLDER);
    }

    /**
     * @return {@code <install>/.cache/update/backup}, the replaced programs while the updater runs
     */
    public @NotNull Path getBackupDirectory() {
        return this.root.resolve(BACKUP_FOLDER);
    }

    /**
     * @return {@code <install>/.cache/update/mods}, downloaded mod jars before they join {@code mods/}
     */
    public @NotNull Path getModsDirectory() {
        return this.root.resolve(MODS_FOLDER);
    }

    /**
     * @return {@code <install>/.cache/update/tmp}, the updater's temporary files
     */
    public @NotNull Path getTemporaryDirectory() {
        return this.root.resolve(TEMPORARY_FOLDER);
    }

    /**
     * @return {@code <install>/.cache/update/updater.log}, the updater's output
     */
    public @NotNull Path getLogFile() {
        return this.root.resolve(LOG_FILE);
    }

    /**
     * @return {@code <install>/.cache/update/failure.txt}, why the last update failed
     */
    public @NotNull Path getFailureFile() {
        return this.root.resolve(FAILURE_FILE);
    }
}
