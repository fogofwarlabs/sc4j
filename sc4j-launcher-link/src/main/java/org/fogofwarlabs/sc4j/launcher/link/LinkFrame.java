package org.fogofwarlabs.sc4j.launcher.link;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Holds one frame of a launcher link and its wire form: the frame length ({@code int}, big-endian, the bytes after it), the kind code (one
 * byte), the correlation ({@code int}), the service name (two-byte length and modified UTF-8) and the payload (the remaining bytes).
 * <p>
 * A hello frame has no service name; its payload is the SC4J version (two-byte length and modified UTF-8) followed by the token, which only
 * the client's hello carries.
 */
final class LinkFrame {
    /**
     * The longest frame, in bytes after the frame length: 1 MiB. A longer frame closes the connection.
     */
    static final int MAXIMUM_FRAME_LENGTH = 1 << 20;
    /**
     * The correlation of frames that answer no request: hellos and events.
     */
    static final int NO_CORRELATION = 0;

    private static final @NotNull String NO_SERVICE = "";
    private static final int MINIMUM_FRAME_LENGTH = Byte.BYTES + Integer.BYTES + Short.BYTES;
    private static final byte @NotNull [] NO_TOKEN = new byte[0];

    /**
     * Creates the hello frame that a server answers an accepted client with.
     *
     * @param version the SC4J version of the server
     * @return the created {@link LinkFrame} instance
     * @throws IOException if the version cannot be encoded
     */
    static @NotNull LinkFrame createHello(@NotNull String version) throws IOException {
        return createHello(version, NO_TOKEN);
    }

    /**
     * Creates the hello frame of a client.
     *
     * @param version the SC4J version of the client
     * @param token   the token of the link
     * @return the created {@link LinkFrame} instance
     * @throws IOException if the version cannot be encoded
     */
    static @NotNull LinkFrame createHello(@NotNull String version, byte @NotNull [] token) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        output.writeUTF(version);
        output.write(token);

        return new LinkFrame(LinkFrameKind.HELLO, NO_CORRELATION, NO_SERVICE, bytes.toByteArray());
    }

    /**
     * Creates a failure frame.
     *
     * @param correlation the correlation of the failed request
     * @param service     the service of the failed request
     * @param message     the failure message
     * @return the created {@link LinkFrame} instance
     */
    static @NotNull LinkFrame createFailure(int correlation, @NotNull String service, @NotNull String message) {
        return new LinkFrame(LinkFrameKind.FAILURE, correlation, service, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the next frame from a blocking channel.
     *
     * @param channel the channel
     * @return the frame, or {@code null} at the end of the stream before a frame starts
     * @throws LauncherLinkException if the frame is longer than {@link #MAXIMUM_FRAME_LENGTH}, malformed or cut off by the end of the stream
     * @throws IOException           if the channel fails
     */
    static @Nullable LinkFrame read(@NotNull ReadableByteChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(Integer.BYTES);

        if (!readFully(channel, header, true)) {
            return null;
        }

        int length = header.flip().getInt();

        if (length < MINIMUM_FRAME_LENGTH || length > MAXIMUM_FRAME_LENGTH) {
            throw LauncherLinkException.create("Invalid launcher link frame length: {0}", length);
        }

        ByteBuffer body = ByteBuffer.allocate(length);

        readFully(channel, body, false);

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(body.array()));
        int code = input.readUnsignedByte();
        LinkFrameKind kind = LinkFrameKind.find(code);

        if (kind == null) {
            throw LauncherLinkException.create("Unknown launcher link frame kind: {0}", code);
        }

        try {
            return new LinkFrame(kind, input.readInt(), input.readUTF(), input.readAllBytes());
        } catch (EOFException | UTFDataFormatException exception) {
            throw LauncherLinkException.create(exception, "Malformed launcher link frame: {0}", exception.getMessage());
        }
    }

    private static boolean readFully(@NotNull ReadableByteChannel channel, @NotNull ByteBuffer target, boolean endAllowed) throws IOException {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                if (endAllowed && target.position() == 0) {
                    return false;
                }

                throw LauncherLinkException.create("The launcher link ended inside a frame.");
            }
        }

        return true;
    }


    private final @NotNull LinkFrameKind kind;
    private final int correlation;
    private final @NotNull String service;
    private final byte @NotNull [] payload;

    /**
     * Creates a {@link LinkFrame} instance.
     *
     * @param kind        the kind
     * @param correlation the correlation that pairs a request with its response or failure, {@link #NO_CORRELATION} for hellos and events
     * @param service     the service name, empty for hellos
     * @param payload     the payload, which the frame keeps
     */
    LinkFrame(@NotNull LinkFrameKind kind, int correlation, @NotNull String service, byte @NotNull [] payload) {
        this.kind        = kind;
        this.correlation = correlation;
        this.service     = service;
        this.payload     = payload;
    }

    /**
     * @return the kind
     */
    @NotNull LinkFrameKind getKind() {
        return this.kind;
    }

    /**
     * @return the correlation that pairs a request with its response or failure
     */
    int getCorrelation() {
        return this.correlation;
    }

    /**
     * @return the service name, empty for hellos
     */
    @NotNull String getService() {
        return this.service;
    }

    /**
     * @return the payload, not copied
     */
    byte @NotNull [] getPayload() {
        return this.payload;
    }

    /**
     * Reads the SC4J version of a hello frame.
     *
     * @return the version
     * @throws LauncherLinkException if the payload holds no version
     */
    @NotNull String readHelloVersion() throws LauncherLinkException {
        try {
            return new DataInputStream(new ByteArrayInputStream(this.payload)).readUTF();
        } catch (IOException exception) {
            throw LauncherLinkException.create(exception, "Malformed launcher link hello: {0}", exception.getMessage());
        }
    }

    /**
     * Reads the token of a client's hello frame.
     *
     * @return the token, empty in a server's hello
     * @throws LauncherLinkException if the payload holds no version
     */
    byte @NotNull [] readHelloToken() throws LauncherLinkException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(this.payload));

        try {
            input.readUTF();

            return input.readAllBytes();
        } catch (IOException exception) {
            throw LauncherLinkException.create(exception, "Malformed launcher link hello: {0}", exception.getMessage());
        }
    }

    /**
     * Encodes the frame in its wire form.
     *
     * @return the frame bytes, ready to write
     * @throws LauncherLinkException if the frame is longer than {@link #MAXIMUM_FRAME_LENGTH}
     * @throws IOException           if the service name cannot be encoded
     */
    @NotNull ByteBuffer encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        output.writeInt(0);
        output.writeByte(this.kind.getCode());
        output.writeInt(this.correlation);
        output.writeUTF(this.service);
        output.write(this.payload);

        int length = bytes.size() - Integer.BYTES;

        if (length > MAXIMUM_FRAME_LENGTH) {
            throw LauncherLinkException.create("The launcher link frame of {0} has {1} bytes; at most {2} fit.", this.service, length,
                                               MAXIMUM_FRAME_LENGTH);
        }

        return ByteBuffer.wrap(bytes.toByteArray()).putInt(0, length);
    }
}
