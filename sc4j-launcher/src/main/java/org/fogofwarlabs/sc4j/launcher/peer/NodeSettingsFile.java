package org.fogofwarlabs.sc4j.launcher.peer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the node settings file of an install, {@code <install>/userdata/beaconlink/}{@value #FILE_NAME}, next to the other Beacon.Link
 * files:
 * {@snippet lang = "json":
 * {
 *   "format": 1,
 *   "role": "automatic",
 *   "port": 7455,
 *   "portMapping": true
 * }
 *}
 * The role is {@code automatic} or {@code off} ({@link NodeRole#getName()}); without {@code portMapping} the ports are mapped. A missing file
 * holds {@link NodeSettings#DEFAULT}.
 */
public final class NodeSettingsFile {
    /**
     * The name of the node settings file in the install's Beacon.Link folder.
     */
    public static final @NotNull String FILE_NAME = "node.json";
    /**
     * The format this release reads and writes.
     */
    public static final int FORMAT = 1;

    private static final @NotNull String FORMAT_FIELD = "format";
    private static final @NotNull String ROLE_FIELD = "role";
    private static final @NotNull String PORT_FIELD = "port";
    private static final @NotNull String PORT_MAPPING_FIELD = "portMapping";

    /**
     * Creates a {@link NodeSettingsFile} instance for the node settings file of an install.
     *
     * @param install the install
     * @return the created {@link NodeSettingsFile} instance
     */
    public static @NotNull NodeSettingsFile of(@NotNull InstallLayout install) {
        return new NodeSettingsFile(install.getBeaconLinkDirectory().resolve(FILE_NAME));
    }


    private final @NotNull Path path;

    /**
     * Creates a {@link NodeSettingsFile} instance; nothing is read or created.
     *
     * @param path the file path
     */
    public NodeSettingsFile(@NotNull Path path) {
        this.path = path;
    }

    /**
     * @return the file path
     */
    public @NotNull Path getPath() {
        return this.path;
    }


    /**
     * Reads the settings. A missing file holds {@link NodeSettings#DEFAULT}.
     *
     * @return the settings
     * @throws IOException if the file exists but cannot be read, is not valid JSON or is not in the node settings format
     */
    public @NotNull NodeSettings read() throws IOException {
        byte[] contents;

        try {
            contents = Files.readAllBytes(this.path);
        } catch (NoSuchFileException exception) {
            return NodeSettings.DEFAULT;
        }

        if (!(JsonReader.parse(contents) instanceof Map<?, ?> object) || !(object.get(FORMAT_FIELD) instanceof Long format) || format != FORMAT ||
            !(object.get(ROLE_FIELD) instanceof String roleName) || !(NodeRole.fromName(roleName) instanceof NodeRole role) ||
            !(object.get(PORT_FIELD) instanceof Long port) || port < ConfiguredNode.MINIMUM_PORT || port > ConfiguredNode.MAXIMUM_PORT) {
            throw Exceptions.newIOException("Invalid node settings file {0}: expected format {1} with a role and a port", this.path, FORMAT);
        }

        Object portMapping = object.get(PORT_MAPPING_FIELD);

        if (portMapping != null && !(portMapping instanceof Boolean)) {
            throw Exceptions.newIOException("Invalid node settings file {0}: portMapping must be true or false", this.path);
        }

        return new NodeSettings(role, port.intValue(), !Boolean.FALSE.equals(portMapping));
    }

    /**
     * Writes the settings, replacing the file atomically where the file system allows; the folder is created when missing.
     *
     * @param settings the settings
     * @throws IOException if the file cannot be written
     */
    public void write(@NotNull NodeSettings settings) throws IOException {
        Map<String, Object> object = new LinkedHashMap<>();

        object.put(FORMAT_FIELD, (long) FORMAT);
        object.put(ROLE_FIELD, settings.getRole().getName());
        object.put(PORT_FIELD, (long) settings.getPort());
        object.put(PORT_MAPPING_FIELD, settings.isPortMapping());

        AtomicFileUtils.write(this.path, JsonWriterUtils.write(object).getBytes(StandardCharsets.UTF_8));
    }
}
