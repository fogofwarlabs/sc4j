package org.fogofwarlabs.sc4j.manifest.install;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;

/**
 * Resolves the folders of an SC4J install. Every file lives below the install folder; SC4J never writes to the operating
 * system's user folders:
 * {@snippet lang = "text":
 * <install>/
 *   SC4J.bat, SC4J                    the start of the launcher for users
 *   java/                             the Java runtime the user package bundles
 *   launcher/bin, launcher/lib        the launcher distribution
 *   launcher/seeds.json               the Beacon.Link seed list the install ships
 *   runtime/bin, runtime/lib          the runtime distribution
 *   mods/<mod>.jar                    the mod jars
 *   userdata/launcher.json            the launcher's own settings
 *   userdata/shared-settings.json     the pre-run settings the mods share
 *   userdata/settings/<modId>.json    the pre-run settings of each mod
 *   userdata/mods/<modId>/            the storage of each mod
 *   userdata/logs/<modId>/            the runtime logs of each mod
 *   userdata/beaconlink/nodes.json    the user's Beacon.Link node list (with the other Beacon.Link files)
 *}
 * The launcher and the runtime share this layout. Each finds the install from the location of its own classes
 * ({@link #detect(Class, String)}); a development run from the class folders of the source tree uses the development
 * install {@code <repository>/build/dev-install}, which the build assembles.
 */
public final class InstallLayout {
    /**
     * The launcher distribution folder.
     */
    public static final @NotNull String LAUNCHER_FOLDER = "launcher";

    /**
     * The runtime distribution folder.
     */
    public static final @NotNull String RUNTIME_FOLDER = "runtime";

    /**
     * The mod jar folder.
     */
    public static final @NotNull String MODS_FOLDER = "mods";

    /**
     * The user data folder.
     */
    public static final @NotNull String USER_DATA_FOLDER = "userdata";

    /**
     * The development install below the repository root.
     */
    public static final @NotNull String DEVELOPMENT_INSTALL = "build/dev-install";

    /**
     * The system property that names the install folder in the Java process of a mod tool, which the launcher starts with
     * {@code tool <mod id> <tool id>}.
     */
    public static final @NotNull String INSTALL_PROPERTY = "sc4j.install";

    private static final @NotNull String LIBRARY_FOLDER = "lib";
    private static final @NotNull String BINARY_FOLDER = "bin";
    private static final @NotNull String JAR_EXTENSION = ".jar";
    private static final @NotNull String SETTINGS_FOLDER = "settings";
    private static final @NotNull String MOD_STORAGE_FOLDER = "mods";
    private static final @NotNull String LOG_FOLDER = "logs";
    private static final @NotNull String BEACON_LINK_FOLDER = "beaconlink";
    private static final @NotNull String LAUNCHER_SETTINGS_FILE = "launcher.json";
    private static final @NotNull String SHARED_SETTINGS_FILE = "shared-settings.json";
    private static final @NotNull String NODES_FILE = "nodes.json";
    private static final @NotNull String SEEDS_FILE = "seeds.json";
    private static final @NotNull String SETTINGS_EXTENSION = ".json";

    /**
     * The folders from a module's class folder ({@code <module>/build/classes/java/main}) up to the repository root.
     */
    private static final int CLASS_FOLDER_DEPTH = 5;
    private static final @NotNull Path CLASS_FOLDER_SUFFIX = Path.of("build", "classes", "java", "main");

