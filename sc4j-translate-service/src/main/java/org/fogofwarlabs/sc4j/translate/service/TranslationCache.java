package org.fogofwarlabs.sc4j.translate.service;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * Keeps translations by preset id, source code, target code and text: the most recently used ones in memory, and every one in a disk folder
 * when one is given, one file per translation named by the SHA-256 of its key. A file holds its key and the translation, so a key whose
 * file holds another key misses. When the folder is opened with more files than its limit, the oldest are deleted. Used by one thread.
 */
final class TranslationCache {
    private static final int MEMORY_ENTRIES = 1024;
    private static final int DISK_ENTRIES = 50_000;
    private static final char KEY_SEPARATOR = '\u0000';
    private static final @NotNull String FILE_EXTENSION = ".translation";
    private static final @NotNull String DIGEST_ALGORITHM = "SHA-256";
    private static final @NotNull Logger LOGGER = Logger.of(TranslationCache.class);

    private static @NotNull String createKey(@NotNull String presetId, @NotNull String sourceCode, @NotNull String targetCode, @NotNull String text) {
        return presetId + KEY_SEPARATOR + sourceCode + KEY_SEPARATOR + targetCode + KEY_SEPARATOR + text;
    }

    private static @NotNull String createFileName(@NotNull String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST_ALGORITHM).digest(key.getBytes(StandardCharsets.UTF_8))) + FILE_EXTENSION;
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no {0}.", DIGEST_ALGORITHM);
        }
    }

    private static @NotNull FileTime readModifiedTime(@NotNull Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }


    private final @Nullable Path folder;
    private final @NotNull Map<@NotNull String, @NotNull String> memory;

    /**
     * Creates a {@link TranslationCache} instance and trims its disk folder to the limit.
     *
     * @param folder the disk folder, or {@code null} to keep translations in memory only
     */
    TranslationCache(@Nullable Path folder) {
        this.folder = folder;
        this.memory = new LinkedHashMap<>(MEMORY_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(@NotNull Map.Entry<@NotNull String, @NotNull String> eldest) {
                return this.size() > MEMORY_ENTRIES;
            }
        };

        if (folder != null) {
            this.trim(folder);
        }
    }

    /**
     * Finds a translation.
     *
     * @param presetId   the preset id
     * @param sourceCode the preset's code of the source language
     * @param targetCode the preset's code of the target language
     * @param text       the original text
     * @return the translation, or {@code null} when it is not kept
     */
    @Nullable String find(@NotNull String presetId, @NotNull String sourceCode, @NotNull String targetCode, @NotNull String text) {
        String key = createKey(presetId, sourceCode, targetCode, text);
        String translation = this.memory.get(key);

        if (translation != null || this.folder == null) {
            return translation;
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(this.folder.resolve(createFileName(key)))))) {
            if (!input.readUTF().equals(key)) {
                return null;
            }

            translation = input.readUTF();
        } catch (NoSuchFileException exception) {
            return null;
        } catch (IOException exception) {
            LOGGER.debug("Cannot read a cached translation: {0}", exception.getMessage());

            return null;
        }

        this.memory.put(key, translation);

        return translation;
    }

    /**
     * Keeps a translation.
     *
     * @param presetId    the preset id
     * @param sourceCode  the preset's code of the source language
     * @param targetCode  the preset's code of the target language
     * @param text        the original text
     * @param translation the translation
     */
    void store(@NotNull String presetId, @NotNull String sourceCode, @NotNull String targetCode, @NotNull String text, @NotNull String translation) {
        String key = createKey(presetId, sourceCode, targetCode, text);

        this.memory.put(key, translation);

        if (this.folder == null) {
            return;
        }

        ByteArrayOutputStream contents = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(contents)) {
            output.writeUTF(key);
            output.writeUTF(translation);
        } catch (IOException exception) {
            // A key or translation beyond the 64 KiB of a UTF field stays in memory only.
            return;
        }

        try {
            AtomicFileUtils.write(this.folder.resolve(createFileName(key)), contents.toByteArray());
        } catch (IOException exception) {
            LOGGER.warning("Cannot write a cached translation: {0}", exception.getMessage());
        }
    }

    /**
     * Deletes the oldest files of the folder beyond the limit.
     */
    private void trim(@NotNull Path folder) {
        List<Path> files;

        try (Stream<Path> entries = Files.list(folder)) {
            files = entries.filter(path -> path.getFileName().toString().endsWith(FILE_EXTENSION)).toList();
        } catch (IOException exception) {
            return;
        }

        if (files.size() <= DISK_ENTRIES) {
            return;
        }

        Map<Path, FileTime> modifiedTimes = new HashMap<>();
        List<Path> oldestFirst = new ArrayList<>(files);

        for (Path file : files) {
            modifiedTimes.put(file, readModifiedTime(file));
        }

        oldestFirst.sort(Comparator.comparing(modifiedTimes::get));

        for (Path file : oldestFirst.subList(0, files.size() - DISK_ENTRIES)) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                LOGGER.debug("Cannot delete a cached translation: {0}", exception.getMessage());
            }
        }
    }
}
