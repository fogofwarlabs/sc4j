package org.fogofwarlabs.sc4j.launcher.launch;

import org.fogofwarlabs.sc4j.launcher.link.LauncherLinkAddress;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests the launcher's link host: the socket path of a launcher process, the removal of the sockets of launchers that no longer run, and the
 * launcher without a link when the socket path is too long.
 */
final class LauncherLinkHostTest {
    private static final @NotNull String VERSION = "0.1.0-TEST";
    private static final long ENDED_PID = Long.MAX_VALUE;

    @Test
    void get_socket_should_name_the_launcher_process_in_the_run_folder(@TempDir @NotNull Path directory) {
        InstallLayout install = InstallLayout.of(directory);

        Assertions.assertEquals(directory.resolve("userdata").resolve("run").resolve("launcher-1234.sock"), LauncherLinkHost.getSocket(install, 1234));
    }

    @Test
    void remove_stale_sockets_should_delete_only_the_sockets_of_ended_launchers(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        Path runDirectory = LauncherLinkHost.getRunDirectory(install);
        Path ended = LauncherLinkHost.getSocket(install, ENDED_PID);
        Path running = LauncherLinkHost.getSocket(install, ProcessHandle.current().pid());
        Path other = runDirectory.resolve("launcher-notes.sock");

        Files.createDirectories(runDirectory);
        Files.writeString(ended, "");
        Files.writeString(running, "");
        Files.writeString(other, "");

        LauncherLinkHost.removeStaleSockets(runDirectory);

        Assertions.assertFalse(Files.exists(ended), "the socket of an ended launcher is removed");
        Assertions.assertTrue(Files.exists(running), "the socket of a running launcher stays");
        Assertions.assertTrue(Files.exists(other), "a file without a process id stays");
    }

    @Test
    void open_should_leave_the_launcher_without_a_link_when_the_socket_path_is_too_long(@TempDir @NotNull Path directory) {
        InstallLayout install = InstallLayout.of(directory.resolve("x".repeat(LauncherLinkAddress.MAXIMUM_SOCKET_PATH_LENGTH)));

        try (LauncherLinkHost host = LauncherLinkHost.open(install, VERSION)) {
            Assertions.assertNull(host.getAddress());
            Assertions.assertDoesNotThrow(() -> host.publish("news", new byte[0]));
        }
    }
}
