package org.fogofwarlabs.sc4j.launcher.settings;

import org.fogofwarlabs.sc4j.catalog.ReleaseCheck;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationDevice;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the launcher's own settings, {@code userdata/launcher.json}: the UI language chosen on the Settings screen, the mod selected last,
 * the window state, the update repository, the launcher version the player chose to skip, whether the chat sound is muted, the
 * translation preset chosen for the chat and the device it runs on, and whether games run on the high-performance GPU. Instances are
 * immutable; each {@code with} method creates a changed copy.
 */
public final class LauncherSettings {
    /**
     * The GitHub repository whose releases update the install while the settings name no other.
     */
    public static final @NotNull String DEFAULT_UPDATE_REPOSITORY = ReleaseCheck.DEFAULT_REPOSITORY.toString();

    /**
     * The settings before anything is saved: the system language, no last mod, the default window, the default update repository, no
     * skipped version, the chat sound on, no translation preset, the automatic translation device and games on the high-performance GPU.
     */
    public static final @NotNull LauncherSettings DEFAULT = new LauncherSettings(null, null, null, null, null, false, null, TranslationDevice.AUTOMATIC,
                                                                                 true);

    private final @Nullable UiLanguage language;
    private final @Nullable String lastMod;
    private final @Nullable WindowState window;
    private final @Nullable String updateRepository;
    private final @Nullable String skippedVersion;
    private final boolean chatSoundMuted;
    private final @Nullable String translationPreset;
    private final @NotNull TranslationDevice translationDevice;
    private final boolean highPerformanceGpu;

