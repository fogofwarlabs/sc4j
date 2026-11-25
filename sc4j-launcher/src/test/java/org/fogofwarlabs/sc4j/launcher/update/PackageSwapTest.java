package org.fogofwarlabs.sc4j.launcher.update;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tests the replacement of an install's programs and shipped mod jars by an unpacked user package: the replaced items, the files that stay,
 * and the old programs restored when a step fails.
 */
final class PackageSwapTest {
    private static final @NotNull String OLD = "old";
    private static final @NotNull String NEW = "new";
    private static final @NotNull String USER_FILE = "userdata/launcher.json";
    private static final @NotNull String MOD_FILE = "mods/demo.jar";
    private static final @NotNull String OTHER_FILE = "notes.txt";
    private static final @NotNull String OLD_ONLY_FILE = "launcher/lib/old-only.jar";
    private static final @NotNull String NEW_ONLY_FILE = "launcher/lib/new-only.jar";
    private static final int ONE_ATTEMPT = 1;
    private static final long NO_DELAY = 0;

    @Test
    void apply_should_replace_the_programs_and_keep_every_other_file(@TempDir @NotNull Path directory) throws IOException {
        Path installRoot = directory.resolve("install");
        Path packageRoot = directory.resolve("package");

        writeInstall(installRoot);
        UserPackageScenario.writeFiles(packageRoot, UserPackageScenario.createProgramFiles(NEW));
        UserPackageScenario.writeFiles(packageRoot, Map.of(NEW_ONLY_FILE, NEW, MOD_FILE, NEW));

        new PackageSwap(installRoot, packageRoot, directory.resolve("backup"), ONE_ATTEMPT, NO_DELAY).apply();

        for (String file : UserPackageScenario.createProgramFiles(NEW).keySet()) {
            Assertions.assertEquals(NEW, Files.readString(installRoot.resolve(file), StandardCharsets.UTF_8), file);
            Assertions.assertEquals(NEW, Files.readString(packageRoot.resolve(file), StandardCharsets.UTF_8), "the package stays: " + file);
        }

        Assertions.assertEquals(NEW, Files.readString(installRoot.resolve(NEW_ONLY_FILE), StandardCharsets.UTF_8));
        Assertions.assertFalse(Files.exists(installRoot.resolve(OLD_ONLY_FILE)), "a program folder is replaced as a whole");
        assertKept(installRoot);
    }

    @Test
    void apply_should_restore_the_old_programs_when_a_step_fails(@TempDir @NotNull Path directory) throws IOException {
        Path installRoot = directory.resolve("install");
        Path packageRoot = directory.resolve("package");
        Path backupRoot = directory.resolve("backup");
        PackageSwap swap = new PackageSwap(installRoot, packageRoot, backupRoot, ONE_ATTEMPT, NO_DELAY);

        writeInstall(installRoot);
        UserPackageScenario.writeFiles(packageRoot, UserPackageScenario.createProgramFiles(NEW));
        // A backup in the way makes the move of NOTICE fail after the items before it were replaced.
        UserPackageScenario.writeFiles(backupRoot, Map.of("NOTICE", OLD));

        Assertions.assertThrows(IOException.class, swap::apply);

        for (String file : UserPackageScenario.createProgramFiles(OLD).keySet()) {
            Assertions.assertEquals(OLD, Files.readString(installRoot.resolve(file), StandardCharsets.UTF_8), file);
        }

        Assertions.assertEquals(OLD, Files.readString(installRoot.resolve(OLD_ONLY_FILE), StandardCharsets.UTF_8));
        assertKept(installRoot);
    }

