package org.fogofwarlabs.sc4j.translate.service;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.service.model.PresetFile;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationPreset;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;

/**
 * Installs the files of a preset into its folder and deletes them. A download goes to a {@code .part} file next to its target, is checked
 * against the preset's size and SHA-256 checksum, and only then takes the file's name, so a file of the folder is always complete; files
 * that are already there with their size are kept. The installation runs only when {@link #install} is called, on the caller's thread, and
 * stops when {@link #cancel()} is called or the thread is interrupted.
 */
public final class PresetInstallation {
    private static final @NotNull String PARTIAL_SUFFIX = ".part";
    private static final @NotNull String DIGEST_ALGORITHM = "SHA-256";
    private static final @NotNull String USER_AGENT_HEADER = "User-Agent";
    private static final @NotNull String USER_AGENT = "SC4J";
    private static final @NotNull Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final @NotNull Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    private static final int OK_STATUS = 200;
    private static final int BUFFER_SIZE = 256 * 1024;

    /**
     * Receives the progress of an installation.
     */
    @FunctionalInterface
    public interface ProgressListener {
        /**
         * Called on the installing thread after each part of a download.
         *
         * @param installedBytes the bytes of the preset's files installed so far, the files that were already there included
         * @param totalBytes     the size of all files of the preset
         */
        void onProgress(long installedBytes, long totalBytes);
    }


    private static @NotNull MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no {0}.", DIGEST_ALGORITHM);
        }
    }


    private final @NotNull TranslationPreset preset;
    private final @NotNull Path folder;
    private final @NotNull HttpClient http;

    private volatile boolean cancelled;

    /**
     * Creates a {@link PresetInstallation} instance.
     *
     * @param preset the preset
     * @param folder the folder of the preset's files, such as {@link TranslationStorage#getPresetFolder}
     */
    public PresetInstallation(@NotNull TranslationPreset preset, @NotNull Path folder) {
        this.preset = preset;
        this.folder = folder;
        this.http   = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT_TIMEOUT).build();

        this.cancelled = false;
    }

    /**
     * @return the preset
     */
    public @NotNull TranslationPreset getPreset() {
        return this.preset;
    }

    /**
     * @return the folder of the preset's files
     */
    public @NotNull Path getFolder() {
        return this.folder;
    }

    /**
     * @return {@code true} if every file of the preset is in the folder with its size, {@code false} otherwise
     */
    public boolean isInstalled() {
        for (PresetFile file : this.preset.getFiles()) {
            if (!this.isFileInstalled(file)) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return the size of all files of the preset in bytes
     */
    public long getTotalSize() {
        long total = 0;

        for (PresetFile file : this.preset.getFiles()) {
            total += file.getSize();
        }

        return total;
    }

    /**
     * Downloads the files of the preset that are missing. A file that fails its size or checksum check is deleted.
     *
     * @param listener receives the progress on the calling thread
     * @throws IOException           if a file cannot be downloaded, does not match its size or checksum, or cannot be written
     * @throws CancellationException if {@link #cancel()} was called or the thread was interrupted; the partial file is deleted
     */
    public void install(@NotNull ProgressListener listener) throws IOException {
        long total = this.getTotalSize();
        long installed = 0;

        this.cancelled = false;

        Files.createDirectories(this.folder);

        for (PresetFile file : this.preset.getFiles()) {
            if (this.isFileInstalled(file)) {
                installed += file.getSize();
                listener.onProgress(installed, total);
            }
        }

        for (PresetFile file : this.preset.getFiles()) {
            if (!this.isFileInstalled(file)) {
                installed = this.download(file, installed, total, listener);
            }
        }
    }

    /**
     * Stops a running {@link #install}; it throws {@link CancellationException} after the current part of its download.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Deletes the files of the preset, the partial downloads included, and the folder when nothing else is in it.
     *
     * @throws IOException if a file cannot be deleted
     */
    public void delete() throws IOException {
        for (PresetFile file : this.preset.getFiles()) {
            Files.deleteIfExists(this.folder.resolve(file.getName()));
            Files.deleteIfExists(this.folder.resolve(file.getName() + PARTIAL_SUFFIX));
        }

        if (Files.isDirectory(this.folder)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(this.folder)) {
                if (!entries.iterator().hasNext()) {
                    Files.delete(this.folder);
                }
            }
        }
    }

    private boolean isFileInstalled(@NotNull PresetFile file) {
        Path path = this.folder.resolve(file.getName());

        try {
            return Files.isRegularFile(path) && Files.size(path) == file.getSize();
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Downloads one file to its partial file, checks it and gives it its name.
     *
     * @return the installed bytes after the file
     */
    private long download(@NotNull PresetFile file, long installedBefore, long total, @NotNull ProgressListener listener) throws IOException {
        Path target = this.folder.resolve(file.getName());
        Path partial = this.folder.resolve(file.getName() + PARTIAL_SUFFIX);
        MessageDigest digest = createDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;
        boolean complete = false;

        try (InputStream input = this.open(file.getAddress()); OutputStream output = Files.newOutputStream(partial)) {
            int read = input.read(buffer);

            while (read >= 0) {
                if (this.cancelled || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException(Exceptions.format("The installation of {0} was cancelled", this.preset.getId()));
                }

                written += read;

                if (written > file.getSize()) {
                    throw Exceptions.newIOException("{0} is larger than {1} bytes", file.getName(), file.getSize());
                }

                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                listener.onProgress(installedBefore + written, total);

                read = input.read(buffer);
            }

            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(partial);
            }
        }

        String checksum = HexFormat.of().formatHex(digest.digest());

        if (written != file.getSize() || !checksum.equals(file.getSha256())) {
            Files.deleteIfExists(partial);

            throw Exceptions.newIOException("{0} has {1} bytes and the checksum {2}; expected {3} bytes and {4}", file.getName(), written, checksum,
                                            file.getSize(), file.getSha256());
        }

        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);

        return installedBefore + written;
    }

    private @NotNull InputStream open(@NotNull URI address) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(address).timeout(RESPONSE_TIMEOUT).header(USER_AGENT_HEADER, USER_AGENT).GET().build();
        HttpResponse<InputStream> response;

        try {
            response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new CancellationException(Exceptions.format("The installation of {0} was interrupted", this.preset.getId()));
        }

        if (response.statusCode() != OK_STATUS) {
            response.body().close();

            throw Exceptions.newIOException("{0} answered with status {1}", address, response.statusCode());
        }

        return response.body();
    }
}
