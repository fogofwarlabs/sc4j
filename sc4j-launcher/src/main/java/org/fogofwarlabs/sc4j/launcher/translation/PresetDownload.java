package org.fogofwarlabs.sc4j.launcher.translation;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.translate.service.PresetInstallation;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationPreset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * Downloads the missing files of one translation preset on a thread of its own ({@link PresetInstallation#install}) and holds its progress
 * and outcome for the Settings screen, which reads them from the user interface thread.
 */
public final class PresetDownload {
    private static final @NotNull Logger LOGGER = Logger.of(PresetDownload.class);
    private static final @NotNull String THREAD_NAME = "launcher-translation-download";

    private final @NotNull PresetInstallation installation;
    private final @NotNull Runnable finished;

    private volatile long installedBytes;
    private volatile long totalBytes;
    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile @Nullable String failure;

    /**
     * Creates a {@link PresetDownload} instance that has not started.
     *
     * @param installation the installation of the preset
     * @param finished     called on the download thread when the download ends, completed, cancelled or failed
     */
    PresetDownload(@NotNull PresetInstallation installation, @NotNull Runnable finished) {
        this.installation = installation;
        this.finished     = finished;

        this.installedBytes = 0L;
        this.totalBytes     = installation.getTotalSize();
        this.running        = false;
        this.cancelled      = false;
        this.failure        = null;
    }

    /**
     * @return the preset
     */
    public @NotNull TranslationPreset getPreset() {
        return this.installation.getPreset();
    }

    /**
     * @return the bytes of the preset's files installed so far, the files that were already there included
     */
    public long getInstalledBytes() {
        return this.installedBytes;
    }

    /**
     * @return the size of all files of the preset in bytes
     */
    public long getTotalBytes() {
        return this.totalBytes;
    }

    /**
     * @return {@code true} if the download runs, {@code false} once it ended
     */
    public boolean isRunning() {
        return this.running;
    }

    /**
     * @return {@code true} if the download was cancelled, {@code false} otherwise
     */
    public boolean isCancelled() {
        return this.cancelled;
    }

    /**
     * @return why the download failed, or {@code null} while it runs and when it completed or was cancelled
     */
    public @Nullable String getFailure() {
        return this.failure;
    }

    /**
     * Starts the download on a daemon thread.
     */
    void start() {
        Thread thread = new Thread(this::run, THREAD_NAME);

        this.running = true;
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stops the download after the current part; the partial file is deleted, the complete files stay.
     */
    public void cancel() {
        this.cancelled = true;
        this.installation.cancel();
    }

    private void run() {
        try {
            this.installation.install((installed, total) -> {
                this.installedBytes = installed;
                this.totalBytes     = total;

                // A cancel that came before the installation started is passed on here.
                if (this.cancelled) {
                    this.installation.cancel();
                }
            });
            LOGGER.info("TRANSLATION: the preset {0} is installed", this.getPreset().getId());
        } catch (CancellationException exception) {
            this.cancelled = true;
        } catch (IOException exception) {
            this.failure = String.valueOf(exception.getMessage());
            LOGGER.warning("TRANSLATION: the download of {0} failed: {1}", this.getPreset().getId(), exception.getMessage());
        } finally {
            this.running = false;
            this.finished.run();
        }
    }
}
