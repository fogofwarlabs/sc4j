package org.fogofwarlabs.sc4j.launcher.install;

import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.mods.ModRelease;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.fogofwarlabs.sc4j.launcher.catalog.IdConflictIssue;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * Tests mod installs from releases: a checked jar joins {@code mods/} with its source recorded, an incompatible jar or a jar of a taken id
 * is refused, a newer release is found and installed in place, and removing a mod removes its record.
 */
final class ModInstallerTest {
    private static final @NotNull String MANIFEST = """
                                                    {
                                                      "format": 1,
                                                      "id": "%s",
                                                      "name": {"en": "My Mod"},
                                                      "version": "1.0.0",
                                                      "engine": "%s",
                                                      "entry": "org.example.MyMod"
                                                    }
                                                    """;
    private static final @NotNull String MOD_ID = "my-mod";
    private static final @NotNull String JAR = MOD_ID + ".jar";
    private static final @NotNull String REPOSITORY = "owner/my-mod";
    private static final @NotNull String FIRST_TAG = "v1.0.0";
    private static final @NotNull String SECOND_TAG = "v1.1.0";
    private static final @NotNull String UNSUPPORTED_ENGINE = "99.0";

    @Test
    void install_should_add_a_checked_jar_and_record_its_source(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ModInstaller installer = new ModInstaller(install, new ReleaseClient(server.getApi()));
            ModInstallResult result;

            server.addCheckedRelease(REPOSITORY, FIRST_TAG, Map.of(JAR, createJar(directory, MOD_ID, EngineVersion.CURRENT.toString())));
            result = installRelease(installer, install, null);

            Assertions.assertEquals(JAR, result.getJarName());
            Assertions.assertEquals(List.of(), result.getIssues());
            Assertions.assertTrue(Files.isRegularFile(install.getModsDirectory().resolve(JAR)));
            assertRecord(installer, FIRST_TAG);
        }
    }

    @Test
    void install_should_refuse_a_jar_for_an_unsupported_engine(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ModInstaller installer = new ModInstaller(install, new ReleaseClient(server.getApi()));
            ModRelease release;
            Path downloaded;
            ModInstallResult result;

            server.addCheckedRelease(REPOSITORY, FIRST_TAG, Map.of(JAR, createJar(directory, MOD_ID, UNSUPPORTED_ENGINE)));
            release    = ModRelease.find(installer.getClient(), requireRepository(), null);
            downloaded = installer.download(release, share -> {
            });
            result     = installer.install(release, downloaded, ModCatalog.scan(install, InstallationRecords.NONE), null);

            Assertions.assertNull(result.getJarName());
            Assertions.assertFalse(result.getIssues().isEmpty());
            Assertions.assertFalse(Files.exists(downloaded));
            Assertions.assertFalse(Files.exists(install.getModsDirectory().resolve(JAR)));
            Assertions.assertEquals(List.of(), installer.getSourcesFile().read());
        }
    }

    @Test
    void install_should_refuse_a_jar_whose_id_another_mod_declares(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        String engine = EngineVersion.CURRENT.toString();

        Files.createDirectories(install.getModsDirectory());
        Files.write(install.getModsDirectory().resolve("copy.jar"), createJar(directory, MOD_ID, engine));

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ModInstaller installer = new ModInstaller(install, new ReleaseClient(server.getApi()));
            ModInstallResult result;

            server.addCheckedRelease(REPOSITORY, FIRST_TAG, Map.of(JAR, createJar(directory, MOD_ID, engine)));
            result = installRelease(installer, install, null);

            Assertions.assertNull(result.getJarName());
            Assertions.assertInstanceOf(IdConflictIssue.class, result.getIssues().getFirst());
            Assertions.assertFalse(Files.exists(install.getModsDirectory().resolve(JAR)));
        }
    }

    @Test
    void find_updates_should_offer_a_newer_release_that_installs_in_place(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        String engine = EngineVersion.CURRENT.toString();

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ModInstaller installer = new ModInstaller(install, new ReleaseClient(server.getApi()));
            ModCatalog catalog;
            Map<String, ModRelease> updates;
            ModEntry installed;

            server.addCheckedRelease(REPOSITORY, FIRST_TAG, Map.of(JAR, createJar(directory, MOD_ID, engine)));
            installRelease(installer, install, null);

            catalog = ModCatalog.scan(install, InstallationRecords.NONE);
            Assertions.assertEquals(Map.of(), installer.findUpdates(catalog));

            server.addCheckedRelease(REPOSITORY, SECOND_TAG, Map.of(JAR, createJar(directory, MOD_ID, engine)));
            updates = installer.findUpdates(catalog);
            Assertions.assertEquals(List.of(JAR), List.copyOf(updates.keySet()));
            Assertions.assertEquals(SECOND_TAG, updates.get(JAR).getTag());

            installed = catalog.findByJarName(JAR);
            Assertions.assertNotNull(installed);
            Assertions.assertEquals(JAR, installRelease(installer, install, installed).getJarName());
            assertRecord(installer, SECOND_TAG);
            Assertions.assertEquals(Map.of(), installer.findUpdates(ModCatalog.scan(install, InstallationRecords.NONE)));
        }
    }

    @Test
    void remove_should_delete_the_jar_and_its_record(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ModInstaller installer = new ModInstaller(install, new ReleaseClient(server.getApi()));
            ModEntry installed;

            server.addCheckedRelease(REPOSITORY, FIRST_TAG, Map.of(JAR, createJar(directory, MOD_ID, EngineVersion.CURRENT.toString())));
            installRelease(installer, install, null);
            installed = ModCatalog.scan(install, InstallationRecords.NONE).findByJarName(JAR);

            Assertions.assertNotNull(installed);

            installer.remove(installed);

            Assertions.assertFalse(Files.exists(install.getModsDirectory().resolve(JAR)));
            Assertions.assertEquals(List.of(), installer.getSourcesFile().read());
        }
    }

    @Test
    void find_release_should_tell_a_release_without_a_checked_jar(@TempDir @NotNull Path directory) throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ModInstaller installer = new ModInstaller(InstallLayout.of(directory), new ReleaseClient(server.getApi()));
            ReleaseException exception;

            server.addRelease(REPOSITORY, FIRST_TAG, Map.of(JAR, createJar(directory, MOD_ID, EngineVersion.CURRENT.toString())));
            exception = Assertions.assertThrows(ReleaseException.class, () -> ModRelease.find(installer.getClient(), requireRepository(), null));

            Assertions.assertEquals(ReleaseProblem.ASSET_MISSING, exception.getProblem());
        }
    }

    private static @NotNull ModInstallResult installRelease(@NotNull ModInstaller installer, @NotNull InstallLayout install,
                                                            @Nullable ModEntry replaced) throws IOException {
        ModRelease release = ModRelease.find(installer.getClient(), requireRepository(), replaced == null ? null : replaced.getJarName());
        Path downloaded = installer.download(release, share -> {
        });

        return installer.install(release, downloaded, ModCatalog.scan(install, InstallationRecords.NONE), replaced);
    }

    private static void assertRecord(@NotNull ModInstaller installer, @NotNull String tag) throws IOException {
        List<ModSource> sources = installer.getSourcesFile().read();

        Assertions.assertEquals(1, sources.size());
        Assertions.assertEquals(JAR, sources.getFirst().getJarName());
        Assertions.assertEquals(REPOSITORY, sources.getFirst().getRepository().toString());
        Assertions.assertEquals(tag, sources.getFirst().getRelease());
    }

    private static @NotNull ReleaseRepository requireRepository() {
        ReleaseRepository repository = ReleaseRepository.parse(REPOSITORY);

        Assertions.assertNotNull(repository);

        return repository;
    }

    private static byte @NotNull [] createJar(@NotNull Path directory, @NotNull String id, @NotNull String engine) throws IOException {
        Path jar = Files.createTempFile(directory, id, ".jar");

        TestJarUtils.write(jar, Map.of(TestJarUtils.MANIFEST_ENTRY, MANIFEST.formatted(id, engine).getBytes(StandardCharsets.UTF_8)));

        return Files.readAllBytes(jar);
    }
}