    @Test
    void apply_should_remove_the_platform_items_when_the_package_has_none(@TempDir @NotNull Path directory) throws IOException {
        Path installRoot = directory.resolve("install");
        Path packageRoot = directory.resolve("package");
        Map<String, String> files = UserPackageScenario.createProgramFiles(NEW);

        files.keySet().removeIf(PackageSwapTest::isPlatformItem);
        writeInstall(installRoot);
        UserPackageScenario.writeFiles(packageRoot, files);

        new PackageSwap(installRoot, packageRoot, directory.resolve("backup"), ONE_ATTEMPT, NO_DELAY).apply();

        for (String file : files.keySet()) {
            Assertions.assertEquals(NEW, Files.readString(installRoot.resolve(file), StandardCharsets.UTF_8), file);
        }

        for (String item : PackageSwap.PLATFORM_ITEMS) {
            Assertions.assertFalse(Files.exists(installRoot.resolve(item)), item);
        }

        assertKept(installRoot);
    }

    @Test
    void apply_should_update_the_shipped_mod_jars_and_keep_the_other_jars(@TempDir @NotNull Path directory) throws IOException {
        Path installRoot = directory.resolve("install");
        Path packageRoot = directory.resolve("package");

        writeInstall(installRoot);
        UserPackageScenario.writeFiles(installRoot, UserPackageScenario.createModFiles(OLD, "shipped.jar", "dropped.jar", "deleted.jar"));
        Files.delete(installRoot.resolve("mods/deleted.jar"));
        UserPackageScenario.writeFiles(installRoot, Map.of("mods/player.jar", OLD));
        UserPackageScenario.writeFiles(packageRoot, UserPackageScenario.createProgramFiles(NEW));
        UserPackageScenario.writeFiles(packageRoot, UserPackageScenario.createModFiles(NEW, "shipped.jar", "deleted.jar", "added.jar", "player.jar"));

        new PackageSwap(installRoot, packageRoot, directory.resolve("backup"), ONE_ATTEMPT, NO_DELAY).apply();

        Assertions.assertEquals(NEW, Files.readString(installRoot.resolve("mods/shipped.jar"), StandardCharsets.UTF_8), "replaced");
        Assertions.assertEquals(NEW, Files.readString(installRoot.resolve("mods/added.jar"), StandardCharsets.UTF_8), "newly shipped");
        Assertions.assertEquals(OLD, Files.readString(installRoot.resolve("mods/player.jar"), StandardCharsets.UTF_8), "the player's jar");
        Assertions.assertFalse(Files.exists(installRoot.resolve("mods/dropped.jar")), "no longer shipped");
        Assertions.assertFalse(Files.exists(installRoot.resolve("mods/deleted.jar")), "deleted by the player");
        Assertions.assertEquals(PackageSwap.readPackageMods(packageRoot), PackageSwap.readPackageMods(installRoot));
        assertKept(installRoot);
    }

    @Test
    void apply_should_replace_and_add_the_shipped_mod_jars_when_the_install_has_no_list(@TempDir @NotNull Path directory) throws IOException {
        Path installRoot = directory.resolve("install");
        Path packageRoot = directory.resolve("package");

        writeInstall(installRoot);
        UserPackageScenario.writeFiles(installRoot, Map.of("mods/player.jar", OLD));
        UserPackageScenario.writeFiles(packageRoot, UserPackageScenario.createProgramFiles(NEW));
        UserPackageScenario.writeFiles(packageRoot, UserPackageScenario.createModFiles(NEW, "demo.jar", "added.jar"));

        new PackageSwap(installRoot, packageRoot, directory.resolve("backup"), ONE_ATTEMPT, NO_DELAY).apply();

        Assertions.assertEquals(NEW, Files.readString(installRoot.resolve(MOD_FILE), StandardCharsets.UTF_8));
        Assertions.assertEquals(NEW, Files.readString(installRoot.resolve("mods/added.jar"), StandardCharsets.UTF_8));
        Assertions.assertEquals(OLD, Files.readString(installRoot.resolve("mods/player.jar"), StandardCharsets.UTF_8));
    }

