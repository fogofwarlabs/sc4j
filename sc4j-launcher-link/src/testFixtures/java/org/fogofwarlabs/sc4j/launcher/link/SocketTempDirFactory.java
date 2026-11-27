package org.fogofwarlabs.sc4j.launcher.link;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the temporary folders of tests that bind launcher link sockets: under {@code /tmp} where it exists, else under the JVM's temporary
 * folder, so that a socket path stays within {@link LauncherLinkAddress#MAXIMUM_SOCKET_PATH_LENGTH} when the build puts the JVM's temporary
 * folder deep in the build directory.
 */
public final class SocketTempDirFactory implements TempDirFactory {
    private static final @NotNull Path UNIX_TEMPORARY_FOLDER = Path.of("/tmp");
    private static final @NotNull String PREFIX = "sc4j-link-";

    /**
     * Creates the temporary folder of a test.
     *
     * @param elementContext   the annotated field or parameter
     * @param extensionContext the context of the test
     * @return the created folder
     * @throws IOException if the folder cannot be created
     */
    @Override
    public @NotNull Path createTempDirectory(@NotNull AnnotatedElementContext elementContext, @NotNull ExtensionContext extensionContext) throws IOException {
        Path parent = Files.isDirectory(UNIX_TEMPORARY_FOLDER) && UNIX_TEMPORARY_FOLDER.isAbsolute() ? UNIX_TEMPORARY_FOLDER
                                                                                                     : Path.of(System.getProperty("java.io.tmpdir"));

        return Files.createTempDirectory(parent, PREFIX);
    }
}
