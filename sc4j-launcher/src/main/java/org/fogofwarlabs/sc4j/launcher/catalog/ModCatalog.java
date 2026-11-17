package org.fogofwarlabs.sc4j.launcher.catalog;

import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.fogofwarlabs.sc4j.manifest.ModArchive;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.ModManifestReader;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.fogofwarlabs.sc4j.manifest.settings.SettingsSchema;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValueStore;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValuesException;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationProblem;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Lists the mod jars of an install ({@code <install>/mods/*.jar}) with their status, the way the launcher shows them.
 * <p>
 * Each jar is read with {@link ModManifestReader} only: its manifest and the images the manifest names. No class of a mod
 * is loaded and no mod code runs, so a broken jar cannot harm the launcher. A jar whose manifest is broken or invalid,
 * whose images are missing, whose engine version this release does not support, or whose id another jar also declares is
 * an {@link ModStatus#ERROR}. A readable mod whose saved settings ({@code userdata/settings/<modId>.json} and the shared
 * {@code userdata/shared-settings.json}) are missing, unreadable or fail the schema is a {@link ModStatus#WARNING}. Every other mod is
 * {@link ModStatus#READY}.
 * <p>
 * An empty directory setting that declares {@code detect} is filled from the computer's records, and the directory found is saved
 * ({@link SettingValueStore#resolve}).
 */
public final class ModCatalog {
    private static final @NotNull String JAR_PATTERN = "*.jar";
    private static final int MINIMUM_CONFLICTING_JARS = 2;

    /**
     * Collects what scanning one jar finds before the entries are created.
     */
    private static final class JarScan {
        private final @NotNull Path jar;
        private final @Nullable ModArchive archive;
        private final @NotNull List<@NotNull ModIssue> issues;

        /**
         * Creates a {@link JarScan} instance.
         *
         * @param jar     the mod jar
         * @param archive the archive read from the jar, or {@code null} when it is broken
         * @param issues  the issues found so far
         */
        private JarScan(@NotNull Path jar, @Nullable ModArchive archive, @NotNull List<@NotNull ModIssue> issues) {
            this.jar     = jar;
            this.archive = archive;
            this.issues  = issues;
        }

        private @NotNull String getJarName() {
            Path fileName = this.jar.getFileName();

            return fileName == null ? this.jar.toString() : fileName.toString();
        }
    }

    /**
     * Scans the mod jars of an install.
     *
     * @param install the install
     * @param records the computer's installation records, which fill an empty directory setting that declares {@code detect}
     * @return the created {@link ModCatalog} instance, empty when {@code mods/} does not exist
     * @throws IOException if the folder {@code mods/} exists but cannot be listed
     */
    public static @NotNull ModCatalog scan(@NotNull InstallLayout install, @NotNull InstallationRecords records) throws IOException {
        Path modsDirectory = install.getModsDirectory();

        if (!Files.isDirectory(modsDirectory)) {
            return createEmpty(modsDirectory);
        }

        List<JarScan> scans = new ArrayList<>();

        for (Path jar : listJars(modsDirectory)) {
            scans.add(readJar(jar));
        }

        addIdConflicts(scans);

        List<ModEntry> entries = new ArrayList<>(scans.size());

        for (JarScan scan : scans) {
            if (scan.archive != null && scan.issues.isEmpty()) {
                addSettingsIssues(install, records, scan.archive.getManifest(), scan.issues);
            }

            entries.add(new ModEntry(scan.jar, scan.archive, scan.issues));
        }

        return new ModCatalog(modsDirectory, entries);
    }

    /**
     * Creates a {@link ModCatalog} instance without mods, for a folder that could not be scanned.
     *
     * @param modsDirectory the folder {@code <install>/mods}
     * @return the created {@link ModCatalog} instance
     */
    public static @NotNull ModCatalog createEmpty(@NotNull Path modsDirectory) {
        return new ModCatalog(modsDirectory, List.of());
    }

    private static @NotNull List<@NotNull Path> listJars(@NotNull Path modsDirectory) throws IOException {
        List<Path> jars = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDirectory, JAR_PATTERN)) {
            for (Path jar : stream) {
                if (Files.isRegularFile(jar)) {
                    jars.add(jar);
                }
            }
        }

        jars.sort(Comparator.comparing((Path jar) -> String.valueOf(jar.getFileName()).toLowerCase(Locale.ROOT))
                            .thenComparing(jar -> String.valueOf(jar.getFileName())));

        return jars;
    }

    private static @NotNull JarScan readJar(@NotNull Path jar) {
        List<ModIssue> issues = new ArrayList<>();
        ModArchive archive;

        try {
            archive = ModManifestReader.read(jar);
        } catch (ManifestException exception) {
            for (ValidationProblem problem : exception.getResult().getProblems()) {
                issues.add(new ManifestIssue(problem));
            }

            return new JarScan(jar, null, issues);
        }

        for (ValidationProblem problem : archive.getManifest().checkEngine(EngineVersion.CURRENT).getProblems()) {
            issues.add(new ManifestIssue(problem));
        }

        return new JarScan(jar, archive, issues);
    }

    private static void addIdConflicts(@NotNull List<@NotNull JarScan> scans) {
        Map<String, List<JarScan>> scansById = new LinkedHashMap<>();

        for (JarScan scan : scans) {
            if (scan.archive != null) {
                scansById.computeIfAbsent(scan.archive.getManifest().getId(), id -> new ArrayList<>()).add(scan);
            }
        }

        for (Map.Entry<String, List<JarScan>> group : scansById.entrySet()) {
            if (group.getValue().size() < MINIMUM_CONFLICTING_JARS) {
                continue;
            }

            for (JarScan scan : group.getValue()) {
                List<String> otherJarNames = new ArrayList<>();

                for (JarScan other : group.getValue()) {
                    if (other != scan) {
                        otherJarNames.add(other.getJarName());
                    }
                }

                scan.issues.add(new IdConflictIssue(group.getKey(), scan.getJarName(), otherJarNames));
            }
        }
    }

    private static void addSettingsIssues(@NotNull InstallLayout install, @NotNull InstallationRecords records, @NotNull ModManifest manifest,
                                          @NotNull List<@NotNull ModIssue> issues) {
        SettingValueStore store = SettingValueStore.of(install, manifest);
        SettingsSchema schema = manifest.getSettings();
        Map<String, Object> savedValues;

        try {
            savedValues = store.resolve(records);
        } catch (SettingValuesException exception) {
            for (ValidationProblem problem : exception.getResult().getProblems()) {
                issues.add(new SettingsIssue(problem, null));
            }

            return;
        } catch (IOException exception) {
            ValidationProblem problem = new ValidationProblem(ProblemCode.VALUES_SYNTAX, store.getModFile().getPath().toString(),
                                                              String.valueOf(exception.getMessage()), null);

            issues.add(new SettingsIssue(problem, null));

            return;
        }

        ValidationResult result = schema.validate(savedValues);

        for (ValidationProblem problem : result.getProblems()) {
            issues.add(new SettingsIssue(problem, schema.findDefinition(problem.getSubject())));
        }
    }


    private final @NotNull Path modsDirectory;
    private final @NotNull List<@NotNull ModEntry> entries;

    /**
     * Creates a {@link ModCatalog} instance.
     *
     * @param modsDirectory the scanned folder
     * @param entries       the entries in list order
     */
    private ModCatalog(@NotNull Path modsDirectory, @NotNull List<@NotNull ModEntry> entries) {
        this.modsDirectory = modsDirectory;
        this.entries       = List.copyOf(entries);
    }

    /**
     * @return the scanned folder {@code <install>/mods}
     */
    public @NotNull Path getModsDirectory() {
        return this.modsDirectory;
    }

    /**
     * @return the entries, ordered by jar file name
     */
    public @NotNull List<@NotNull ModEntry> getEntries() {
        return this.entries;
    }

    /**
     * Finds the entry of a jar.
     *
     * @param jarName the jar file name, such as {@code my-mod.jar}
     * @return the entry, or {@code null} when the catalog has no such jar
     */
    public @Nullable ModEntry findByJarName(@NotNull String jarName) {
        for (ModEntry entry : this.entries) {
            if (entry.getJarName().equals(jarName)) {
                return entry;
            }
        }

        return null;
    }

    /**
     * Checks a jar that is to join {@code mods/} the way {@link #scan} checks the listed jars: a jar that cannot be read, a broken or invalid
     * manifest, a missing image and an engine version this release does not support are errors, and so is an id that a listed jar other than
     * the replaced one declares. Saved settings are not checked.
     *
     * @param jar             the jar to check
     * @param replacedJarName the file name of the listed jar the checked jar replaces, or {@code null} when it is added
     * @return the entry of the checked jar with the issues found; an {@link ModStatus#ERROR} entry must not join {@code mods/}
     */
    public @NotNull ModEntry checkNewJar(@NotNull Path jar, @Nullable String replacedJarName) {
        JarScan scan = readJar(jar);

        if (scan.archive != null) {
            String id = scan.archive.getManifest().getId();
            List<String> otherJarNames = new ArrayList<>();

            for (ModEntry entry : this.entries) {
                ModManifest manifest = entry.getManifest();

                if (manifest != null && manifest.getId().equals(id) && !entry.getJarName().equals(replacedJarName)) {
                    otherJarNames.add(entry.getJarName());
                }
            }

            if (!otherJarNames.isEmpty()) {
                scan.issues.add(new IdConflictIssue(id, scan.getJarName(), otherJarNames));
            }
        }

        return new ModEntry(scan.jar, scan.archive, scan.issues);
    }

    /**
     * Counts the entries with a status.
     *
     * @param status the status
     * @return the number of entries with the status
     */
    public int countStatus(@NotNull ModStatus status) {
        int count = 0;

        for (ModEntry entry : this.entries) {
            if (entry.getStatus() == status) {
                count++;
            }
        }

        return count;
    }
}
