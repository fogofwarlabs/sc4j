package org.fogofwarlabs.sc4j.manifest.settings;

import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines a {@code directory} setting: the value must name an existing directory that holds every relative path of
 * {@code check.contains} and, when {@code check.containsAny} lists paths, at least one of them (for example the program file of each
 * platform's build of a game).
 * <p>
 * A directory setting may also declare where a directory of its kind is usually recorded on the computer ({@code detect}): the names of
 * uninstall entries ({@code uninstallEntries}) and folder names below the program folders ({@code programFolders}); an empty value is
 * filled with the first recorded directory that passes the checks ({@link #detect}). With {@code shared} the value is stored once under
 * that name for every mod that declares the same name, instead of once per mod.
 */
public final class DirectorySetting extends SettingDefinition {
    private static final @NotNull String PATH_LIST_SEPARATOR = ", ";

    private final @NotNull List<@NotNull String> containedPaths;
    private final @NotNull List<@NotNull String> anyContainedPaths;

    private final @NotNull List<@NotNull String> uninstallEntries;
    private final @NotNull List<@NotNull String> programFolders;

    private final @Nullable String sharedName;

    /**
     * Creates a {@link DirectorySetting} instance.
     *
     * @param key               the key of the value in the settings value file
     * @param label             the label of the launcher's form field
     * @param description       the longer description, or {@code null}
     * @param required          whether a value (or a default) must exist before the mod runs
     * @param defaultValue      the directory used when none is saved, or {@code null}
     * @param message           the localized message the launcher shows when a check fails, or {@code null} for the generic one
     * @param containedPaths    the relative paths, with {@code /} separators, that must exist in the directory
     * @param anyContainedPaths the relative paths, with {@code /} separators, of which one must exist in the directory; empty for no check
     * @param uninstallEntries  the names of the uninstall entries whose install location is a directory of this kind
     * @param programFolders    the folder names below the program folders, with {@code /} separators, of a directory of this kind
     * @param sharedName        the name under which mods share one value, or {@code null} for a value of this mod only
     */
    public DirectorySetting(@NotNull String key, @NotNull LocalizedText label, @Nullable LocalizedText description,
                            boolean required, @Nullable Path defaultValue, @Nullable LocalizedText message,
                            @NotNull List<@NotNull String> containedPaths, @NotNull List<@NotNull String> anyContainedPaths,
                            @NotNull List<@NotNull String> uninstallEntries, @NotNull List<@NotNull String> programFolders,
                            @Nullable String sharedName) {
        super(key, label, description, required, defaultValue, message);

        this.containedPaths    = List.copyOf(containedPaths);
        this.anyContainedPaths = List.copyOf(anyContainedPaths);

        this.uninstallEntries = List.copyOf(uninstallEntries);
        this.programFolders   = List.copyOf(programFolders);

        this.sharedName = sharedName;
    }

    /**
     * @return the type of the setting
     */
    @Override
    public @NotNull SettingType getType() {
        return SettingType.DIRECTORY;
    }

    /**
     * Returns the name under which mods share one value of the setting ({@code shared}); only a {@code directory} setting declares one.
     *
     * @return the shared name, or {@code null} when the value belongs to this mod only
     */
    @Override
    public @Nullable String getSharedName() {
        return this.sharedName;
    }

    /**
     * @return the relative paths, with {@code /} separators, that must exist in the directory ({@code check.contains})
     */
    public @NotNull List<@NotNull String> getContainedPaths() {
        return this.containedPaths;
    }

    /**
     * @return the relative paths, with {@code /} separators, of which one must exist in the directory ({@code check.containsAny}), or none
     */
    public @NotNull List<@NotNull String> getAnyContainedPaths() {
        return this.anyContainedPaths;
    }

    /**
     * @return the names of the uninstall entries whose install location is a directory of this kind ({@code detect.uninstallEntries})
     */
    public @NotNull List<@NotNull String> getUninstallEntries() {
        return this.uninstallEntries;
    }

    /**
     * @return the folder names below the program folders of a directory of this kind ({@code detect.programFolders})
     */
    public @NotNull List<@NotNull String> getProgramFolders() {
        return this.programFolders;
    }

    /**
     * Detects a directory of this kind where the computer records it: the install locations of the uninstall entries first, then the folders
     * below the program folders. Only recorded locations are looked at; no drive is searched.
     *
     * @param records the computer's installation records
     * @return the first recorded directory that passes the checks, or {@code null} when none does or the setting declares no {@code detect}
     */
    public @Nullable Path detect(@NotNull InstallationRecords records) {
        List<Path> candidates = new ArrayList<>();

        for (String uninstallEntry : this.uninstallEntries) {
            candidates.addAll(records.findUninstallLocations(uninstallEntry));
        }

        for (Path programDirectory : records.getProgramDirectories()) {
            for (String programFolder : this.programFolders) {
                candidates.add(programDirectory.resolve(programFolder));
            }
        }

        for (Path candidate : candidates) {
            ValidationResult.Builder problems = new ValidationResult.Builder();

            this.check(candidate, problems);

            if (problems.isValid()) {
                return candidate.toAbsolutePath().normalize();
            }
        }

        return null;
    }

    /**
     * Runs the type's declarative checks on a value.
     *
     * @param value    the value, of the type's Java type
     * @param problems receives a problem for each failed check, with this setting's {@link #getMessage() message}
     */
    @Override
    void check(@NotNull Object value, @NotNull ValidationResult.Builder problems) {
        Path directory = (Path) value;

        if (!Files.isDirectory(directory)) {
            problems.addCheckFailure(ProblemCode.VALUE_DIRECTORY, this.getKey(), this.getMessage(), "Not a directory: {0}", directory);

            return;
        }

        for (String containedPath : this.containedPaths) {
            if (!Files.exists(directory.resolve(containedPath))) {
                problems.addCheckFailure(ProblemCode.VALUE_CONTAINS, this.getKey(), this.getMessage(),
                                         "The directory {0} does not contain {1}.", directory, containedPath);
            }
        }

        if (this.anyContainedPaths.isEmpty()) {
            return;
        }

        for (String anyContainedPath : this.anyContainedPaths) {
            if (Files.exists(directory.resolve(anyContainedPath))) {
                return;
            }
        }

        problems.addCheckFailure(ProblemCode.VALUE_CONTAINS_ANY, this.getKey(), this.getMessage(), "The directory {0} contains none of {1}.",
                                 directory, String.join(PATH_LIST_SEPARATOR, this.anyContainedPaths));
    }
}
