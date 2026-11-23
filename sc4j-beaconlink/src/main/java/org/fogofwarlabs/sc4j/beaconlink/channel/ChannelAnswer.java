package org.fogofwarlabs.sc4j.beaconlink.channel;

import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Accepts a {@link ChannelOffer}: it repeats the initiator's receive number, so that the initiator knows which offer is answered, and gives
 * the responder's receive number, ephemeral X25519 public key and address candidates. The answer goes back through the node the offer came
 * through; a repeated offer gets the same answer.
 * <p>
 * Fields after the sender's connection: the initiator's receive number (eight bytes), the responder's receive number (eight bytes), the ephemeral
 * public key ({@value EphemeralKeyPair#KEY_LENGTH} bytes) and the address candidates.
 */
public final class ChannelAnswer extends ChannelSignal {
    /**
     * Reads the fields after the kind and the sender's connection.
     *
     * @param reader           the reader
     * @param senderConnection the connection number of the sender's session
     * @return the answer, or {@code null} if the reader failed
     */
    static @Nullable ChannelAnswer read(@NotNull WireReader reader, long senderConnection) {
        long offerNumber = reader.readInt64();
        long receiveNumber = reader.readInt64();
        byte[] ephemeralKey = reader.readBytes(EphemeralKeyPair.KEY_LENGTH);
        List<InetSocketAddress> candidates = readCandidates(reader);

        if (candidates == null || reader.isFailed()) {
            return null;
        }

        return new ChannelAnswer(senderConnection, offerNumber, receiveNumber, ephemeralKey, candidates);
    }


    private final long offerNumber;
    private final long receiveNumber;
    private final byte @NotNull [] ephemeralKey;
    private final @NotNull List<@NotNull InetSocketAddress> candidates;

    /**
     * Creates a {@link ChannelAnswer} instance.
     *
     * @param senderConnection the connection number of the responder's session
     * @param offerNumber      the initiator's receive number from the answered offer
     * @param receiveNumber    the number under which the responder receives the channel's datagrams
     * @param ephemeralKey     the responder's ephemeral public key
     * @param candidates       the responder's address candidates
     * @throws IllegalArgumentException if the key length is not {@value EphemeralKeyPair#KEY_LENGTH} or there are more than {@value #MAXIMUM_CANDIDATES} candidates
     */
    public ChannelAnswer(long senderConnection, long offerNumber, long receiveNumber, byte @NotNull [] ephemeralKey,
                         @NotNull List<@NotNull InetSocketAddress> candidates) {
        super(senderConnection);

        this.offerNumber   = offerNumber;
        this.receiveNumber = receiveNumber;
        this.ephemeralKey  = copyEphemeralKey(ephemeralKey);
        this.candidates    = copyCandidates(candidates);
    }

    /**
     * @return {@link ChannelSignalKind#ANSWER}
     */
    @Override
    public @NotNull ChannelSignalKind getKind() {
        return ChannelSignalKind.ANSWER;
    }

    /**
     * @return the initiator's receive number from the answered offer
     */
    public long getOfferNumber() {
        return this.offerNumber;
    }

    /**
     * @return the number under which the responder receives the channel's datagrams
     */
    public long getReceiveNumber() {
        return this.receiveNumber;
    }

    /**
     * @return a copy of the responder's ephemeral public key
     */
    public byte @NotNull [] getEphemeralKey() {
        return this.ephemeralKey.clone();
    }

    /**
     * @return the responder's address candidates
     */
    public @NotNull List<@NotNull InetSocketAddress> getCandidates() {
        return this.candidates;
    }


    /**
     * Writes the kind's fields.
     *
     * @param writer the writer
     */
    @Override
    protected void writeFields(@NotNull WireWriter writer) {
        writer.writeInt64(this.offerNumber);
        writer.writeInt64(this.receiveNumber);
        writer.writeBytes(this.ephemeralKey);
        writeCandidates(writer, this.candidates);
    }
}