    /**
     * Detects the install from the location of a program's classes.
     *
     * @param programClass       a class of the program's own jar or class folder, such as its main class
     * @param distributionFolder the program's distribution folder below the install, {@link #LAUNCHER_FOLDER} or {@link #RUNTIME_FOLDER}
     * @return the detected install layout, or {@code null} when the classes are neither in {@code <install>/<distributionFolder>/lib} nor in a class folder of the source tree
     */
    public static @Nullable InstallLayout detect(@NotNull Class<?> programClass, @NotNull String distributionFolder) {
        try {
            CodeSource source = programClass.getProtectionDomain().getCodeSource();

            return source == null ? null : fromCodeLocation(Path.of(source.getLocation().toURI()), distributionFolder);
        } catch (URISyntaxException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Creates a {@link InstallLayout} instance from the location of a program's classes. A jar in {@code <install>/<distributionFolder>/lib}
     * gives {@code <install>}; a class folder {@code <repository>/<module>/build/classes/java/main} gives the development install.
     *
     * @param codeLocation       a jar or a class folder
     * @param distributionFolder the program's distribution folder below the install, {@link #LAUNCHER_FOLDER} or {@link #RUNTIME_FOLDER}
     * @return the created {@link InstallLayout} instance, or {@code null} for any other location
     */
    public static @Nullable InstallLayout fromCodeLocation(@NotNull Path codeLocation, @NotNull String distributionFolder) {
        Path location = codeLocation.toAbsolutePath().normalize();
        Path fileName = location.getFileName();
        Path library = location.getParent();
        Path distribution = library == null ? null : library.getParent();

        if (fileName != null && fileName.toString().endsWith(JAR_EXTENSION) && library != null && distribution != null &&
            isNamed(library, LIBRARY_FOLDER) && isNamed(distribution, distributionFolder) && distribution.getParent() != null) {
            return new InstallLayout(distribution.getParent());
        }

        if (location.endsWith(CLASS_FOLDER_SUFFIX) && location.getNameCount() > CLASS_FOLDER_DEPTH) {
            Path repository = location;

            for (int level = 0; level < CLASS_FOLDER_DEPTH; level++) {
                repository = repository.getParent();
            }

            return new InstallLayout(repository.resolve(DEVELOPMENT_INSTALL));
        }

        return null;
    }

    /**
     * Creates a {@link InstallLayout} instance for an install folder.
     *
     * @param root the install folder
     * @return the created {@link InstallLayout} instance
     */
    public static @NotNull InstallLayout of(@NotNull Path root) {
        return new InstallLayout(root);
    }

    private static boolean isNamed(@NotNull Path path, @NotNull String name) {
        Path fileName = path.getFileName();

        return fileName != null && fileName.toString().equals(name);
    }


    private final @NotNull Path root;

    /**
     * Creates a {@link InstallLayout} instance.
     *
     * @param root the install folder, made absolute and normalized
     */
    private InstallLayout(@NotNull Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * @return the absolute install folder
     */
    public @NotNull Path getRoot() {
        return this.root;
    }

    /**
     * @return {@code <install>/launcher}
     */
    public @NotNull Path getLauncherDirectory() {
        return this.root.resolve(LAUNCHER_FOLDER);
    }

    /**
     * @return {@code <install>/launcher/seeds.json}, the Beacon.Link seed list the install ships, in the node list format
     */
    public @NotNull Path getSeedsFile() {
        return this.getLauncherDirectory().resolve(SEEDS_FILE);
    }

    /**
     * @return {@code <install>/runtime}
     */
    public @NotNull Path getRuntimeDirectory() {
        return this.root.resolve(RUNTIME_FOLDER);
    }

    /**
     * Returns a start script of the runtime distribution.
     *
     * @param scriptName the script file name, such as {@code sc4j-runtime.bat}
     * @return {@code <install>/runtime/bin/<scriptName>}
     */
    public @NotNull Path getRuntimeScript(@NotNull String scriptName) {
        return this.getRuntimeDirectory().resolve(BINARY_FOLDER).resolve(scriptName);
    }

    /**
     * @return {@code <install>/mods}, the mod jars
     */
    public @NotNull Path getModsDirectory() {
        return this.root.resolve(MODS_FOLDER);
    }

    /**
     * @return {@code <install>/userdata}
     */
    public @NotNull Path getUserDataDirectory() {
        return this.root.resolve(USER_DATA_FOLDER);
    }

    /**
     * @return {@code <install>/userdata/launcher.json}
     */
    public @NotNull Path getLauncherSettingsFile() {
        return this.getUserDataDirectory().resolve(LAUNCHER_SETTINGS_FILE);
    }

    /**
     * Returns the file of the value of each shared setting ({@code shared}) by its shared name, one value for every mod that declares the same
     * name.
     *
     * @return {@code <install>/userdata/shared-settings.json}
     */
    public @NotNull Path getSharedSettingsFile() {
        return this.getUserDataDirectory().resolve(SHARED_SETTINGS_FILE);
    }

    /**
     * Returns the settings value file of a mod.
     *
     * @param modId the mod id
     * @return {@code <install>/userdata/settings/<modId>.json}
     */
    public @NotNull Path getSettingsFile(@NotNull String modId) {
        return this.getUserDataDirectory().resolve(SETTINGS_FOLDER).resolve(modId + SETTINGS_EXTENSION);
    }

    /**
     * Returns the storage folder of a mod.
     *
     * @param modId the mod id
     * @return {@code <install>/userdata/mods/<modId>}
     */
    public @NotNull Path getStorageDirectory(@NotNull String modId) {
        return this.getUserDataDirectory().resolve(MOD_STORAGE_FOLDER).resolve(modId);
    }

    /**
     * Returns the log folder of a mod's runs.
     *
     * @param modId the mod id
     * @return {@code <install>/userdata/logs/<modId>}
     */
    public @NotNull Path getLogDirectory(@NotNull String modId) {
        return this.getUserDataDirectory().resolve(LOG_FOLDER).resolve(modId);
    }

    /**
     * @return {@code <install>/userdata/beaconlink}
     */
    public @NotNull Path getBeaconLinkDirectory() {
        return this.getUserDataDirectory().resolve(BEACON_LINK_FOLDER);
    }

    /**
     * Returns the file of the user's Beacon.Link node list, which the launcher writes once the user saves it. While it does not exist, the seed
     * list ({@link #getSeedsFile()}) stands in for it.
     *
     * @return {@code <install>/userdata/beaconlink/nodes.json}
     */
    public @NotNull Path getNodesFile() {
        return this.getBeaconLinkDirectory().resolve(NODES_FILE);
    }
}
