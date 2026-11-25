package org.fogofwarlabs.sc4j.manifest.settings;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads where programs are installed on Linux and macOS: the application folders of macOS ({@code /Applications},
 * {@code ~/Applications}) and the Windows program folders ({@code drive_c/Program Files (x86)}, {@code drive_c/Program Files}) of the Wine
 * prefixes at their default places, in this order: {@code WINEPREFIX}, {@code ~/.wine}, the Proton prefixes of Steam
 * ({@code steamapps/compatdata/<app>/pfx} of the native and the Flatpak Steam), the Lutris game folders ({@code ~/Games/<game>}), the bottles
 * of Bottles (native and Flatpak) on Linux and of CrossOver on macOS. These systems keep no uninstall entries.
 * <p>
 * Only folders that exist are returned, each once (by its real path). A folder of prefixes is listed one level deep, in name order; no drive
 * is searched.
 */
final class UnixInstallationRecords implements InstallationRecords {
    private static final @NotNull List<@NotNull String> WINDOWS_PROGRAM_FOLDERS = List.of("drive_c/Program Files (x86)", "drive_c/Program Files");
    private static final @NotNull String SYSTEM_APPLICATION_FOLDER = "/Applications";
    private static final @NotNull String HOME_APPLICATION_FOLDER = "Applications";
    private static final @NotNull String DEFAULT_WINE_PREFIX = ".wine";
    private static final @NotNull String DEFAULT_DATA_FOLDER = ".local/share";
    private static final @NotNull String STEAM_LINK = ".steam/steam";
    private static final @NotNull String STEAM_FOLDER = "Steam";
    private static final @NotNull String FLATPAK_STEAM_FOLDER = ".var/app/com.valvesoftware.Steam/.local/share/Steam";
    private static final @NotNull String STEAM_PREFIX_FOLDER = "steamapps/compatdata";
    private static final @NotNull String STEAM_PREFIX = "pfx";
    private static final @NotNull String LUTRIS_GAME_FOLDER = "Games";
    private static final @NotNull String BOTTLES_FOLDER = "bottles/bottles";
    private static final @NotNull String FLATPAK_BOTTLES_FOLDER = ".var/app/com.usebottles.bottles/data/bottles/bottles";
    private static final @NotNull String CROSSOVER_BOTTLES_FOLDER = "Library/Application Support/CrossOver/Bottles";
    private static final @NotNull String WINE_PREFIX_VARIABLE = "WINEPREFIX";
    private static final @NotNull String DATA_HOME_VARIABLE = "XDG_DATA_HOME";
    private static final @NotNull String HOME_PROPERTY = "user.home";
    private static final @NotNull String OPERATING_SYSTEM_PROPERTY = "os.name";
    private static final @NotNull String MAC_NAME_PREFIX = "mac";

    /**
     * A folder whose entries are Wine prefixes, or hold one at a fixed path.
     */
    private static final class PrefixFolder {
        private final @NotNull Path folder;
        private final @Nullable String prefixPath;

        private PrefixFolder(@NotNull Path folder, @Nullable String prefixPath) {
            this.folder     = folder;
            this.prefixPath = prefixPath;
        }
    }

    /**
     * Creates a {@link UnixInstallationRecords} instance for the running computer: its home folder, {@code XDG_DATA_HOME} and
     * {@code WINEPREFIX}, as macOS or Linux records by the operating system's name.
     *
     * @return the created {@link UnixInstallationRecords} instance
     */
    static @NotNull UnixInstallationRecords ofSystem() {
        Path home = parsePath(System.getProperty(HOME_PROPERTY, ""));
        Path winePrefix = parsePath(System.getenv(WINE_PREFIX_VARIABLE));
        Path dataHome = parsePath(System.getenv(DATA_HOME_VARIABLE));

        if (home == null) {
            return new UnixInstallationRecords(List.of(), List.of(), List.of());
        }

        if (System.getProperty(OPERATING_SYSTEM_PROPERTY, "").toLowerCase(Locale.ROOT).startsWith(MAC_NAME_PREFIX)) {
            return ofMacOs(home, winePrefix);
        }

        return ofLinux(home, dataHome, winePrefix);
    }

