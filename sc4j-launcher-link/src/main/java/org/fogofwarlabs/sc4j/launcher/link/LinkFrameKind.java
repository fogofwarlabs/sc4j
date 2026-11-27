package org.fogofwarlabs.sc4j.launcher.link;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists the kinds of the frames of a launcher link, with their wire codes.
 */
enum LinkFrameKind {
    /**
     * The first frame of each side: the client's carries the SC4J version and the token, the server's answer the SC4J version.
     */
    HELLO(1),
    /**
     * A request of the client to a service, answered by a {@link #RESPONSE} or a {@link #FAILURE} with the same correlation.
     */
    REQUEST(2),
    /**
     * The result of a request.
     */
    RESPONSE(3),
    /**
     * The failure of a request, with its message as UTF-8 payload.
     */
    FAILURE(4),
    /**
     * An event of a service that the server sends to every client.
     */
    EVENT(5);

    private static final @NotNull LinkFrameKind @NotNull [] VALUES = values();

    /**
     * Finds a kind by its wire code.
     *
     * @param code the wire code
     * @return the kind, or {@code null} if no kind has the code
     */
    static @Nullable LinkFrameKind find(int code) {
        for (LinkFrameKind kind : VALUES) {
            if (kind.code == code) {
                return kind;
            }
        }

        return null;
    }


    private final int code;

    /**
     * Creates a {@link LinkFrameKind} instance.
     *
     * @param code the wire code
     */
    LinkFrameKind(int code) {
        this.code = code;
    }

    /**
     * @return the wire code
     */
    int getCode() {
        return this.code;
    }
}