    @Test
    void apply_should_restore_the_old_mod_jars_when_a_step_fails(@TempDir @NotNull Path directory) throws IOException {
        Path installRoot = directory.resolve("install");
        Path packageRoot = directory.resolve("package");
        Path backupRoot = directory.resolve("backup");
        PackageSwap swap = new PackageSwap(installRoot, packageRoot, backupRoot, ONE_ATTEMPT, NO_DELAY);

        writeInstall(installRoot);
        UserPackageScenario.writeFiles(installRoot, UserPackageScenario.createModFiles(OLD, "demo.jar", "second.jar"));
        UserPackageScenario.writeFiles(packageRoot, UserPackageScenario.createProgramFiles(NEW));
        UserPackageScenario.writeFiles(packageRoot, UserPackageScenario.createModFiles(NEW, "demo.jar", "second.jar", "added.jar"));
        // A backup in the way makes the move of the second jar fail after the programs and the first jar were replaced.
        UserPackageScenario.writeFiles(backupRoot, Map.of("mods/second.jar", OLD));

        Assertions.assertThrows(IOException.class, swap::apply);

        for (String file : UserPackageScenario.createProgramFiles(OLD).keySet()) {
            Assertions.assertEquals(OLD, Files.readString(installRoot.resolve(file), StandardCharsets.UTF_8), file);
        }

        Assertions.assertEquals(OLD, Files.readString(installRoot.resolve("mods/second.jar"), StandardCharsets.UTF_8));
        Assertions.assertFalse(Files.exists(installRoot.resolve("mods/added.jar")));
        Assertions.assertEquals(List.of("demo.jar", "second.jar"), PackageSwap.readPackageMods(installRoot));
        assertKept(installRoot);
    }

    @Test
    void check_package_should_refuse_a_package_without_every_program(@TempDir @NotNull Path directory) throws IOException {
        Map<String, String> files = UserPackageScenario.createProgramFiles(NEW);

        files.remove("NOTICE");
        UserPackageScenario.writeFiles(directory, files);

        Assertions.assertThrows(IOException.class, () -> PackageSwap.checkPackage(directory));
    }

    @Test
    void check_package_should_refuse_a_package_without_a_listed_mod_jar(@TempDir @NotNull Path directory) throws IOException {
        Map<String, String> files = UserPackageScenario.createProgramFiles(NEW);

        files.putAll(UserPackageScenario.createModFiles(NEW, "demo.jar"));
        files.remove(MOD_FILE);
        UserPackageScenario.writeFiles(directory, files);

        Assertions.assertThrows(IOException.class, () -> PackageSwap.checkPackage(directory));
    }

    @Test
    void read_package_mods_should_refuse_a_line_that_is_no_jar_file_name(@TempDir @NotNull Path directory) throws IOException {
        UserPackageScenario.writeFiles(directory, Map.of(PackageSwap.PACKAGE_MODS_FILE, "demo.jar\n../outside.jar\n"));

        Assertions.assertThrows(IOException.class, () -> PackageSwap.readPackageMods(directory));
    }

    private static boolean isPlatformItem(@NotNull String file) {
        return PackageSwap.PLATFORM_ITEMS.stream().anyMatch(item -> file.equals(item) || file.startsWith(item + "/"));
    }

    private static void writeInstall(@NotNull Path installRoot) throws IOException {
        UserPackageScenario.writeFiles(installRoot, UserPackageScenario.createProgramFiles(OLD));
        UserPackageScenario.writeFiles(installRoot, Map.of(OLD_ONLY_FILE, OLD, USER_FILE, OLD, MOD_FILE, OLD, OTHER_FILE, OLD));
    }

    private static void assertKept(@NotNull Path installRoot) throws IOException {
        for (String file : new String[]{USER_FILE, MOD_FILE, OTHER_FILE}) {
            Assertions.assertEquals(OLD, Files.readString(installRoot.resolve(file), StandardCharsets.UTF_8), "kept: " + file);
        }
    }
}
