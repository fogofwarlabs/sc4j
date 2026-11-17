package org.fogofwarlabs.sc4j.launcher.install;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the record of the mods installed from releases, {@code userdata/mod-sources.json}:
 * {@snippet lang = "json":
 * {
 *   "format": 1,
 *   "mods": [
 *     {"jar": "my-mod.jar", "repository": "owner/my-mod", "release": "v1.2.0"}
 *   ]
 * }
 *}
 * A mod put into {@code mods/} by hand has no record.
 */
public final class ModSourcesFile {
    /**
     * The record format this release reads and writes.
     */
    public static final int FORMAT = 1;

    private static final @NotNull String FORMAT_FIELD = "format";
    private static final @NotNull String MODS_FIELD = "mods";
    private static final @NotNull String JAR_FIELD = "jar";
    private static final @NotNull String REPOSITORY_FIELD = "repository";
    private static final @NotNull String RELEASE_FIELD = "release";

    private final @NotNull Path path;

    /**
     * Creates a {@link ModSourcesFile} instance.
     *
     * @param path the file path, normally {@code <install>/userdata/mod-sources.json}
     */
    public ModSourcesFile(@NotNull Path path) {
        this.path = path;
    }

    /**
     * @return the file path
     */
    public @NotNull Path getPath() {
        return this.path;
    }

    /**
     * Reads the records. A missing file holds none.
     *
     * @return the records, in the order of the file
     * @throws IOException if the file exists but cannot be read or is not a record file of format {@value #FORMAT}
     */
    public @NotNull List<@NotNull ModSource> read() throws IOException {
        byte[] contents;

        try {
            contents = Files.readAllBytes(this.path);
        } catch (NoSuchFileException exception) {
            return List.of();
        }

        if (!(JsonReader.parse(contents) instanceof Map<?, ?> object) || !(object.get(FORMAT_FIELD) instanceof Long format) || format != FORMAT ||
            !(object.get(MODS_FIELD) instanceof List<?> mods)) {
            throw Exceptions.newIOException("Invalid mod sources file {0}: expected format {1}", this.path, FORMAT);
        }

        List<ModSource> sources = new ArrayList<>(mods.size());

        for (Object mod : mods) {
            if (!(mod instanceof Map<?, ?> entry) || !(entry.get(JAR_FIELD) instanceof String jarName) ||
                !(entry.get(REPOSITORY_FIELD) instanceof String repositoryText) || !(entry.get(RELEASE_FIELD) instanceof String release)) {
                throw Exceptions.newIOException("Invalid mod sources file {0}: expected entries {jar, repository, release}", this.path);
            }

            ReleaseRepository repository = ReleaseRepository.parse(repositoryText);

            if (repository == null) {
                throw Exceptions.newIOException("Invalid mod sources file {0}: invalid repository {1}", this.path, repositoryText);
            }

            sources.add(new ModSource(jarName, repository, release));
        }

        return sources;
    }

    /**
     * Writes the records, replacing the file atomically where the file system allows; the folder is created when missing.
     *
     * @param sources the records
     * @throws IOException if the file cannot be written
     */
    public void write(@NotNull List<@NotNull ModSource> sources) throws IOException {
        Map<String, Object> object = new LinkedHashMap<>();
        List<Object> mods = new ArrayList<>(sources.size());

        for (ModSource source : sources) {
            Map<String, Object> entry = new LinkedHashMap<>();

            entry.put(JAR_FIELD, source.getJarName());
            entry.put(REPOSITORY_FIELD, source.getRepository().toString());
            entry.put(RELEASE_FIELD, source.getRelease());
            mods.add(entry);
        }

        object.put(FORMAT_FIELD, (long) FORMAT);
        object.put(MODS_FIELD, mods);
        AtomicFileUtils.write(this.path, JsonWriterUtils.write(object).getBytes(StandardCharsets.UTF_8));
    }
}
