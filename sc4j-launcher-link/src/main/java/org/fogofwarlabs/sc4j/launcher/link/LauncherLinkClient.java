package org.fogofwarlabs.sc4j.launcher.link;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Connects a runtime to the {@link LauncherLinkServer} of the launcher that started it: it sends requests to the launcher's services and
 * receives their events.
 * <p>
 * {@link #connect} sends the hello with the token and the SC4J version and returns once the server accepted it. A daemon thread of the client
 * then reads the server's frames: responses and failures complete the futures of {@link #request}, events reach the listeners of their service
 * on that thread. When the connection ends or {@link #close()} is called, every pending request fails with a {@link LauncherLinkException}
 * and the close listeners run once.
 */
public final class LauncherLinkClient implements Closeable {
    /**
     * The longest wait for the server's answer to the hello, in milliseconds.
     */
    public static final long HELLO_TIMEOUT_MILLIS = 10_000L;

    private static final @NotNull Logger LOGGER = Logger.of(LauncherLinkClient.class);
    private static final @NotNull String READER_THREAD_NAME = "sc4j-launcher-link-reader";

    /**
     * Connects to the server of an address and waits until it accepts the hello.
     *
     * @param address the socket of the server and the token of the link
     * @param version the SC4J version of the client, which must equal the server's
     * @return the created {@link LauncherLinkClient} instance, connected
     * @throws LauncherLinkException if the socket cannot be connected, the server refuses the hello or does not answer it in
     *                               {@value #HELLO_TIMEOUT_MILLIS} ms, or the thread is interrupted while it waits
     */
    public static @NotNull LauncherLinkClient connect(@NotNull LauncherLinkAddress address, @NotNull String version) throws LauncherLinkException {
        SocketChannel channel = null;

        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(address.getSocket()));
        } catch (IOException exception) {
            if (channel != null) {
                IgnoreException.execute(
                        channel::close
                );
            }

            throw LauncherLinkException.create(exception, "The launcher link {0} cannot be connected: {1}", address, exception.getMessage());
        }

        LauncherLinkClient client = new LauncherLinkClient(new LinkConnection(channel), version);

        try {
            client.connection.write(LinkFrame.createHello(version, address.getToken()));
            Thread.ofPlatform().name(READER_THREAD_NAME).daemon().start(client::readFrames);
            client.accepted.get(HELLO_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (IOException exception) {
            client.close();

            throw LauncherLinkException.create(exception, "The launcher link {0} failed: {1}", address, exception.getMessage());
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;

            client.close();

            throw LauncherLinkException.create(cause, "The launcher link {0} was refused: {1}", address, cause.getMessage());
        } catch (TimeoutException exception) {
            client.close();

            throw LauncherLinkException.create(exception, "The launcher link {0} did not answer the hello.", address);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            client.close();

            throw LauncherLinkException.create(exception, "The connect to the launcher link {0} was interrupted.", address);
        }

        return client;
    }


    private final @NotNull LinkConnection connection;
    private final @NotNull String version;

    private final @NotNull CompletableFuture<Void> accepted;
    private final @NotNull AtomicInteger lastCorrelation;
    private final @NotNull Map<@NotNull Integer, @NotNull CompletableFuture<byte @NotNull []>> pendingRequests;
    private final @NotNull Map<@NotNull String, @NotNull List<@NotNull Consumer<byte @NotNull []>>> eventListeners;
    private final @NotNull List<@NotNull Runnable> closeListeners;
    private final @NotNull AtomicBoolean closed;

    /**
     * Creates a {@link LauncherLinkClient} instance.
     *
     * @param connection the connection to the server, which the client closes
     * @param version    the SC4J version of the client
     */
    private LauncherLinkClient(@NotNull LinkConnection connection, @NotNull String version) {
        this.connection = connection;
        this.version    = version;

        this.accepted        = new CompletableFuture<>();
        this.lastCorrelation = new AtomicInteger(LinkFrame.NO_CORRELATION);
        this.pendingRequests = new ConcurrentHashMap<>();
        this.eventListeners  = new ConcurrentHashMap<>();
        this.closeListeners  = new CopyOnWriteArrayList<>();
        this.closed          = new AtomicBoolean(false);
    }

    /**
     * @return {@code true} if the link is closed, {@code false} otherwise
     */
    public boolean isClosed() {
        return this.closed.get();
    }

    /**
     * Sends a request to a service of the launcher.
     *
     * @param service the service name
     * @param payload the request payload
     * @return the future of the response payload; it fails with a {@link LauncherLinkException} when the service answers with a failure, the request is longer than a frame may be, or the link closes before the answer
     */
    public @NotNull CompletableFuture<byte @NotNull []> request(@NotNull String service, byte @NotNull [] payload) {
        CompletableFuture<byte[]> response = new CompletableFuture<>();
        int correlation = this.lastCorrelation.updateAndGet(last -> last == Integer.MAX_VALUE ? LinkFrame.NO_CORRELATION + 1 : last + 1);

        this.pendingRequests.put(correlation, response);

        // A close that ran before the request was listed has not failed it.
        if (this.closed.get()) {
            this.pendingRequests.remove(correlation);
            response.completeExceptionally(LauncherLinkException.create("The launcher link is closed."));

            return response;
        }

        try {
            this.connection.write(new LinkFrame(LinkFrameKind.REQUEST, correlation, service, payload));
        } catch (LauncherLinkException exception) {
            this.pendingRequests.remove(correlation);
            response.completeExceptionally(exception);
        } catch (IOException exception) {
            this.pendingRequests.remove(correlation);
            response.completeExceptionally(LauncherLinkException.create(exception, "The launcher link failed: {0}", exception.getMessage()));
            this.close();
        }

        return response;
    }

    /**
     * Adds a listener of the events of a service. It runs on the client's reader thread; what it throws is logged and ignored.
     *
     * @param service  the service name
     * @param listener receives the payload of each event
     */
    public void addEventListener(@NotNull String service, @NotNull Consumer<byte @NotNull []> listener) {
        this.eventListeners.computeIfAbsent(service, name -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Adds a listener that runs once when the link closes, on the thread that closes it; added to a closed link, it runs at once.
     *
     * @param listener the listener
     */
    public void addCloseListener(@NotNull Runnable listener) {
        this.closeListeners.add(listener);

        if (this.closed.get()) {
            this.runCloseListener(listener);
        }
    }

    private void readFrames() {
        LauncherLinkException failure = null;

        try {
            LinkFrame hello = this.connection.read();

            if (hello == null || hello.getKind() != LinkFrameKind.HELLO) {
                throw LauncherLinkException.create("The launcher closed the link instead of answering the hello.");
            }

            String serverVersion = hello.readHelloVersion();

            if (!serverVersion.equals(this.version)) {
                throw LauncherLinkException.create("The launcher runs SC4J {0} instead of {1}.", serverVersion, this.version);
            }

            this.accepted.complete(null);

            LinkFrame frame = this.connection.read();

            while (frame != null) {
                this.dispatch(frame);
                frame = this.connection.read();
            }
        } catch (LauncherLinkException exception) {
            failure = exception;
        } catch (IOException exception) {
            failure = LauncherLinkException.create(exception, "The launcher link failed: {0}", exception.getMessage());
        } finally {
            this.accepted.completeExceptionally(failure != null ? failure : LauncherLinkException.create("The launcher link is closed."));
            this.close();
        }
    }

    private void dispatch(@NotNull LinkFrame frame) throws LauncherLinkException {
        switch (frame.getKind()) {
            case RESPONSE -> {
                CompletableFuture<byte[]> response = this.pendingRequests.remove(frame.getCorrelation());

                if (response != null) {
                    response.complete(frame.getPayload());
                }
            }
            case FAILURE -> {
                CompletableFuture<byte[]> response = this.pendingRequests.remove(frame.getCorrelation());

                if (response != null) {
                    response.completeExceptionally(LauncherLinkException.create("{0}", new String(frame.getPayload(), StandardCharsets.UTF_8)));
                }
            }
            case EVENT -> {
                for (Consumer<byte[]> listener : this.eventListeners.getOrDefault(frame.getService(), List.of())) {
                    try {
                        listener.accept(frame.getPayload());
                    } catch (RuntimeException exception) {
                        LOGGER.exception(LogLevel.WARNING, exception, "LAUNCHER LINK: an event listener of {0} failed", frame.getService());
                    }
                }
            }
            default -> throw LauncherLinkException.create("The launcher sent a frame of the kind {0}.", frame.getKind());
        }
    }

    private void runCloseListener(@NotNull Runnable listener) {
        // Only the call that removes the listener runs it, so it runs once.
        if (this.closeListeners.remove(listener)) {
            IgnoreException.execute(
                    listener::run
            );
        }
    }

    /**
     * Closes the link: every pending request fails with a {@link LauncherLinkException} and the close listeners run.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        IgnoreException.execute(
                this.connection::close
        );

        for (Integer correlation : this.pendingRequests.keySet()) {
            CompletableFuture<byte[]> response = this.pendingRequests.remove(correlation);

            if (response != null) {
                response.completeExceptionally(LauncherLinkException.create("The launcher link closed before the answer."));
            }
        }

        for (Runnable listener : this.closeListeners) {
            this.runCloseListener(listener);
        }
    }
}