    /**
     * Creates a {@link UnixInstallationRecords} instance with the places of Linux.
     *
     * @param home       the user's home folder
     * @param dataHome   the user's data folder ({@code XDG_DATA_HOME}), or {@code null} for {@code ~/.local/share}
     * @param winePrefix the Wine prefix of {@code WINEPREFIX}, or {@code null} when none is set
     * @return the created {@link UnixInstallationRecords} instance
     */
    static @NotNull UnixInstallationRecords ofLinux(@NotNull Path home, @Nullable Path dataHome, @Nullable Path winePrefix) {
        Path data = dataHome != null ? dataHome : home.resolve(DEFAULT_DATA_FOLDER);

        return new UnixInstallationRecords(List.of(), createWinePrefixes(home, winePrefix),
                                           List.of(new PrefixFolder(home.resolve(STEAM_LINK).resolve(STEAM_PREFIX_FOLDER), STEAM_PREFIX),
                                                   new PrefixFolder(data.resolve(STEAM_FOLDER).resolve(STEAM_PREFIX_FOLDER), STEAM_PREFIX),
                                                   new PrefixFolder(home.resolve(FLATPAK_STEAM_FOLDER).resolve(STEAM_PREFIX_FOLDER), STEAM_PREFIX),
                                                   new PrefixFolder(home.resolve(LUTRIS_GAME_FOLDER), null),
                                                   new PrefixFolder(data.resolve(BOTTLES_FOLDER), null),
                                                   new PrefixFolder(home.resolve(FLATPAK_BOTTLES_FOLDER), null)));
    }

    /**
     * Creates a {@link UnixInstallationRecords} instance with the places of macOS.
     *
     * @param home       the user's home folder
     * @param winePrefix the Wine prefix of {@code WINEPREFIX}, or {@code null} when none is set
     * @return the created {@link UnixInstallationRecords} instance
     */
    static @NotNull UnixInstallationRecords ofMacOs(@NotNull Path home, @Nullable Path winePrefix) {
        return new UnixInstallationRecords(List.of(Path.of(SYSTEM_APPLICATION_FOLDER), home.resolve(HOME_APPLICATION_FOLDER)),
                                           createWinePrefixes(home, winePrefix),
                                           List.of(new PrefixFolder(home.resolve(CROSSOVER_BOTTLES_FOLDER), null)));
    }

    private static @NotNull List<@NotNull Path> createWinePrefixes(@NotNull Path home, @Nullable Path winePrefix) {
        List<Path> prefixes = new ArrayList<>();

        if (winePrefix != null) {
            prefixes.add(winePrefix);
        }

        prefixes.add(home.resolve(DEFAULT_WINE_PREFIX));

        return prefixes;
    }

    private static @Nullable Path parsePath(@Nullable String text) {
        try {
            return text == null || text.isBlank() ? null : Path.of(text);
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    /**
     * Adds the real path of a folder when the folder exists and its real path was not added yet.
     *
     * @param directories the real paths added so far
     * @param directory   the folder
     */
    private static void addExisting(@NotNull List<@NotNull Path> directories, @NotNull Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }

        Path real;

        try {
            real = directory.toRealPath();
        } catch (IOException exception) {
            return;
        }

        if (!directories.contains(real)) {
            directories.add(real);
        }
    }


    private final @NotNull List<@NotNull Path> applicationDirectories;
    private final @NotNull List<@NotNull Path> winePrefixes;
    private final @NotNull List<@NotNull PrefixFolder> prefixFolders;

    private UnixInstallationRecords(@NotNull List<@NotNull Path> applicationDirectories, @NotNull List<@NotNull Path> winePrefixes,
                                    @NotNull List<@NotNull PrefixFolder> prefixFolders) {
        this.applicationDirectories = List.copyOf(applicationDirectories);
        this.winePrefixes           = List.copyOf(winePrefixes);
        this.prefixFolders          = List.copyOf(prefixFolders);
    }

    /**
     * Returns the install locations of the uninstall entries with a name.
     *
     * @param entryName the entry name, such as {@code Example Game}
     * @return no location: the systems these records read keep no uninstall entries
     */
    @Override
    public @NotNull List<@NotNull Path> findUninstallLocations(@NotNull String entryName) {
        return List.of();
    }

    /**
     * @return the folders programs are installed into by default, in the order they are looked at
     */
    @Override
    public @NotNull List<@NotNull Path> getProgramDirectories() {
        List<Path> prefixes = new ArrayList<>(this.winePrefixes);
        List<Path> directories = new ArrayList<>();

        for (PrefixFolder prefixFolder : this.prefixFolders) {
            if (!Files.isDirectory(prefixFolder.folder)) {
                continue;
            }

            List<Path> entries = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(prefixFolder.folder, Files::isDirectory)) {
                for (Path entry : stream) {
                    entries.add(entry);
                }
            } catch (IOException exception) {
                continue;
            }

            entries.sort(null);

            for (Path entry : entries) {
                prefixes.add(prefixFolder.prefixPath == null ? entry : entry.resolve(prefixFolder.prefixPath));
            }
        }

        for (Path applicationDirectory : this.applicationDirectories) {
            addExisting(directories, applicationDirectory);
        }

        for (Path prefix : prefixes) {
            for (String programFolder : WINDOWS_PROGRAM_FOLDERS) {
                addExisting(directories, prefix.resolve(programFolder));
            }
        }

        return directories;
    }
}