    /**
     * Creates a {@link LauncherSettings} instance.
     *
     * @param language           the UI language chosen on the Settings screen, or {@code null} to follow the operating system
     * @param lastMod            the file name of the mod jar selected last, such as {@code my-mod.jar}, or {@code null}
     * @param window             the window state, or {@code null} for the default window
     * @param updateRepository   the update repository {@code owner/name}, or {@code null} for {@link #DEFAULT_UPDATE_REPOSITORY}
     * @param skippedVersion     the launcher version the player chose to skip, such as {@code 1.2.0}, or {@code null}
     * @param chatSoundMuted     whether the chat notification sound is muted
     * @param translationPreset  the id of the translation preset chosen for the chat, or {@code null} for none
     * @param translationDevice  the device the translation model runs on
     * @param highPerformanceGpu whether games run on the high-performance GPU
     */
    public LauncherSettings(@Nullable UiLanguage language, @Nullable String lastMod, @Nullable WindowState window, @Nullable String updateRepository,
                            @Nullable String skippedVersion, boolean chatSoundMuted, @Nullable String translationPreset,
                            @NotNull TranslationDevice translationDevice, boolean highPerformanceGpu) {
        this.language           = language;
        this.lastMod            = lastMod;
        this.window             = window;
        this.updateRepository   = updateRepository;
        this.skippedVersion     = skippedVersion;
        this.chatSoundMuted     = chatSoundMuted;
        this.translationPreset  = translationPreset;
        this.translationDevice  = translationDevice;
        this.highPerformanceGpu = highPerformanceGpu;
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
     * @return {@code true} if the chat notification sound is muted, {@code false} otherwise
     */
    public boolean isChatSoundMuted() {
        return this.chatSoundMuted;
    }

    /**
     * @return the id of the translation preset chosen for the chat, or {@code null} for none
     */
    public @Nullable String getTranslationPreset() {
        return this.translationPreset;
    }

    /**
     * @return the device the translation model runs on
     */
    public @NotNull TranslationDevice getTranslationDevice() {
        return this.translationDevice;
    }

    /**
     * @return {@code true} if games run on the high-performance GPU, {@code false} otherwise
     */
    public boolean isHighPerformanceGpu() {
        return this.highPerformanceGpu;
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
        return new LauncherSettings(language, this.lastMod, this.window, this.updateRepository, this.skippedVersion, this.chatSoundMuted,
                                    this.translationPreset, this.translationDevice, this.highPerformanceGpu);
    }

    /**
     * Creates a copy with another last mod.
     *
     * @param lastMod the file name of the mod jar selected last, or {@code null}
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withLastMod(@Nullable String lastMod) {
        return new LauncherSettings(this.language, lastMod, this.window, this.updateRepository, this.skippedVersion, this.chatSoundMuted,
                                    this.translationPreset, this.translationDevice, this.highPerformanceGpu);
    }

    /**
     * Creates a copy with another window state.
     *
     * @param window the window state, or {@code null} for the default window
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withWindow(@Nullable WindowState window) {
        return new LauncherSettings(this.language, this.lastMod, window, this.updateRepository, this.skippedVersion, this.chatSoundMuted,
                                    this.translationPreset, this.translationDevice, this.highPerformanceGpu);
    }

    /**
     * Creates a copy with another update repository; the default repository is saved as none, so that the settings follow the default.
     *
     * @param updateRepository the update repository {@code owner/name}, or {@code null} for {@link #DEFAULT_UPDATE_REPOSITORY}
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withUpdateRepository(@Nullable String updateRepository) {
        String savedRepository = DEFAULT_UPDATE_REPOSITORY.equals(updateRepository) ? null : updateRepository;

        return new LauncherSettings(this.language, this.lastMod, this.window, savedRepository, this.skippedVersion, this.chatSoundMuted,
                                    this.translationPreset, this.translationDevice, this.highPerformanceGpu);
    }

    /**
     * Creates a copy with another skipped version.
     *
     * @param skippedVersion the launcher version the player chose to skip, or {@code null} for none
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withSkippedVersion(@Nullable ReleaseVersion skippedVersion) {
        String savedVersion = skippedVersion == null ? null : skippedVersion.toString();

        return new LauncherSettings(this.language, this.lastMod, this.window, this.updateRepository, savedVersion, this.chatSoundMuted,
                                    this.translationPreset, this.translationDevice, this.highPerformanceGpu);
    }

    /**
     * Creates a copy with the chat notification sound muted or on.
     *
     * @param chatSoundMuted whether the chat notification sound is muted
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withChatSoundMuted(boolean chatSoundMuted) {
        return new LauncherSettings(this.language, this.lastMod, this.window, this.updateRepository, this.skippedVersion, chatSoundMuted,
                                    this.translationPreset, this.translationDevice, this.highPerformanceGpu);
    }

    /**
     * Creates a copy with another translation preset.
     *
     * @param translationPreset the id of the translation preset chosen for the chat, or {@code null} for none
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withTranslationPreset(@Nullable String translationPreset) {
        return new LauncherSettings(this.language, this.lastMod, this.window, this.updateRepository, this.skippedVersion, this.chatSoundMuted,
                                    translationPreset, this.translationDevice, this.highPerformanceGpu);
    }

    /**
     * Creates a copy with another translation device.
     *
     * @param translationDevice the device the translation model runs on
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withTranslationDevice(@NotNull TranslationDevice translationDevice) {
        return new LauncherSettings(this.language, this.lastMod, this.window, this.updateRepository, this.skippedVersion, this.chatSoundMuted,
                                    this.translationPreset, translationDevice, this.highPerformanceGpu);
    }

    /**
     * Creates a copy with games on the high-performance GPU or not.
     *
     * @param highPerformanceGpu whether games run on the high-performance GPU
     * @return the created {@link LauncherSettings} instance
     */
    public @NotNull LauncherSettings withHighPerformanceGpu(boolean highPerformanceGpu) {
        return new LauncherSettings(this.language, this.lastMod, this.window, this.updateRepository, this.skippedVersion, this.chatSoundMuted,
                                    this.translationPreset, this.translationDevice, highPerformanceGpu);
    }
}
