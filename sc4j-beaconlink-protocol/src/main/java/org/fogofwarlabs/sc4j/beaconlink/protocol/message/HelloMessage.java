package org.fogofwarlabs.sc4j.beaconlink.protocol.message;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Introduces a peer to another peer, or answers an introduction. The peer's key is the envelope's sender key and its address is the source
 * address the receiver observes; a peer cannot claim another address. The request is padded (its source is not proved yet); the reply to
 * the receiver's own hello proves the sender's address and key.
 * <p>
 * Body: the {@link PeerCard} (proof nonce, reachable flag), the capabilities (two bytes, a bit set: {@link #ROUTING_CAPABILITY},
 * {@link #TOPIC_CAPABILITY}; unknown bits are ignored, so later additions need no version change) and the peer software version (UTF-8, at
 * most {@value #MAXIMUM_SOFTWARE_LENGTH} bytes).
 */
public final class HelloMessage extends Message {
    /**
     * The largest UTF-8 length of the software version.
     */
    public static final int MAXIMUM_SOFTWARE_LENGTH = 32;
    /**
     * The capability of a peer that answers {@link FindPeersMessage} requests from its routing table.
     */
    public static final int ROUTING_CAPABILITY = 1;
    /**
     * The capability of a peer that takes part in topics: it answers {@link GetTopicMessage} requests, stores announcements and joins swarms.
     */
    public static final int TOPIC_CAPABILITY = 2;

    private static final int MAXIMUM_CAPABILITIES = 0xffff;

    /**
     * Reads the body.
     *
     * @param reader the body reader
     * @return the body, or {@code null} if the reader failed
     */
    public static @Nullable HelloMessage read(@NotNull WireReader reader) {
        PeerCard card = PeerCard.read(reader);
        int capabilities = reader.readUnsigned16();
        String software = reader.readText(MAXIMUM_SOFTWARE_LENGTH);

        return card == null || reader.isFailed() ? null : new HelloMessage(card, capabilities, software);
    }


    private final @NotNull PeerCard card;
    private final int capabilities;
    private final @NotNull String software;

    /**
     * Creates a {@link HelloMessage} instance.
     *
     * @param card         the sender's card
     * @param capabilities the sender's capability bits
     * @param software     the peer software version
     * @throws IllegalArgumentException if the capabilities do not fit two bytes or the version is longer than {@value #MAXIMUM_SOFTWARE_LENGTH} UTF-8 bytes
     */
    public HelloMessage(@NotNull PeerCard card, int capabilities, @NotNull String software) {
        if (capabilities < 0 || capabilities > MAXIMUM_CAPABILITIES) {
            throw Exceptions.newIllegalArgumentException("The capabilities {0} do not fit two bytes.", capabilities);
        }

        if (WireWriter.getTextLength(software) > MAXIMUM_SOFTWARE_LENGTH) {
            throw Exceptions.newIllegalArgumentException("The software version {0} is longer than {1} UTF-8 bytes.",
                                                         software, MAXIMUM_SOFTWARE_LENGTH);
        }

        this.card         = card;
        this.capabilities = capabilities;
        this.software     = software;
    }

    /**
     * @return {@link MessageKind#HELLO}
     */
    @Override
    public @NotNull MessageKind getKind() {
        return MessageKind.HELLO;
    }

    /**
     * @return the sender's card
     */
    public @NotNull PeerCard getCard() {
        return this.card;
    }

    /**
     * @return the sender's capability bits
     */
    public int getCapabilities() {
        return this.capabilities;
    }

    /**
     * @return the peer software version
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
        this.card.write(writer);
        writer.writeUnsigned16(this.capabilities);
        writer.writeText(this.software, MAXIMUM_SOFTWARE_LENGTH);
    }
}
