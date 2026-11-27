package org.fogofwarlabs.sc4j.launcher.translation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.translate.Translation;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.fogofwarlabs.sc4j.translate.TranslationStatus;
import org.fogofwarlabs.sc4j.translate.Translator;
import org.fogofwarlabs.sc4j.translate.service.PresetInstallation;
import org.fogofwarlabs.sc4j.translate.service.TranslationService;
import org.fogofwarlabs.sc4j.translate.service.TranslationStorage;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationDevice;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationModelOptions;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationPreset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the launcher's translation: one {@link TranslationService} for the chosen preset on the chosen device while the preset is installed,
 * which translates the launcher's chat and, over the launcher link, the chat of the runtimes the launcher starts. The service loads its
 * models on its first request; without it every request answers {@link TranslationStatus#UNAVAILABLE}. The presets are downloaded
 * ({@link PresetDownload}) and deleted here, only when the user asks; a preset that finishes its download while it is chosen gets its service
 * then. Choosing another device replaces the service.
 */
public final class LauncherTranslation implements Translator, Closeable {
    private static final @NotNull Logger LOGGER = Logger.of(LauncherTranslation.class);
    private static final @NotNull String CLOSE_THREAD_NAME = "launcher-translation-close";

    private final @NotNull TranslationStorage storage;
    private final @NotNull List<@NotNull TranslationPreset> presets;
    private final @NotNull Map<@NotNull String, @NotNull PresetInstallation> installations;
    private final @NotNull Map<@NotNull String, @NotNull PresetDownload> downloads;

    private @Nullable String chosenPreset;
    private @NotNull TranslationDevice device;
    private volatile @Nullable TranslationService service;
    private boolean closed;

    /**
     * Creates a {@link LauncherTranslation} instance without a chosen preset, on {@link TranslationDevice#AUTOMATIC}.
     *
     * @param storage the translation storage of the install
     * @param presets the presets offered for download
     */
    public LauncherTranslation(@NotNull TranslationStorage storage, @NotNull List<@NotNull TranslationPreset> presets) {
        this.storage       = storage;
        this.presets       = List.copyOf(presets);
        this.installations = new LinkedHashMap<>();
        this.downloads     = new ConcurrentHashMap<>();

        this.chosenPreset = null;
        this.device       = TranslationDevice.AUTOMATIC;
        this.service      = null;
        this.closed       = false;

        for (TranslationPreset preset : this.presets) {
            this.installations.put(preset.getId(), new PresetInstallation(preset, storage.getPresetFolder(preset)));
        }
    }

    /**
     * @return the presets offered for download, in the order they are shown; unmodifiable
     */
    public @NotNull List<@NotNull TranslationPreset> getPresets() {
        return this.presets;
    }

    /**
     * Finds an offered preset by its id.
     *
     * @param id the preset id
     * @return the preset, or {@code null} when no offered preset has the id
     */
    public @Nullable TranslationPreset findPreset(@NotNull String id) {
        for (TranslationPreset preset : this.presets) {
            if (preset.getId().equals(id)) {
                return preset;
            }
        }

        return null;
    }

    /**
     * @return the id of the chosen preset, or {@code null} for none; the preset may not be installed
     */
    public synchronized @Nullable String getChosenPreset() {
        return this.chosenPreset;
    }

    /**
     * @return the device the translation model runs on
     */
    public synchronized @NotNull TranslationDevice getDevice() {
        return this.device;
    }

    /**
     * @return the id of the preset whose service translates, chosen and installed, or {@code null} while the launcher does not translate
     */
    public @Nullable String getActivePresetId() {
        TranslationService active = this.service;

        return active == null ? null : active.getPreset().getId();
    }

    /**
     * Tells whether all files of a preset are installed.
     *
     * @param preset the preset, one of {@link #getPresets()}
     * @return {@code true} if the preset is installed, {@code false} otherwise
     */
    public boolean isInstalled(@NotNull TranslationPreset preset) {
        return this.getInstallation(preset).isInstalled();
    }

    /**
     * Returns the last download of a preset.
     *
     * @param preset the preset
     * @return the running or the last ended download of the preset in this launcher run, or {@code null} for none
     */
    public @Nullable PresetDownload getDownload(@NotNull TranslationPreset preset) {
        return this.downloads.get(preset.getId());
    }

    /**
     * Chooses the preset the launcher translates with and starts its service when it is installed; the service of another preset is closed.
     *
     * @param presetId the id of the preset, or {@code null} to translate nothing
     */
    public synchronized void choose(@Nullable String presetId) {
        this.chosenPreset = presetId;
        this.updateService(false);
    }

    /**
     * Chooses the device the translation model runs on; a running service of another device is replaced.
     *
     * @param device the device
     */
    public synchronized void setDevice(@NotNull TranslationDevice device) {
        this.device = device;
        this.updateService(false);
    }

    /**
     * Starts the download of a preset's missing files, unless it runs already. The chosen preset gets its service once its download completes.
     *
     * @param preset the preset, one of {@link #getPresets()}
     * @return the running download
     */
    public synchronized @NotNull PresetDownload download(@NotNull TranslationPreset preset) {
        PresetDownload running = this.downloads.get(preset.getId());

        if (running != null && running.isRunning()) {
            return running;
        }

        PresetDownload started = new PresetDownload(this.getInstallation(preset), this::onDownloadEnded);

        this.downloads.put(preset.getId(), started);
        started.start();

        return started;
    }

    /**
     * Deletes the installed files of a preset; its service is closed first, so that no model holds them.
     *
     * @param preset the preset, one of {@link #getPresets()}
     * @throws IOException           if a file cannot be deleted
     * @throws IllegalStateException if the preset is being downloaded
     */
    public void delete(@NotNull TranslationPreset preset) throws IOException {
        TranslationService closing = null;

        synchronized (this) {
            PresetDownload running = this.downloads.get(preset.getId());

            if (running != null && running.isRunning()) {
                throw Exceptions.newIllegalStateException("The preset {0} is being downloaded.", preset.getId());
            }

            TranslationService active = this.service;

            if (active != null && active.getPreset().getId().equals(preset.getId())) {
                closing      = active;
                this.service = null;
            }

            this.downloads.remove(preset.getId());
        }

        if (closing != null) {
            closing.close();
        }

        this.getInstallation(preset).delete();
        LOGGER.info("TRANSLATION: the preset {0} is deleted", preset.getId());
    }

    /**
     * Translates a text.
     *
     * @param request the request
     * @return the future of the translation, completed on a worker thread or at once
     */
    @Override
    public @NotNull CompletableFuture<@NotNull Translation> translate(@NotNull TranslationRequest request) {
        TranslationService active = this.service;

        return active == null ? Translator.unavailable().translate(request) : active.translate(request);
    }

    private @NotNull PresetInstallation getInstallation(@NotNull TranslationPreset preset) {
        PresetInstallation installation = this.installations.get(preset.getId());

        if (installation == null) {
            throw Exceptions.newIllegalArgumentException("The preset {0} is not offered.", preset.getId());
        }

        return installation;
    }

    private synchronized void onDownloadEnded() {
        this.updateService(false);
    }

    /**
     * Gives the chosen preset its service on the chosen device when it is installed and not being downloaded, and closes the service of
     * another preset or device.
     *
     * @param waitForClose whether a replaced service is closed on this thread, else on a thread of its own
     */
    private synchronized void updateService(boolean waitForClose) {
        TranslationPreset chosen = this.chosenPreset == null || this.closed ? null : this.findPreset(this.chosenPreset);
        PresetDownload running = chosen == null ? null : this.downloads.get(chosen.getId());
        TranslationPreset wanted = chosen != null && (running == null || !running.isRunning()) && this.isInstalled(chosen) ? chosen : null;
        TranslationService current = this.service;

        if (current != null && wanted != null && current.getPreset().getId().equals(wanted.getId()) &&
            current.getOptions().getDevice() == this.device || current == null && wanted == null) {
            return;
        }

        this.service = wanted == null ? null : new TranslationService(wanted, new TranslationModelOptions(this.device), this.storage);
        LOGGER.info("TRANSLATION: {0}", wanted == null ? "none" : wanted.getId() + ", device " + this.device);

        if (current == null) {
            return;
        }

        if (waitForClose) {
            current.close();
        } else {
            Thread closer = new Thread(current::close, CLOSE_THREAD_NAME);

            closer.setDaemon(true);
            closer.start();
        }
    }

    /**
     * Cancels the running downloads and closes the service.
     */
    @Override
    public void close() {
        synchronized (this) {
            this.closed = true;
        }

        for (PresetDownload running : this.downloads.values()) {
            running.cancel();
        }

        this.updateService(true);
    }
}
