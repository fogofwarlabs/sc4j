package org.fogofwarlabs.sc4j.launcher.link;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Answers the requests of one service of a {@link LauncherLinkServer}. It is called on the reader thread of the client that sent the
 * request, so it returns at once and completes the future later, on any thread.
 */
@FunctionalInterface
public interface LinkRequestHandler {
    /**
     * Handles a request. A future that fails, or an exception thrown here, answers the client with a failure that carries the message.
     *
     * @param payload the request payload
     * @return the future of the response payload
     */
    @NotNull CompletableFuture<byte @NotNull []> handle(byte @NotNull [] payload);
}
