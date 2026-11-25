package org.fogofwarlabs.sc4j.launcher.update;

import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
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
import java.util.stream.Stream;

/**
 * Tests the launcher update up to the updater step: the latest release against the running version, the package of the updater's platform,
 * the download and unpacking of the user package, broken packages and packages of another platform, the failure note of the updater and the
 * cleanup of the update folder.
 */
final class LauncherUpdaterTest {
    private static final @NotNull String REPOSITORY = "owner/sc4j";
    private static final @NotNull String TAG = "v1.2.0";
    private static final @NotNull String PACKAGE = "SC4J-1.2.0";
    private static final @NotNull String PACKAGE_FILE = PACKAGE + "-" + UserPackageScenario.PLATFORM.getId() + ".zip";
    private static final @NotNull String NEW = "new";

    @Test
    void is_automatic_check_allowed_should_hold_for_release_builds_only() {
        Assertions.assertTrue(LauncherUpdater.isAutomaticCheckAllowed(ReleaseVersion.parse("1.0.0")));
        Assertions.assertFalse(LauncherUpdater.isAutomaticCheckAllowed(ReleaseVersion.parse("1.1.0-SNAPSHOT")));
        Assertions.assertFalse(LauncherUpdater.isAutomaticCheckAllowed(ReleaseVersion.parse("development")));
    }

    @Test
    void find_update_should_offer_a_release_newer_than_the_running_version(@TempDir @NotNull Path directory) throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = createUpdater(directory, server);
            LauncherRelease release;

            server.addCheckedRelease(REPOSITORY, TAG, Map.of(PACKAGE_FILE, UserPackageScenario.createZip(PACKAGE, Map.of())));
            release = updater.findUpdate(requireRepository(), ReleaseVersion.parse("1.1.0"));

