package org.fogofwarlabs.sc4j.launcher.launch;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.launcher.link.LauncherLinkAddress;
import org.fogofwarlabs.sc4j.launcher.link.LauncherLinkServer;
import org.fogofwarlabs.sc4j.launcher.link.LinkRequestHandler;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Owns the launcher's link to the runtimes it starts: the {@link LauncherLinkServer} on {@code <install>/userdata/run/launcher-<pid>.sock}
 * while the launcher runs. Opening removes the socket files of launchers that no longer run. A socket path longer than the platform allows,
 * or a socket that cannot be opened, is logged and leaves the launcher without a link; the runtimes then run on their own. The launcher's
 * features register their services here; without a link nothing reaches them.
 */
public final class LauncherLinkHost implements Closeable {
    private static final @NotNull Logger LOGGER = Logger.of(LauncherLinkHost.class);
    private static final @NotNull String RUN_FOLDER = "run";
    private static final @NotNull String SOCKET_PREFIX = "launcher-";
    private static final @NotNull String SOCKET_EXTENSION = ".sock";

    /**
     * Opens the link of the launcher process of an install; it is logged when the launcher runs without a link.
     *
     * @param install the install
     * @param version the SC4J version the runtimes must run
     * @return the created {@link LauncherLinkHost} instance, with or without a link
     */
    public static @NotNull LauncherLinkHost open(@NotNull InstallLayout install, @NotNull String version) {
        Path runDirectory = getRunDirectory(install);
        Path socket = getSocket(install, ProcessHandle.current().pid());

        removeStaleSockets(runDirectory);

        if (!LauncherLinkAddress.isSocketPathSupported(socket)) {
            LOGGER.warning("LAUNCHER LINK: none, the socket path is longer than {0} bytes: {1}", LauncherLinkAddress.MAXIMUM_SOCKET_PATH_LENGTH,
                           socket);

            return new LauncherLinkHost(null);
        }

        try {
            LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(socket), version);

            LOGGER.info("LAUNCHER LINK: {0}", socket);

            return new LauncherLinkHost(server);
        } catch (IOException exception) {
            LOGGER.warning("LAUNCHER LINK: none, the socket {0} cannot be opened: {1}", socket, exception.getMessage());

            return new LauncherLinkHost(null);
        }
    }

    /**
     * Gives the folder of the launchers' sockets of an install.
     *
     * @param install the install
     * @return the folder {@code <install>/userdata/run}
     */
    static @NotNull Path getRunDirectory(@NotNull InstallLayout install) {
        return install.getUserDataDirectory().resolve(RUN_FOLDER);
    }

    /**
     * Gives the socket of a launcher process of an install.
     *
     * @param install the install
     * @param pid     the process id of the launcher
     * @return the socket {@code <install>/userdata/run/launcher-<pid>.sock}
     */
    static @NotNull Path getSocket(@NotNull InstallLayout install, long pid) {
        return getRunDirectory(install).resolve(SOCKET_PREFIX + pid + SOCKET_EXTENSION);
    }

    /**
     * Deletes the socket files of launchers that no longer run: the files {@code launcher-<pid>.sock} of the folder whose process is not alive.
     * Other files stay; a folder that cannot be read or a file that cannot be deleted is left as it is.
     *
     * @param runDirectory the folder of the launchers' sockets
     */
    static void removeStaleSockets(@NotNull Path runDirectory) {
        if (!Files.isDirectory(runDirectory)) {
            return;
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(runDirectory, SOCKET_PREFIX + "*" + SOCKET_EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                long pid;

                try {
                    pid = Long.parseLong(name.substring(SOCKET_PREFIX.length(), name.length() - SOCKET_EXTENSION.length()));
                } catch (NumberFormatException exception) {
                    continue;
                }

                Optional<ProcessHandle> process = ProcessHandle.of(pid);

                if (process.isEmpty() || !process.get().isAlive()) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException exception) {
            LOGGER.warning("LAUNCHER LINK: stale sockets in {0} cannot be removed: {1}", runDirectory, exception.getMessage());
        }
    }


    private final @Nullable LauncherLinkServer server;

    /**
     * Creates a {@link LauncherLinkHost} instance.
     *
     * @param server the open server, or {@code null} when the launcher has no link
     */
    private LauncherLinkHost(@Nullable LauncherLinkServer server) {
        this.server = server;
    }

    /**
     * @return the address the launcher passes to the runtimes it starts, or {@code null} when the launcher has no link
     */
    public @Nullable LauncherLinkAddress getAddress() {
        return this.server == null ? null : this.server.getAddress();
    }

    /**
     * Registers the handler of a service of the launcher; without a link it is never called.
     *
     * @param service the service name
     * @param handler the handler of the service's requests
     * @throws IllegalArgumentException if the service has a handler already
     */
    public void register(@NotNull String service, @NotNull LinkRequestHandler handler) {
        if (this.server != null) {
            this.server.register(service, handler);
        }
    }

    /**
     * Sends an event of a service to every connected runtime; without a link it is dropped.
     *
     * @param service the service name
     * @param payload the event payload
     * @throws IllegalArgumentException if the event is longer than a frame may be
     */
    public void publish(@NotNull String service, byte @NotNull [] payload) {
        if (this.server != null) {
            this.server.publish(service, payload);
        }
    }

    /**
     * Closes the link: the connected runtimes see it close and the socket file is deleted.
     */
    @Override
    public void close() {
        if (this.server != null) {
            this.server.close();
        }
    }
}
