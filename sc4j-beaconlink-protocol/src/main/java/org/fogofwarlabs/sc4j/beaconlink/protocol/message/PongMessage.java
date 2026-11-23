package org.fogofwarlabs.sc4j.beaconlink.protocol.message;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;

/**
 * Answers a {@link PingMessage}: the source address the node observed and the node software version. The sender of the ping learns that the
 * node is reachable, the node's key (the envelope's sender key, checked by its signature), its own observed address and the version.
 * <p>
 * Body: the observed address and the node software version (UTF-8, at most {@value HelloMessage#MAXIMUM_SOFTWARE_LENGTH} bytes).
 */
public final class PongMessage extends Message {
    /**
     * Reads the body.
     *
     * @param reader the body reader
     * @return the body, or {@code null} if the reader failed
     */
    public static @Nullable PongMessage read(@NotNull WireReader reader) {
        InetSocketAddress address = reader.readAddress();
        String software = reader.readText(HelloMessage.MAXIMUM_SOFTWARE_LENGTH);

        if (address == null || reader.isFailed()) {
            return null;
        }

        return new PongMessage(address, software);
    }


    private final @NotNull InetSocketAddress address;
    private final @NotNull String software;

    /**
     * Creates a {@link PongMessage} instance.
     *
     * @param address  the observed source address of the ping, resolved
     * @param software the node software version
     * @throws IllegalArgumentException if the address is not resolved or the version is longer than {@value HelloMessage#MAXIMUM_SOFTWARE_LENGTH} UTF-8 bytes
     */
    public PongMessage(@NotNull InetSocketAddress address, @NotNull String software) {
        if (address.isUnresolved() || WireWriter.getTextLength(software) > HelloMessage.MAXIMUM_SOFTWARE_LENGTH) {
            throw Exceptions.newIllegalArgumentException("The address {0} is not resolved or the software version {1} is longer than {2} bytes.",
                                                         address, software, HelloMessage.MAXIMUM_SOFTWARE_LENGTH);
        }

        this.address  = address;
        this.software = software;
    }

    /**
     * @return {@link MessageKind#PONG}
     */
    @Override
    public @NotNull MessageKind getKind() {
        return MessageKind.PONG;
    }

    /**
     * @return the source address the node observed for the ping
     */
    public @NotNull InetSocketAddress getAddress() {
        return this.address;
    }

    /**
     * @return the node software version
     */
    public @NotNull String getSoftware() {
        return this.software;
    }


    /**
     * Writes the body fields.
     *
     * @param writer the writer
     */
    @Override
    public void writeBody(@NotNull WireWriter writer) {
        writer.writeAddress(this.address);
        writer.writeText(this.software, HelloMessage.MAXIMUM_SOFTWARE_LENGTH);
    }
}
