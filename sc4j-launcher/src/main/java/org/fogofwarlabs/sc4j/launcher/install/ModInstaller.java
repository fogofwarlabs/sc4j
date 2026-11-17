package org.fogofwarlabs.sc4j.launcher.install;

import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.mods.ModRelease;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.catalog.ModStatus;
import org.fogofwarlabs.sc4j.launcher.update.UpdateFolder;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Installs, updates and removes mods from the releases of GitHub repositories. A mod release carries the mod jar {@code <id>.jar} and its
 * checksum file {@code <id>.jar.sha256}.
 * <p>
 * A downloaded jar waits in the update folder ({@link UpdateFolder#getModsDirectory()}) until it is checked: its checksum must match, and
 * the launcher's own checks ({@link ModCatalog#checkNewJar}) must pass: a valid manifest and images, an engine version this launcher supports
 * and an id that no other jar of {@code mods/} declares. A refused jar never joins {@code mods/}. The repository and release of each
 * installed mod are recorded in {@code userdata/mod-sources.json} ({@link ModSourcesFile}), so that newer releases can be found
 * ({@link #findUpdates}); removing a mod removes its record.
 */
public final class ModInstaller {
    private static final @NotNull String SOURCES_FILE = "mod-sources.json";
    private static final @NotNull String JAR_EXTENSION = ".jar";
    private static final long MAXIMUM_JAR_SIZE = 1024L * 1024 * 1024;

    private final @NotNull InstallLayout install;
    private final @NotNull ReleaseClient client;
    private final @NotNull ModSourcesFile sourcesFile;
    private final @NotNull Path downloadDirectory;

    /**
     * Creates a {@link ModInstaller} instance.
     *
     * @param install the install
     * @param client  reads the releases
     */
    public ModInstaller(@NotNull InstallLayout install, @NotNull ReleaseClient client) {
        this.install           = install;
        this.client            = client;
        this.sourcesFile       = new ModSourcesFile(install.getUserDataDirectory().resolve(SOURCES_FILE));
        this.downloadDirectory = UpdateFolder.of(install).getModsDirectory();
    }

    /**
     * @return the record of the mods installed from releases
     */
    public @NotNull ModSourcesFile getSourcesFile() {
        return this.sourcesFile;
    }

    /**
     * @return the reader of the releases
     */
    public @NotNull ReleaseClient getClient() {
        return this.client;
    }

    /**
     * Downloads the jar of a mod release into the update folder, kept only when its checksum matches.
     *
     * @param release  the mod release
     * @param progress receives the share downloaded, from {@code 0} to {@code 1}, on the downloading thread
     * @return the downloaded jar, still outside {@code mods/}
     * @throws IOException if the jar cannot be downloaded, does not match its checksum or cannot be written
     */
    public @NotNull Path download(@NotNull ModRelease release, @NotNull DoubleConsumer progress) throws IOException {
        Path target = this.downloadDirectory.resolve(release.getJarFile().getName());

        this.client.download(release.getJarFile(), release.getChecksumFile(), target, MAXIMUM_JAR_SIZE, progress);

        return target;
    }

    /**
     * Installs a downloaded jar when it passes the launcher's checks: as {@code mods/<id>.jar}, or in place of the jar it replaces, and records
     * its repository and release. A refused jar is deleted.
     *
     * @param release    the release the jar was downloaded from
     * @param downloaded the downloaded jar ({@link #download})
     * @param catalog    the mods of the install, for the id check
     * @param replaced   the installed mod the jar replaces, or {@code null} when it is added
     * @return the installed jar, or the reasons it was refused
     * @throws IOException if the jar cannot be moved into {@code mods/}, a jar of that name exists already, or the record cannot be written
     */
    public @NotNull ModInstallResult install(@NotNull ModRelease release, @NotNull Path downloaded, @NotNull ModCatalog catalog,
                                             @Nullable ModEntry replaced) throws IOException {
        ModEntry checked = catalog.checkNewJar(downloaded, replaced == null ? null : replaced.getJarName());
        ModManifest manifest = checked.getManifest();

        if (checked.getStatus() == ModStatus.ERROR || manifest == null) {
            Files.deleteIfExists(downloaded);

            return new ModInstallResult(null, checked.getIssues());
        }

        Path modsDirectory = this.install.getModsDirectory();
        Path target = replaced != null ? replaced.getJar() : modsDirectory.resolve(manifest.getId() + JAR_EXTENSION);
        String jarName = String.valueOf(target.getFileName());

        Files.createDirectories(modsDirectory);

        if (replaced != null) {
            Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(downloaded, target);
        }

        this.record(jarName, new ModSource(jarName, release.getRepository(), release.getTag()));

        return new ModInstallResult(jarName, List.of());
    }

    /**
     * Removes a mod: deletes its jar and its record. Its settings, storage and logs in {@code userdata/} stay.
     *
     * @param entry the mod
     * @throws IOException if the jar cannot be deleted, such as while the mod runs, or the record cannot be written
     */
    public void remove(@NotNull ModEntry entry) throws IOException {
        Files.deleteIfExists(entry.getJar());
        this.record(entry.getJarName(), null);
    }

    /**
     * Finds the installed mods with a newer release: each recorded mod that the catalog lists is checked against the latest release of its
     * repository. A newer release is one with a higher version, or, when a tag is not a version, any other tag. A repository that cannot be
     * read is left out quietly.
     *
     * @param catalog the mods of the install
     * @return the newer release of each such mod by its jar file name
     * @throws IOException if the record cannot be read
     */
    public @NotNull Map<@NotNull String, @NotNull ModRelease> findUpdates(@NotNull ModCatalog catalog) throws IOException {
        Map<String, ModRelease> updates = new LinkedHashMap<>();

        for (ModSource source : this.sourcesFile.read()) {
            if (catalog.findByJarName(source.getJarName()) == null) {
                continue;
            }

            ModRelease latest;

            try {
                latest = ModRelease.find(this.client, source.getRepository(), source.getJarName());
            } catch (ReleaseException exception) {
                continue;
            }

            ReleaseVersion latestVersion = ReleaseVersion.parse(latest.getTag());
            ReleaseVersion installedVersion = ReleaseVersion.parse(source.getRelease());
            boolean newer = latestVersion != null && installedVersion != null ? latestVersion.isNewerThan(installedVersion)
                                                                              : !latest.getTag().equals(source.getRelease());

            if (newer) {
                updates.put(source.getJarName(), latest);
            }
        }

        return updates;
    }

    private void record(@NotNull String jarName, @Nullable ModSource source) throws IOException {
        List<ModSource> sources = new ArrayList<>();
        boolean changed = source != null;

        for (ModSource recorded : this.sourcesFile.read()) {
            if (recorded.getJarName().equals(jarName)) {
                changed = true;
            } else {
                sources.add(recorded);
            }
        }

        if (source != null) {
            sources.add(source);
        }

        if (changed) {
            this.sourcesFile.write(sources);
        }
    }
}