            Assertions.assertNotNull(release);
            Assertions.assertEquals("1.2.0", release.getVersion().toString());
            Assertions.assertEquals(PACKAGE_FILE, release.getPackageFile().getName());
            Assertions.assertNull(updater.findUpdate(requireRepository(), ReleaseVersion.parse("1.2.0")));
            Assertions.assertNull(updater.findUpdate(requireRepository(), ReleaseVersion.parse("1.3.0")));
            Assertions.assertNotNull(updater.findUpdate(requireRepository(), ReleaseVersion.parse("1.2.0-SNAPSHOT")));
            Assertions.assertNotNull(updater.findUpdate(requireRepository(), ReleaseVersion.parse("development")));
        }
    }

    @Test
    void find_update_should_tell_a_release_without_the_package_of_its_platform(@TempDir @NotNull Path directory) throws IOException {
        String otherPackage = PACKAGE + "-" + PackagePlatform.WINDOWS_X64.getId() + ".zip";

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ReleaseClient client = new ReleaseClient(server.getApi());
            LauncherUpdater updater = new LauncherUpdater(InstallLayout.of(directory), client, UserPackageScenario.PLATFORM);
            LauncherUpdater unsupported = new LauncherUpdater(InstallLayout.of(directory), client, null);
            ReleaseException missing;
            ReleaseException noPlatform;

            server.addCheckedRelease(REPOSITORY, TAG, Map.of(otherPackage, UserPackageScenario.createZip(PACKAGE, Map.of())));
            missing    = Assertions.assertThrows(ReleaseException.class,
                                                 () -> updater.findUpdate(requireRepository(), ReleaseVersion.parse("1.1.0")));
            noPlatform = Assertions.assertThrows(ReleaseException.class,
                                                 () -> unsupported.findUpdate(requireRepository(), ReleaseVersion.parse("1.1.0")));

            Assertions.assertEquals(ReleaseProblem.PLATFORM_MISSING, missing.getProblem());
            Assertions.assertEquals(ReleaseProblem.PLATFORM_MISSING, noPlatform.getProblem());
        }
    }

    @Test
    void find_update_should_tell_a_release_without_the_checksum_file(@TempDir @NotNull Path directory) throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = createUpdater(directory, server);
            ReleaseException exception;

            server.addRelease(REPOSITORY, TAG, Map.of(PACKAGE_FILE, UserPackageScenario.createZip(PACKAGE, Map.of())));
            exception = Assertions.assertThrows(ReleaseException.class, () -> updater.findUpdate(requireRepository(), ReleaseVersion.parse("1.1.0")));

            Assertions.assertEquals(ReleaseProblem.ASSET_MISSING, exception.getProblem());
        }
    }

    @Test
    void download_and_unpack_should_give_the_checked_package(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        Map<String, String> files = UserPackageScenario.createProgramFiles(NEW);
        byte[] zip;

        files.putAll(UserPackageScenario.createPlatformFile(UserPackageScenario.PLATFORM));
        zip = UserPackageScenario.createZip(PACKAGE, files);

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = new LauncherUpdater(install, new ReleaseClient(server.getApi()), UserPackageScenario.PLATFORM);
            LauncherRelease release;
            Path packageFile;
            Path packageRoot;

            server.addCheckedRelease(REPOSITORY, TAG, Map.of(PACKAGE_FILE, zip));
            release     = requireUpdate(updater);
            packageFile = updater.download(release, share -> {
            });
            packageRoot = updater.unpack(release, packageFile);

            Assertions.assertEquals(UpdateFolder.of(install).getDownloadFile(PACKAGE_FILE), packageFile);
            Assertions.assertArrayEquals(zip, Files.readAllBytes(packageFile));
            Assertions.assertEquals(UpdateFolder.of(install).getPackageDirectory().resolve(PACKAGE), packageRoot);

            for (String file : UserPackageScenario.createProgramFiles(NEW).keySet()) {
                Assertions.assertEquals(NEW, Files.readString(packageRoot.resolve(file), StandardCharsets.UTF_8), file);
            }
        }
    }

    @Test
    void unpack_should_refuse_a_package_without_every_program(@TempDir @NotNull Path directory) throws IOException {
        Map<String, String> files = UserPackageScenario.createProgramFiles(NEW);

        files.putAll(UserPackageScenario.createPlatformFile(UserPackageScenario.PLATFORM));
        files.remove("NOTICE");

        assertUnpackRefused(directory, UserPackageScenario.createZip(PACKAGE, files));
    }

    @Test
    void unpack_should_refuse_a_package_of_another_platform_or_of_none(@TempDir @NotNull Path directory) throws IOException {
        Map<String, String> otherPlatform = UserPackageScenario.createProgramFiles(NEW);

        otherPlatform.putAll(UserPackageScenario.createPlatformFile(PackagePlatform.WINDOWS_X64));

        assertUnpackRefused(directory.resolve("other"), UserPackageScenario.createZip(PACKAGE, otherPlatform));
        assertUnpackRefused(directory.resolve("none"), UserPackageScenario.createZip(PACKAGE, UserPackageScenario.createProgramFiles(NEW)));
    }

    @Test
    void unpack_should_refuse_an_entry_outside_the_package_folder(@TempDir @NotNull Path directory) throws IOException {
        Map<String, String> files = UserPackageScenario.createProgramFiles(NEW);

        files.put("../../../outside.txt", NEW);

        assertUnpackRefused(directory, UserPackageScenario.createZip(PACKAGE, files));
        Assertions.assertFalse(Files.exists(directory.resolve("outside.txt")));
        Assertions.assertFalse(Files.exists(directory.resolve(".cache").resolve("outside.txt")));
    }

    @Test
    void take_failure_and_clean_up_should_empty_the_update_folder_but_the_log(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        UpdateFolder folder = UpdateFolder.of(install);
        LauncherUpdater updater = new LauncherUpdater(install, new ReleaseClient(ReleaseClient.GITHUB_API));

        UserPackageScenario.writeFiles(folder.getRoot(), Map.of("package/SC4J-1.2.0/NOTICE", NEW, "mods/my-mod.jar.part", NEW,
                                                                "updater.log", NEW, "failure.txt", "The update failed."));

        Assertions.assertEquals("The update failed.", updater.takeFailure());
        Assertions.assertNull(updater.takeFailure());

        updater.cleanUp();

        try (Stream<Path> entries = Files.list(folder.getRoot())) {
            Assertions.assertEquals(List.of(folder.getLogFile()), entries.toList());
        }
    }

    private static void assertUnpackRefused(@NotNull Path directory, byte @NotNull [] zip) throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = createUpdater(directory, server);
            LauncherRelease release;
            Path packageFile;

            server.addCheckedRelease(REPOSITORY, TAG, Map.of(PACKAGE_FILE, zip));
            release     = requireUpdate(updater);
            packageFile = updater.download(release, share -> {
            });

            Assertions.assertThrows(InvalidPackageException.class, () -> updater.unpack(release, packageFile));
        }
    }

    private static @NotNull LauncherUpdater createUpdater(@NotNull Path directory, @NotNull ReleaseServerScenario server) {
        return new LauncherUpdater(InstallLayout.of(directory), new ReleaseClient(server.getApi()), UserPackageScenario.PLATFORM);
    }

    private static @NotNull LauncherRelease requireUpdate(@NotNull LauncherUpdater updater) throws ReleaseException {
        LauncherRelease release = updater.findUpdate(requireRepository(), ReleaseVersion.parse("1.1.0"));

        Assertions.assertNotNull(release);

        return release;
    }

    private static @NotNull ReleaseRepository requireRepository() {
        ReleaseRepository repository = ReleaseRepository.parse(REPOSITORY);

        Assertions.assertNotNull(repository);

        return repository;
    }
}
