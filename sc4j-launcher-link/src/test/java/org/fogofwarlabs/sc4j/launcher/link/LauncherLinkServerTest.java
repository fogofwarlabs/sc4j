package org.fogofwarlabs.sc4j.launcher.link;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Tests the launcher link server with clients in the same process over a socket in a temporary folder: requests and their responses or
 * failures, unknown services, events to every client, refused hellos, oversized frames and the socket file.
 */
final class LauncherLinkServerTest {
    private static final @NotNull String VERSION = "0.1.0-TEST";
    private static final @NotNull String OTHER_VERSION = "0.2.0-TEST";
    private static final @NotNull String SOCKET_NAME = "link.sock";
    private static final @NotNull String ECHO_SERVICE = "echo";
    private static final @NotNull String FAILING_SERVICE = "failing";
    private static final @NotNull String EVENT_SERVICE = "news";
    private static final long TIMEOUT_SECONDS = 10L;

    private static byte @NotNull [] toBytes(@NotNull String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static @NotNull Throwable awaitFailure(@NotNull CompletableFuture<byte @NotNull []> future) {
        ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        return failure.getCause();
    }

    @Test
    void request_should_reach_the_handler_of_its_service_and_return_its_response(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws Exception {
        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(directory.resolve(SOCKET_NAME)), VERSION)) {
            server.register(ECHO_SERVICE, payload -> CompletableFuture.completedFuture(toBytes("echo " + new String(payload, StandardCharsets.UTF_8))));

            try (LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION)) {
                CompletableFuture<byte[]> first = client.request(ECHO_SERVICE, toBytes("one"));
                CompletableFuture<byte[]> second = client.request(ECHO_SERVICE, toBytes("two"));

                Assertions.assertEquals("echo one", new String(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8));
                Assertions.assertEquals("echo two", new String(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void request_should_fail_with_the_message_of_a_failed_handler_and_of_an_unknown_service(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws Exception {
        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(directory.resolve(SOCKET_NAME)), VERSION)) {
            server.register(FAILING_SERVICE, payload -> CompletableFuture.failedFuture(Exceptions.newIllegalStateException("no model")));
            server.register(ECHO_SERVICE, payload -> {
                throw Exceptions.newIllegalArgumentException("bad payload");
            });

            try (LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION)) {
                Throwable failed = awaitFailure(client.request(FAILING_SERVICE, new byte[0]));
                Throwable thrown = awaitFailure(client.request(ECHO_SERVICE, new byte[0]));
                Throwable unknown = awaitFailure(client.request("missing", new byte[0]));

                Assertions.assertInstanceOf(LauncherLinkException.class, failed);
                Assertions.assertEquals("no model", failed.getMessage());
                Assertions.assertEquals("bad payload", thrown.getMessage());
                Assertions.assertInstanceOf(LauncherLinkException.class, unknown);
                Assertions.assertTrue(unknown.getMessage().contains("missing"), unknown.getMessage());
                Assertions.assertFalse(client.isClosed(), "a failure keeps the link open");
            }
        }
    }

    @Test
    void register_should_reject_a_second_handler_of_a_service(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws IOException {
        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(directory.resolve(SOCKET_NAME)), VERSION)) {
            server.register(ECHO_SERVICE, CompletableFuture::completedFuture);

            Assertions.assertThrows(IllegalArgumentException.class, () -> server.register(ECHO_SERVICE, CompletableFuture::completedFuture));
        }
    }

    @Test
    void publish_should_send_the_event_to_every_client(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws Exception {
        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(directory.resolve(SOCKET_NAME)), VERSION);
             LauncherLinkClient first = LauncherLinkClient.connect(server.getAddress(), VERSION);
             LauncherLinkClient second = LauncherLinkClient.connect(server.getAddress(), VERSION)) {
            CompletableFuture<String> firstEvent = new CompletableFuture<>();
            CompletableFuture<String> secondEvent = new CompletableFuture<>();
            CompletableFuture<String> otherService = new CompletableFuture<>();

            first.addEventListener(EVENT_SERVICE, payload -> firstEvent.complete(new String(payload, StandardCharsets.UTF_8)));
            second.addEventListener(EVENT_SERVICE, payload -> secondEvent.complete(new String(payload, StandardCharsets.UTF_8)));
            second.addEventListener(ECHO_SERVICE, payload -> otherService.complete(new String(payload, StandardCharsets.UTF_8)));
            server.publish(EVENT_SERVICE, toBytes("ready"));

            Assertions.assertEquals("ready", firstEvent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Assertions.assertEquals("ready", secondEvent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Assertions.assertFalse(otherService.isDone(), "a listener hears only its own service");
        }
    }

    @Test
    void connect_should_be_refused_with_a_wrong_token_or_another_version(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws IOException {
        Path socket = directory.resolve(SOCKET_NAME);

        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(socket), VERSION)) {
            LauncherLinkAddress wrongToken = LauncherLinkAddress.create(socket);

            Assertions.assertThrows(LauncherLinkException.class, () -> LauncherLinkClient.connect(wrongToken, VERSION));
            Assertions.assertThrows(LauncherLinkException.class, () -> LauncherLinkClient.connect(server.getAddress(), OTHER_VERSION));

            try (LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION)) {
                Assertions.assertFalse(client.isClosed(), "the right token and version are accepted");
            }
        }
    }

    @Test
    void server_should_close_a_connection_that_sends_an_oversized_frame(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws IOException {
        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(directory.resolve(SOCKET_NAME)), VERSION);
             SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(server.getAddress().getSocket()));

            ByteBuffer hello = LinkFrame.createHello(VERSION, server.getAddress().getToken()).encode();

            while (hello.hasRemaining()) {
                channel.write(hello);
            }

            Assertions.assertEquals(LinkFrameKind.HELLO, LinkFrame.read(channel).getKind(), "the server accepts the hello");

            ByteBuffer oversized = ByteBuffer.allocate(Integer.BYTES).putInt(0, LinkFrame.MAXIMUM_FRAME_LENGTH + 1);

            while (oversized.hasRemaining()) {
                channel.write(oversized);
            }

            Assertions.assertNull(LinkFrame.read(channel), "the server closes the connection");
        }
    }

    @Test
    void request_should_fail_when_its_payload_or_the_response_is_oversized(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws Exception {
        byte[] oversized = new byte[LinkFrame.MAXIMUM_FRAME_LENGTH];

        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(directory.resolve(SOCKET_NAME)), VERSION)) {
            server.register(ECHO_SERVICE, payload -> CompletableFuture.completedFuture(oversized));

            try (LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION)) {
                Assertions.assertInstanceOf(LauncherLinkException.class, awaitFailure(client.request(ECHO_SERVICE, oversized)));
                Assertions.assertInstanceOf(LauncherLinkException.class, awaitFailure(client.request(ECHO_SERVICE, new byte[0])));
                Assertions.assertFalse(client.isClosed(), "an oversized request or response keeps the link open");
            }
        }
    }

    @Test
    void open_should_replace_a_stale_socket_file_and_close_should_delete_it(@TempDir(factory = SocketTempDirFactory.class) @NotNull Path directory) throws IOException {
        Path socket = directory.resolve("run").resolve(SOCKET_NAME);

        Files.createDirectories(socket.getParent());
        Files.writeString(socket, "stale");

        try (LauncherLinkServer server = LauncherLinkServer.open(LauncherLinkAddress.create(socket), VERSION)) {
            Assertions.assertTrue(Files.exists(server.getAddress().getSocket()));
        }

        Assertions.assertFalse(Files.exists(socket), "closing deletes the socket file");
    }
}
