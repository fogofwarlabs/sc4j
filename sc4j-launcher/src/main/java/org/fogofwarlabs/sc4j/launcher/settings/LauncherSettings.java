package org.fogofwarlabs.sc4j.launcher.settings;

import org.fogofwarlabs.sc4j.catalog.ReleaseCheck;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the launcher's own settings, {@code userdata/launcher.json}: the UI language chosen on the Settings screen, the mod selected last,
 * the window state, the update repository and the launcher version the player chose to skip. Instances are immutable; each {@code with}
 * method creates a changed copy.
 */
public final class LauncherSettings {
    /**
     * The GitHub repository whose releases update the install while the settings name no other.
     */
    public static final @NotNull String DEFAULT_UPDATE_REPOSITORY = ReleaseCheck.DEFAULT_REPOSITORY.toString();

    /**
     * The settings before anything is saved: the system language, no last mod, the default window, the default update repository and no
     * skipped version.
     */
    public static final @NotNull LauncherSettings DEFAULT = new LauncherSettings(null, null, null, null, null);

    private final @Nullable UiLanguage language;
    private final @Nullable String lastMod;
    private final @Nullable WindowState window;
    private final @Nullable String updateRepository;
    private final @Nullable String skippedVersion;

    /**
     * Creates a {@link LauncherSettings} instance.
     *
     * @param language         the UI language chosen on the Settings screen, or {@code null} to follow the operating system
     * @param lastMod          the file name of the mod jar selected last, such as {@code my-mod.jar}, or {@code null}
     * @param window           the window state, or {@code null} for the default window
     * @param updateRepository the update repository {@code owner/name}, or {@code null} for {@link #DEFAULT_UPDATE_REPOSITORY}
     * @param skippedVersion   the launcher version the player chose to skip, such as {@code 1.2.0}, or {@code null}
     */
    public LauncherSettings(@Nullable UiLanguage language, @Nullable String lastMod, @Nullable WindowState window, @Nullable String updateRepository,
                            @Nullable String skippedVersion) {
        this.language         = language;
        this.lastMod          = lastMod;
        this.window           = window;
        this.updateRepository = updateRepository;
        this.skippedVersion   = skippedVersion;
    }

    /**
     * @return the UI language chosen on the Settings screen, or {@code null} to follow the operating system
     */
    public @Nullable UiLanguage getLanguage() {
        return this.language;
    }

    /**
     * @return the file name of the mod jar selected last, or {@code null}
     */
    public @Nullable String getLastMod() {
        return this.lastMod;
    }

    /**
     * @return the window state, or {@code null} for the default window
     */
    public @Nullable WindowState getWindow() {
        return this.window;
    }

    /**
     * @return the update repository {@code owner/name} the settings name, or {@code null} when they name none
     */
    public @Nullable String getSavedUpdateRepository() {
        return this.updateRepository;
    }

    /**
     * @return the update repository {@code owner/name}: the one the settings name, else {@link #DEFAULT_UPDATE_REPOSITORY}
     */
    public @NotNull String getUpdateRepository() {
        return this.updateRepository == null ? DEFAULT_UPDATE_REPOSITORY : this.updateRepository;
    }

    /**
     * @return the launcher version the player chose to skip, such as {@code 1.2.0}, or {@code null}
     */
    public @Nullable String getSkippedVersion() {
        return this.skippedVersion;
    }

    /**
     * Tells whether the player chose to skip a launcher version.
     *
     * @param version the version
     * @return {@code true} if the version is the skipped version, {@code false} otherwise
     */
    public boolean isSkipped(@NotNull ReleaseVersion version) {
        return version.toString().equals(this.skippedVersion);
    }

    /**
     * Creates a copy with another UI language.
     *
     * @param language the UI language, or {@code null} to follow the operating system
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withLanguage(@Nullable UiLanguage language) {
        return new LauncherSettings(language, this.lastMod, this.window, this.updateRepository, this.skippedVersion);
    }

    /**
     * Creates a copy with another last mod.
     *
     * @param lastMod the file name of the mod jar selected last, or {@code null}
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withLastMod(@Nullable String lastMod) {
        return new LauncherSettings(this.language, lastMod, this.window, this.updateRepository, this.skippedVersion);
    }

    /**
     * Creates a copy with another window state.
     *
     * @param window the window state, or {@code null} for the default window
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withWindow(@Nullable WindowState window) {
        return new LauncherSettings(this.language, this.lastMod, window, this.updateRepository, this.skippedVersion);
    }

    /**
     * Creates a copy with another update repository; the default repository is saved as none, so that the settings follow the default.
     *
     * @param updateRepository the update repository {@code owner/name}, or {@code null} for {@link #DEFAULT_UPDATE_REPOSITORY}
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withUpdateRepository(@Nullable String updateRepository) {
        String savedRepository = DEFAULT_UPDATE_REPOSITORY.equals(updateRepository) ? null : updateRepository;

        return new LauncherSettings(this.language, this.lastMod, this.window, savedRepository, this.skippedVersion);
    }

    /**
     * Creates a copy with another skipped version.
     *
     * @param skippedVersion the launcher version the player chose to skip, or {@code null} for none
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withSkippedVersion(@Nullable ReleaseVersion skippedVersion) {
        String savedVersion = skippedVersion == null ? null : skippedVersion.toString();

        return new LauncherSettings(this.language, this.lastMod, this.window, this.updateRepository, savedVersion);
    }
}
