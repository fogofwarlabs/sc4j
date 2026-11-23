package org.fogofwarlabs.sc4j.beaconlink.peer.file;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a file that several processes of one computer read and write at the same time, such as the learned peers of an install: the file
 * itself is locked, never replaced. A merge opens the file read-write, takes an exclusive lock of the whole file, reads it, lets the caller
 * merge its change into what is there, writes the result in place from the start, truncates the rest, forces it to the disk and releases the
 * lock. A read takes a shared lock, so a reader waits while a writer holds the file and never sees a half-written file of a running writer.
 * An empty file counts as a missing one: a writer creates the file before it can lock it.
 * <p>
 * A process that ends during a write (a crash, a kill, a power loss before the force) may leave a partly written file; the caller reads it
 * as empty and the next merge writes it again from what the processes know. There is no backup copy and no temporary file.
 * <p>
 * The operating system orders the locks of processes, but one process may hold one lock of a file only: there is one {@link SharedFile} per
 * file in a process ({@link #of}), and its methods hold the object while they hold the lock, so threads of one process take turns.
 */
public final class SharedFile {
    private static final @NotNull Map<@NotNull Path, @NotNull SharedFile> FILES = new ConcurrentHashMap<>();
    private static final long WHOLE_FILE = Long.MAX_VALUE;
    private static final byte @NotNull [] EMPTY = new byte[0];

    /**
     * Merges a change into the contents of a shared file.
     */
    @FunctionalInterface
    public interface Merger {
        /**
         * Merges the caller's change into the current contents; called while the file is locked.
         *
         * @param contents the current contents: {@code null} if the file does not exist or is empty, empty if it is longer than the read limit
         * @return the contents to write
         */
        byte @NotNull [] merge(byte @Nullable [] contents);
    }

    /**
     * Returns the shared file object of a path: the same object for every caller of this process that names the same file.
     *
     * @param path the file
     * @return the shared file object of the absolute, normalized path
     */
    public static @NotNull SharedFile of(@NotNull Path path) {
        return FILES.computeIfAbsent(path.toAbsolutePath().normalize(), SharedFile::new);
    }

    private static byte @Nullable [] readContents(@NotNull FileChannel channel, int maximumLength) throws IOException {
        long size = channel.size();

        if (size == 0L) {
            return null;
        }

        if (size > maximumLength) {
            return EMPTY;
        }

        ByteBuffer buffer = ByteBuffer.allocate((int) size);

        while (buffer.hasRemaining()) {
            if (channel.read(buffer, buffer.position()) < 0) {
                return EMPTY;
            }
        }

        return buffer.array();
    }


    private final @NotNull Path path;

    private SharedFile(@NotNull Path path) {
        this.path = path;
    }

    /**
     * @return the absolute file
     */
    public @NotNull Path getPath() {
        return this.path;
    }


    /**
     * Reads the file under a shared lock.
     *
     * @param maximumLength the longest file that is read; a longer file reads as empty
     * @return the contents: {@code null} if the file does not exist or is empty, empty if it is longer than {@code maximumLength}
     * @throws IOException if the file exists but cannot be read
     */
    public synchronized byte @Nullable [] read(int maximumLength) throws IOException {
        try (FileChannel channel = FileChannel.open(this.path, StandardOpenOption.READ)) {
            FileLock lock = channel.lock(0L, WHOLE_FILE, true);

            try {
                return readContents(channel, maximumLength);
            } finally {
                lock.release();
            }
        } catch (NoSuchFileException exception) {
            return null;
        }
    }

    /**
     * Merges a change into the file under an exclusive lock, creating the file and its folder when they do not exist: reads the file, writes
     * what the merger returns in place from the start, truncates the rest and forces the file to the disk.
     *
     * @param maximumLength the longest file that is read; a longer file reaches the merger as empty
     * @param merger        the merge of the caller's change, called while the file is locked
     * @return the written contents
     * @throws IOException if the file cannot be created, locked, read or written
     */
    public synchronized byte @NotNull [] merge(int maximumLength, @NotNull Merger merger) throws IOException {
        Path directory = this.path.getParent();

        if (directory != null) {
            Files.createDirectories(directory);
        }

        try (FileChannel channel = FileChannel.open(this.path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock = channel.lock();

            try {
                byte[] merged = merger.merge(readContents(channel, maximumLength));
                ByteBuffer buffer = ByteBuffer.wrap(merged);

                while (buffer.hasRemaining()) {
                    channel.write(buffer, buffer.position());
                }

                channel.truncate(merged.length);
                channel.force(true);

                return merged;
            } finally {
                lock.release();
            }
        }
    }
}
