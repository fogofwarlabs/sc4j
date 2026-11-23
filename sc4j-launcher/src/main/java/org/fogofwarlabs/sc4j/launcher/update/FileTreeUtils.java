package org.fogofwarlabs.sc4j.launcher.update;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies, deletes and unpacks folder trees for the launcher update. Symbolic links are copied and deleted as links, never followed.
 */
public final class FileTreeUtils {
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Copies a file or a folder with everything in it; the target must not exist.
     *
     * @param source the file or folder
     * @param target the copy to create
     * @throws IOException if a file cannot be copied
     */
    public static void copy(@NotNull Path source, @NotNull Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            /**
             * Invoked for a directory before entries in the directory are visited.
             *
             * @param directory  a reference to the directory
             * @param attributes the directory's basic attributes
             * @return the visit result
             * @throws IOException if an I/O error occurs
             */
            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path directory, @NotNull BasicFileAttributes attributes) throws IOException {
                Files.createDirectory(target.resolve(source.relativize(directory).toString()));

                return FileVisitResult.CONTINUE;
            }

            /**
             * Invoked for a file in a directory.
             *
             * @param file       a reference to the file
             * @param attributes the file's basic attributes
             * @return the visit result
             * @throws IOException if an I/O error occurs
             */
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES,
                           LinkOption.NOFOLLOW_LINKS);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Deletes a file or a folder with everything in it; a missing path is left as it is.
     *
     * @param path the file or folder
     * @throws IOException if a file cannot be deleted
     */
    public static void delete(@NotNull Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            /**
             * Invoked for a file in a directory.
             *
             * @param file       a reference to the file
             * @param attributes the file's basic attributes
             * @return the visit result
             * @throws IOException if an I/O error occurs
             */
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attributes) throws IOException {
                Files.delete(file);

                return FileVisitResult.CONTINUE;
            }

            /**
             * Invoked for a directory after entries in the directory, and all of their descendants, have been visited.
             *
             * @param directory a reference to the directory
             * @param failure   {@code null} if the iteration of the directory completes without an error, else the I/O exception that ended it early
             * @return the visit result
             * @throws IOException if an I/O error occurs
             */
            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path directory, @Nullable IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }

                Files.delete(directory);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Unpacks a zip file into a folder, which is created when missing. An entry that would land outside the folder is refused.
     *
     * @param zip         the zip file
     * @param target      the folder
     * @param maximumSize the largest total size of the unpacked files, in bytes
     * @throws IOException if the zip file is broken, an entry is refused, the files are larger than the limit or a file cannot be written
     */
    public static void unzip(@NotNull Path zip, @NotNull Path target, long maximumSize) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;

        Files.createDirectories(root);

        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path destination = root.resolve(entry.getName()).normalize();

                if (!destination.startsWith(root) || destination.equals(root)) {
                    throw Exceptions.newIOException("The zip entry {0} lies outside the target folder.", entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(destination);

                    continue;
                }

                Files.createDirectories(destination.getParent());

                try (InputStream input = file.getInputStream(entry); OutputStream output = Files.newOutputStream(destination)) {
                    int read = input.read(buffer);

                    while (read >= 0) {
                        written += read;

                        if (written > maximumSize) {
                            throw Exceptions.newIOException("The zip file {0} unpacks to more than {1} bytes.", zip, maximumSize);
                        }

                        output.write(buffer, 0, read);
                        read = input.read(buffer);
                    }
                }
            }
        }
    }


    private FileTreeUtils() {
    }
}
