package org.fogofwarlabs.sc4j.launcher.link;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests how a launcher link client sees the end of its link: pending requests fail, close listeners run once, and a missing server refuses
 * the connect.
 */
final class LauncherLinkClientTest {
    private static final @NotNull String VERSION = "0.1.0-TEST";
    private static final @NotNull String SOCKET_NAME = "link.sock";
    private static final @NotNull String WAITING_SERVICE = "waiting";
    private static final long TIMEOUT_SECONDS = 10L;

    private static void assertLinkFailure(@NotNull CompletableFuture<byte @NotNull []> future) {
        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        Assertions.assertInstanceOf(LauncherLinkException.class, failure.getCause());
    }

    @Test
    void client_should_fail_pending_requests_and_run_close_listeners_when_the_server_closes(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws Exception {
        CompletableFuture<Void> closeSeen = new CompletableFuture<>();
        CompletableFuture<Void> requestReceived = new CompletableFuture<>();
        LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(directory.resolve(SOCKET_NAME)), VERSION);

        server.register(WAITING_SERVICE, payload -> {
            requestReceived.complete(null);

            return new CompletableFuture<>();
        });

        try (LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION)) {
            CompletableFuture<byte[]> pending = client.request(WAITING_SERVICE, new byte[0]);

            client.addCloseListener(() -> closeSeen.complete(null));
            requestReceived.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server.close();

            assertLinkFailure(pending);
            closeSeen.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Assertions.assertTrue(client.isClosed());
            assertLinkFailure(client.request(WAITING_SERVICE, new byte[0]));
        } finally {
            server.close();
        }
    }

    @Test
    void close_should_fail_pending_requests_and_run_each_close_listener_once(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger lateCloseCount = new AtomicInteger();

        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(directory.resolve(SOCKET_NAME)), VERSION)) {
            server.register(WAITING_SERVICE, payload -> new CompletableFuture<>());

            LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION);
            CompletableFuture<byte[]> pending = client.request(WAITING_SERVICE, new byte[0]);

            client.addCloseListener(closeCount::incrementAndGet);
            client.close();
            client.close();
            client.addCloseListener(lateCloseCount::incrementAndGet);

            assertLinkFailure(pending);
            Assertions.assertEquals(1, closeCount.get(), "a close listener runs once");
            Assertions.assertEquals(1, lateCloseCount.get(), "a listener added to a closed link runs at once");
        }
    }

    @Test
    void connect_should_fail_without_a_server(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) {
        LauncherLinkAddress address = LauncherLinkAddress.create(directory.resolve(SOCKET_NAME));

        Assertions.assertThrows(LauncherLinkException.class, () -> LauncherLinkClient.connect(address, VERSION));
    }
}
