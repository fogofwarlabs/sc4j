package org.fogofwarlabs.sc4j.beaconlink.channel;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.ModFingerprint;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Asks another participant for a channel: the number under which the offering participant receives the channel's datagrams, its ephemeral
 * X25519 public key and its address candidates. An offer to a room's host also asks to join the room: it names the room number and carries
 * the joiner's mod identity in its wire form, which the host checks against its own, and for a private room the proof of its password
 * ({@link JoinProofUtils}). The offer is sent through every registered node and again until the answer arrives; every copy carries the same
 * fields.
 * <p>
 * Fields after the sender's connection: the initiator's receive number (eight bytes), the ephemeral public key ({@value EphemeralKeyPair#KEY_LENGTH}
 * bytes), the join flag (one byte); with the flag {@code 1} the room number (unsigned 32-bit), the mod identity (length byte and bytes) and the
 * password proof (a length byte, {@code 0} or {@value JoinProofUtils#PROOF_LENGTH}, and the bytes); then the address candidates.
 */
public final class ChannelOffer extends ChannelSignal {
    /**
     * The room number of an offer that does not join a room.
     */
    public static final long NO_ROOM = -1L;

    private static final int JOIN_FLAG = 1;

    /**
     * Reads the fields after the kind and the sender's connection.
     *
     * @param reader           the reader
     * @param senderConnection the connection number of the sender's session
     * @return the offer, or {@code null} if the reader failed
     */
    static @Nullable ChannelOffer read(@NotNull WireReader reader, long senderConnection) {
        long receiveNumber = reader.readInt64();
        byte[] ephemeralKey = reader.readBytes(EphemeralKeyPair.KEY_LENGTH);
        int join = reader.readUnsigned8();
        long roomNumber = NO_ROOM;
        ModFingerprint identity = null;
        byte[] proof = null;

        if (join == JOIN_FLAG) {
            roomNumber = reader.readUnsigned32();
            identity   = ModFingerprint.read(reader);

            int proofLength = reader.readUnsigned8();

            if (proofLength == JoinProofUtils.PROOF_LENGTH) {
                proof = reader.readBytes(proofLength);
            } else if (proofLength != 0) {
                reader.fail();
            }

            if (identity == null) {
                reader.fail();
            }
        } else if (join != 0) {
            reader.fail();
        }

        List<InetSocketAddress> candidates = readCandidates(reader);

        if (candidates == null || reader.isFailed()) {
            return null;
        }

        return new ChannelOffer(senderConnection, receiveNumber, ephemeralKey, roomNumber, identity, proof, candidates);
    }


    private final long receiveNumber;
    private final byte @NotNull [] ephemeralKey;
    private final long roomNumber;
    private final @Nullable ModFingerprint identity;
    private final byte @Nullable [] proof;
    private final @NotNull List<@NotNull InetSocketAddress> candidates;

    /**
     * Creates a {@link ChannelOffer} instance of a channel that joins no room.
     *
     * @param senderConnection the connection number of the initiator's session
     * @param receiveNumber    the number under which the initiator receives the channel's datagrams
     * @param ephemeralKey     the initiator's ephemeral public key
     * @param candidates       the initiator's address candidates
     * @throws IllegalArgumentException if the key length is not {@value EphemeralKeyPair#KEY_LENGTH} or there are more than {@value #MAXIMUM_CANDIDATES} candidates
     */
    public ChannelOffer(long senderConnection, long receiveNumber, byte @NotNull [] ephemeralKey,
                        @NotNull List<@NotNull InetSocketAddress> candidates) {
        this(senderConnection, receiveNumber, ephemeralKey, NO_ROOM, null, null, candidates);
    }

    /**
     * Creates a {@link ChannelOffer} instance without a password proof. The key has {@value EphemeralKeyPair#KEY_LENGTH} bytes, there are at
     * most {@value #MAXIMUM_CANDIDATES} candidates, and the room number is {@link #NO_ROOM} exactly when the identity is {@code null}.
     *
     * @param senderConnection the connection number of the initiator's session
     * @param receiveNumber    the number under which the initiator receives the channel's datagrams
     * @param ephemeralKey     the initiator's ephemeral public key
     * @param roomNumber       the number of the room the initiator joins, or {@link #NO_ROOM}
     * @param identity         the initiator's mod identity in its wire form when it joins a room, otherwise {@code null}
     * @param candidates       the initiator's address candidates
     * @throws IllegalArgumentException if the key length, the candidate count, or the room number and the identity are invalid
     */
    public ChannelOffer(long senderConnection, long receiveNumber, byte @NotNull [] ephemeralKey, long roomNumber,
                        @Nullable ModFingerprint identity, @NotNull List<@NotNull InetSocketAddress> candidates) {
        this(senderConnection, receiveNumber, ephemeralKey, roomNumber, identity, null, candidates);
    }

    /**
     * Creates a {@link ChannelOffer} instance. The key has {@value EphemeralKeyPair#KEY_LENGTH} bytes, there are at most
     * {@value #MAXIMUM_CANDIDATES} candidates, the room number is {@link #NO_ROOM} exactly when the identity is {@code null}, and a proof has
     * {@value JoinProofUtils#PROOF_LENGTH} bytes and comes only with a join.
     *
     * @param senderConnection the connection number of the initiator's session
     * @param receiveNumber    the number under which the initiator receives the channel's datagrams
     * @param ephemeralKey     the initiator's ephemeral public key
     * @param roomNumber       the number of the room the initiator joins, or {@link #NO_ROOM}
     * @param identity         the initiator's mod identity in its wire form when it joins a room, otherwise {@code null}
     * @param proof            the proof of a private room's password (copied) when the initiator joins a private room, otherwise {@code null}
     * @param candidates       the initiator's address candidates
     * @throws IllegalArgumentException if the key length, the candidate count, the room number and the identity, or the proof are invalid
     */
    public ChannelOffer(long senderConnection, long receiveNumber, byte @NotNull [] ephemeralKey, long roomNumber,
                        @Nullable ModFingerprint identity, byte @Nullable [] proof, @NotNull List<@NotNull InetSocketAddress> candidates) {
        super(senderConnection);

        if (identity == null ? roomNumber != NO_ROOM : roomNumber < 0L || roomNumber > RoomKey.MAXIMUM_NUMBER) {
            throw Exceptions.newIllegalArgumentException("The room number {0} does not fit an offer with the identity {1}.", roomNumber, identity);
        }

        if (proof != null && (identity == null || proof.length != JoinProofUtils.PROOF_LENGTH)) {
            throw Exceptions.newIllegalArgumentException("A proof of {0} bytes does not fit an offer with the identity {1}.", proof.length, identity);
        }

        this.receiveNumber = receiveNumber;
        this.ephemeralKey  = copyEphemeralKey(ephemeralKey);
        this.roomNumber    = roomNumber;
        this.identity      = identity;
        this.proof         = proof == null ? null : proof.clone();
        this.candidates    = copyCandidates(candidates);
    }

    /**
     * @return {@link ChannelSignalKind#OFFER}
     */
    @Override
    public @NotNull ChannelSignalKind getKind() {
        return ChannelSignalKind.OFFER;
    }

    /**
     * @return the number under which the initiator receives the channel's datagrams
     */
    public long getReceiveNumber() {
        return this.receiveNumber;
    }

    /**
     * @return a copy of the initiator's ephemeral public key
     */
    public byte @NotNull [] getEphemeralKey() {
        return this.ephemeralKey.clone();
    }

    /**
     * @return {@code true} if the initiator joins a room of the target, {@code false} otherwise (a channel that joins no room)
     */
    public boolean isJoin() {
        return this.identity != null;
    }

    /**
     * @return the number of the room the initiator joins, or {@link #NO_ROOM}
     */
    public long getRoomNumber() {
        return this.roomNumber;
    }

    /**
     * @return the initiator's mod identity in its wire form when it joins a room, otherwise {@code null}
     */
    public @Nullable ModFingerprint getIdentity() {
        return this.identity;
    }

    /**
     * @return a copy of the proof of a private room's password, or {@code null} if the offer carries none
     */
    public byte @Nullable [] getProof() {
        return this.proof == null ? null : this.proof.clone();
    }

    /**
     * @return the initiator's address candidates
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
        ModFingerprint joined = this.identity;

        writer.writeInt64(this.receiveNumber);
        writer.writeBytes(this.ephemeralKey);

        if (joined == null) {
            writer.writeUnsigned8(0);
        } else {
            writer.writeUnsigned8(JOIN_FLAG);
            writer.writeUnsigned32(this.roomNumber);
            joined.write(writer);

            if (this.proof == null) {
                writer.writeUnsigned8(0);
            } else {
                writer.writeUnsigned8(this.proof.length);
                writer.writeBytes(this.proof);
            }
        }

        writeCandidates(writer, this.candidates);
    }
}
