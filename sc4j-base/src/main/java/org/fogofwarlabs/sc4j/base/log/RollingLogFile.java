package org.fogofwarlabs.sc4j.base.log;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Appends log lines to a file that rolls over by size: {@code name.log} becomes {@code name.1.log}, which becomes
 * {@code name.2.log}, and the oldest beyond the kept count is deleted.
 */
final class RollingLogFile {
    private final @NotNull Path directory;
    private final @NotNull String baseName;
    private final @NotNull String extension;
    private final int fileCount;
    private final long sizeLimit;

    private long size;
    private @NotNull Writer writer;

    /**
     * Creates a {@link RollingLogFile} instance that appends to the current file of a directory.
     *
     * @param directory the log directory, created if absent
     * @param fileName  the file name of the current log
     * @param fileCount the number of files kept, including the current one
     * @param sizeLimit the size in bytes at which the current file rolls over
     * @throws IOException if the directory or the file cannot be opened
     */
    RollingLogFile(@NotNull Path directory, @NotNull String fileName, int fileCount, long sizeLimit) throws IOException {
        int dot = fileName.lastIndexOf('.');

        this.directory = directory;
        this.baseName  = dot < 0 ? fileName : fileName.substring(0, dot);
        this.extension = dot < 0 ? "" : fileName.substring(dot);
        this.fileCount = fileCount;
        this.sizeLimit = sizeLimit;

        Files.createDirectories(directory);

        Path current = this.getPath(0);

        this.size   = Files.exists(current) ? Files.size(current) : 0;
        this.writer = Files.newBufferedWriter(current, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private @NotNull Path getPath(int index) {
        return this.directory.resolve(index == 0 ? this.baseName + this.extension : this.baseName + '.' + index + this.extension);
    }


    /**
     * Appends one line and flushes it; an I/O failure is reported through the return value.
     *
     * @param line the line without its terminator
     * @return {@code true} if the line was written, {@code false} otherwise
     */
    boolean write(@NotNull String line) {
        try {
            String text = line + System.lineSeparator();

            if (this.size > 0 && this.size + text.length() > this.sizeLimit) {
                this.roll();
            }

            this.writer.write(text);
            this.writer.flush();
            this.size += text.getBytes(StandardCharsets.UTF_8).length;

            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private void roll() throws IOException {
        int oldestIndex = this.fileCount - 1;

        this.writer.close();
        Files.deleteIfExists(this.getPath(oldestIndex));

        for (int index = oldestIndex - 1; index >= 0; index--) {
            Path source = this.getPath(index);

            if (Files.exists(source)) {
                Files.move(source, this.getPath(index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        this.size   = 0;
        this.writer = Files.newBufferedWriter(this.getPath(0), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }


    /**
     * Closes the current file; a close failure is ignored.
     */
    void close() {
        try {
            this.writer.close();
        } catch (IOException ignored) {
            // The file is abandoned either way.
        }
    }
}
