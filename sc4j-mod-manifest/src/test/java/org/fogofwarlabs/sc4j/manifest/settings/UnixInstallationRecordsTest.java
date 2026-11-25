package org.fogofwarlabs.sc4j.manifest.settings;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests the program folders of the Linux and macOS installation records in a home folder that the test lays out in a temporary folder.
 */
final class UnixInstallationRecordsTest {
    private static final @NotNull String PROGRAMS_32 = "drive_c/Program Files (x86)";
    private static final @NotNull String PROGRAMS_64 = "drive_c/Program Files";

    private static @NotNull Path createFolder(@NotNull Path directory) throws IOException {
        return Files.createDirectories(directory).toRealPath();
    }

    /**
     * Keeps the folders below the test's home folder, leaving out the system folders of the computer that runs the test.
     */
    private static @NotNull List<@NotNull Path> keepBelow(@NotNull List<@NotNull Path> directories, @NotNull Path home) throws IOException {
        Path realHome = home.toRealPath();

        return directories.stream().filter(directory -> directory.startsWith(realHome)).toList();
    }

    @Test
    void linux_records_list_the_program_folders_of_the_wine_prefixes_in_order(@TempDir @NotNull Path home) throws IOException {
        Path custom = createFolder(home.resolve("custom-prefix").resolve(PROGRAMS_64));
        Path defaultPrefix = createFolder(home.resolve(".wine").resolve(PROGRAMS_32));
        Path defaultPrefix64 = createFolder(home.resolve(".wine").resolve(PROGRAMS_64));
        Path steamSecond = createFolder(home.resolve(".local/share/Steam/steamapps/compatdata/200/pfx").resolve(PROGRAMS_32));
        Path steamFirst = createFolder(home.resolve(".local/share/Steam/steamapps/compatdata/100/pfx").resolve(PROGRAMS_32));
        Path flatpakSteam = createFolder(home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/compatdata/300/pfx")
                                             .resolve(PROGRAMS_64));
        Path lutris = createFolder(home.resolve("Games/example-game").resolve(PROGRAMS_32));
        Path bottles = createFolder(home.resolve(".local/share/bottles/bottles/Gaming").resolve(PROGRAMS_32));
        Path flatpakBottles = createFolder(home.resolve(".var/app/com.usebottles.bottles/data/bottles/bottles/Other").resolve(PROGRAMS_64));

        Files.createDirectories(home.resolve(".local/share/Steam/steamapps/compatdata/400"));
        Files.writeString(home.resolve("Games").resolve("notes.txt"), "not a prefix");

        UnixInstallationRecords records = UnixInstallationRecords.ofLinux(home, null, home.resolve("custom-prefix"));

        Assertions.assertEquals(List.of(custom, defaultPrefix, defaultPrefix64, steamFirst, steamSecond, flatpakSteam, lutris, bottles,
                                        flatpakBottles),
                                records.getProgramDirectories());
        Assertions.assertEquals(List.of(), records.findUninstallLocations("Example Game"));
    }

    @Test
    void linux_records_list_each_folder_once_and_follow_the_data_home(@TempDir @NotNull Path home) throws IOException {
        Path defaultPrefix = createFolder(home.resolve(".wine").resolve(PROGRAMS_32));
        Path bottles = createFolder(home.resolve("data/bottles/bottles/Gaming").resolve(PROGRAMS_32));

        createFolder(home.resolve(".local/share/bottles/bottles/Ignored").resolve(PROGRAMS_32));

        UnixInstallationRecords records = UnixInstallationRecords.ofLinux(home, home.resolve("data"), home.resolve(".wine"));

        Assertions.assertEquals(List.of(defaultPrefix, bottles), records.getProgramDirectories());
        Assertions.assertEquals(List.of(), UnixInstallationRecords.ofLinux(home.resolve("missing"), null, null).getProgramDirectories());
    }

    @Test
    void mac_records_list_the_application_folders_before_the_wine_bottles(@TempDir @NotNull Path home) throws IOException {
        Path applications = createFolder(home.resolve("Applications"));
        Path defaultPrefix = createFolder(home.resolve(".wine").resolve(PROGRAMS_64));
        Path bottle = createFolder(home.resolve("Library/Application Support/CrossOver/Bottles/Games").resolve(PROGRAMS_32));

        UnixInstallationRecords records = UnixInstallationRecords.ofMacOs(home, null);

        Assertions.assertEquals(List.of(applications, defaultPrefix, bottle), keepBelow(records.getProgramDirectories(), home));
    }
}
