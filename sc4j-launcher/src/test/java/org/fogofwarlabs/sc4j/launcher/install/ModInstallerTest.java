package org.fogofwarlabs.sc4j.launcher.install;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.mods.ModRelease;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.fogofwarlabs.sc4j.launcher.catalog.IdConflictIssue;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.update.PackageSwap;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.fogofwarlabs.sc4j.manifest.ModManifestReader;
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
 * Tests mod installs from releases: a checked jar joins {@code mods/} with its source recorded, an incompatible jar or a jar of an id two jars
 * declare is refused, any release replaces the installed jar of its id, a newer release is found and installed in place, the release state of
 * an installed jar is told, and removing a mod removes its record.
 */
final class ModInstallerTest {
    private static final @NotNull String MANIFEST = """
                                                    {
                                                      "format": 1,
                                                      "id": "%s",
                                                      "name": {"en": "My Mod"},
                                                      "version": "%s",
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
    void install_should_refuse_a_jar_whose_id_two_installed_jars_declare(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        String engine = EngineVersion.CURRENT.toString();

        Files.createDirectories(install.getModsDirectory());
        Files.write(install.getModsDirectory().resolve("copy.jar"), createJar(directory, MOD_ID, engine));
        Files.write(install.getModsDirectory().resolve("other-copy.jar"), createJar(directory, MOD_ID, engine));

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
    void install_should_replace_the_installed_jar_of_the_id_with_any_release(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        String engine = EngineVersion.CURRENT.toString();
        Path copy = install.getModsDirectory().resolve("copy.jar");

        Files.createDirectories(install.getModsDirectory());
        Files.write(copy, createJar(directory, MOD_ID, "2.0.0", engine));

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ModInstaller installer = new ModInstaller(install, new ReleaseClient(server.getApi()));
            List<ModRelease> releases;

            server.addCheckedRelease(REPOSITORY, FIRST_TAG, Map.of(JAR, createJar(directory, MOD_ID, "1.0.0", engine)));
            server.addCheckedRelease(REPOSITORY, SECOND_TAG, Map.of(JAR, createJar(directory, MOD_ID, "1.1.0", engine)));
            releases = ModRelease.findAll(installer.getClient(), requireRepository(), null);

            Assertions.assertEquals(List.of(SECOND_TAG, FIRST_TAG), releases.stream().map(ModRelease::getTag).toList());
            Assertions.assertEquals("copy.jar", installChosenRelease(installer, install, releases.getLast()).getJarName(), "an older release over a copy");
            Assertions.assertEquals("1.0.0", readVersion(copy));
            Assertions.assertEquals(FIRST_TAG, requireSource(installer, "copy.jar").getRelease());

            Assertions.assertEquals("copy.jar", installChosenRelease(installer, install, releases.getLast()).getJarName(), "the same release again");
            Assertions.assertEquals("copy.jar", installChosenRelease(installer, install, releases.getFirst()).getJarName(), "the latest release");
            Assertions.assertEquals("1.1.0", readVersion(copy));
            Assertions.assertEquals(SECOND_TAG, requireSource(installer, "copy.jar").getRelease());
            Assertions.assertEquals(1, installer.getSourcesFile().read().size());
            Assertions.assertFalse(Files.exists(install.getModsDirectory().resolve(JAR)));
        }
    }

    @Test
    void find_release_state_should_tell_a_latest_an_older_and_an_unreleased_jar(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        String engine = EngineVersion.CURRENT.toString();
        Path jar = install.getModsDirectory().resolve(JAR);
        Path shipped = install.getModsDirectory().resolve("shipped.jar");

        Files.createDirectories(install.getModsDirectory());
        Files.write(shipped, createJar(directory, "shipped", engine));
        Files.createDirectories(install.getLauncherDirectory());
        Files.writeString(install.getRoot().resolve(PackageSwap.PACKAGE_MODS_FILE), "shipped.jar\n");

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ModInstaller installer = new ModInstaller(install, new ReleaseClient(server.getApi()));

            server.addCheckedRelease(REPOSITORY, FIRST_TAG, Map.of(JAR, createJar(directory, MOD_ID, "1.0.0", engine)));
            installRelease(installer, install, null);

            Assertions.assertEquals(ModReleaseState.LATEST, installer.findReleaseState(requireEntry(install, JAR), false));
            Assertions.assertEquals(ModReleaseState.OLDER, installer.findReleaseState(requireEntry(install, JAR), true));
            Assertions.assertEquals(ModReleaseState.LATEST, installer.findReleaseState(requireEntry(install, "shipped.jar"), true), "a shipped jar");

            Files.write(jar, createJar(directory, MOD_ID, "1.1.0", engine));
            Assertions.assertEquals(ModReleaseState.UNRELEASED, installer.findReleaseState(requireEntry(install, JAR), false), "a jar built from source");

            installer.getSourcesFile().write(List.of());
            Assertions.assertEquals(ModReleaseState.UNRELEASED, installer.findReleaseState(requireEntry(install, JAR), false), "a jar without a record");
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

    private static @NotNull ModInstallResult installChosenRelease(@NotNull ModInstaller installer, @NotNull InstallLayout install,
                                                                  @NotNull ModRelease release) throws IOException {
        Path downloaded = installer.download(release, share -> {
        });

        return installer.install(release, downloaded, ModCatalog.scan(install, InstallationRecords.NONE), null);
    }

    private static @NotNull ModEntry requireEntry(@NotNull InstallLayout install, @NotNull String jarName) throws IOException {
        ModEntry entry = ModCatalog.scan(install, InstallationRecords.NONE).findByJarName(jarName);

        Assertions.assertNotNull(entry, jarName);

        return entry;
    }

    private static @NotNull ModSource requireSource(@NotNull ModInstaller installer, @NotNull String jarName) throws IOException {
        ModSource source = installer.findSource(jarName);

        Assertions.assertNotNull(source, jarName);

        return source;
    }

    private static @NotNull String readVersion(@NotNull Path jar) {
        try {
            return ModManifestReader.read(jar).getManifest().getVersion().toString();
        } catch (ManifestException exception) {
            throw Exceptions.newAssertionError(exception, "The jar {0} has no valid manifest.", jar);
        }
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
        return createJar(directory, id, "1.0.0", engine);
    }

    private static byte @NotNull [] createJar(@NotNull Path directory, @NotNull String id, @NotNull String version,
                                              @NotNull String engine) throws IOException {
        Path jar = Files.createTempFile(directory, id, ".jar");

        TestJarUtils.write(jar, Map.of(TestJarUtils.MANIFEST_ENTRY, MANIFEST.formatted(id, version, engine).getBytes(StandardCharsets.UTF_8)));

        return Files.readAllBytes(jar);
    }
}
