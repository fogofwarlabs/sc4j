package org.fogofwarlabs.sc4j.beaconlink.protocol.community;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.RecordKind;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Tells the members of a topic that one connection of a player is online: a record of the topic, signed by the player key and carrying the
 * player's stamp ({@link PlayerStamp}), which binds the content's display name to the key. Every connection of a player (the launcher, a
 * game, another computer) signs a presence of its own into every topic it takes part in (its mod's topic, its channels, its player's own
 * topic), refreshes it before it expires and signs a new one when its content changes; of the presences of one connection of a player in one
 * topic, the higher sequence replaces the lower, and the presences of the player's other connections stand beside it.
 * <p>
 * On the wire: the flags (one byte, zero), the topic, the player key, the connection number (64-bit), the stamp nonce (64-bit), the sequence
 * (unsigned 32-bit), the expiry (epoch milliseconds, 64-bit), the {@link PresenceContent} and the Ed25519 signature of the player over a
 * domain prefix and every byte before it. At most {@value #MAXIMUM_ENCODED_LENGTH} bytes.
 */
public final class PresenceRecord extends TopicRecord {
    /**
     * The largest sequence number.
     */
    public static final long MAXIMUM_SEQUENCE = 0xffffffffL;
    /**
     * The largest length of a presence's wire form.
     */
    public static final int MAXIMUM_ENCODED_LENGTH = 1 + TopicKey.LENGTH + IdentityKey.LENGTH + Long.BYTES + Long.BYTES + Integer.BYTES +
                                                     Long.BYTES + PresenceContent.MAXIMUM_ENCODED_LENGTH + IdentityKey.SIGNATURE_LENGTH;

    private static final byte @NotNull [] SIGNATURE_DOMAIN = "Beacon.Link presence 1\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * Creates a {@link PresenceRecord} instance signed by the player.
     *
     * @param author       the player's key pair
     * @param stamp        the player's stamp
     * @param topic        the topic of the presence
     * @param connection   the number of the player's connection the presence tells of
     * @param sequence     the sequence, higher than every earlier presence of the connection in the topic
     * @param expiryMillis the epoch millisecond at which the presence expires
     * @param content      what the presence tells
     * @return the created {@link PresenceRecord} instance
     * @throws IllegalArgumentException if the sequence is out of range, or the stamp is of another key or another name than the content's display name
     */
    public static @NotNull PresenceRecord sign(@NotNull IdentityKeyPair author, @NotNull PlayerStamp stamp, @NotNull TopicKey topic, long connection,
                                               long sequence, long expiryMillis, @NotNull PresenceContent content) {
        if (sequence < 0L || sequence > MAXIMUM_SEQUENCE || !stamp.getKey().equals(author.getPublicKey()) ||
            !stamp.getName().equals(content.getDisplayName())) {
            throw Exceptions.newIllegalArgumentException("The sequence {0} is outside 0..{1}, or the stamp {2} is not of the key {3} and the name {4}.",
                                                         sequence, MAXIMUM_SEQUENCE, stamp, author.getPublicKey(), content.getDisplayName());
        }

        byte[] unsigned = encodeUnsigned(topic, author.getPublicKey(), connection, stamp.getNonce(), sequence, expiryMillis, content);

        return new PresenceRecord(topic, stamp, connection, sequence, expiryMillis, content, unsigned,
                                  TopicRecord.sign(author, SIGNATURE_DOMAIN, unsigned));
    }

    /**
     * Reads a presence from the wire; the signature and the stamp (of the key and the display name) are checked separately.
     *
     * @param reader the reader
     * @return the presence, or {@code null} if the reader failed or a field is out of range
     */
    public static @Nullable PresenceRecord read(@NotNull WireReader reader) {
        int flags = reader.readUnsigned8();
        TopicKey topic = TopicKey.read(reader);
        IdentityKey author = IdentityKey.read(reader);
        long connection = reader.readInt64();
        long stampNonce = reader.readInt64();
        long sequence = reader.readUnsigned32();
        long expiryMillis = reader.readInt64();
        PresenceContent content = PresenceContent.read(reader);
        byte[] signature = reader.readBytes(IdentityKey.SIGNATURE_LENGTH);

        if (flags != 0 || topic == null || author == null || content == null || reader.isFailed()) {
            reader.fail();

            return null;
        }

        return new PresenceRecord(topic, new PlayerStamp(author, content.getDisplayName(), stampNonce), connection, sequence, expiryMillis,
                                  content, encodeUnsigned(topic, author, connection, stampNonce, sequence, expiryMillis, content), signature);
    }

    private static byte @NotNull [] encodeUnsigned(@NotNull TopicKey topic, @NotNull IdentityKey author, long connection, long stampNonce,
                                                   long sequence, long expiryMillis, @NotNull PresenceContent content) {
        WireWriter writer = new WireWriter(MAXIMUM_ENCODED_LENGTH);

        writer.writeUnsigned8(0);
        topic.write(writer);
        author.write(writer);
        writer.writeInt64(connection);
        writer.writeInt64(stampNonce);
        writer.writeUnsigned32(sequence);
        writer.writeInt64(expiryMillis);
        content.write(writer);

        return writer.toByteArray();
    }


    private final @NotNull TopicKey topic;
    private final @NotNull PlayerStamp stamp;
    private final long connection;
    private final long sequence;
    private final long expiryMillis;
    private final @NotNull PresenceContent content;

    private PresenceRecord(@NotNull TopicKey topic, @NotNull PlayerStamp stamp, long connection, long sequence, long expiryMillis,
                           @NotNull PresenceContent content, byte @NotNull [] unsigned, byte @NotNull [] signature) {
        super(unsigned, signature);

        this.topic        = topic;
        this.stamp        = stamp;
        this.connection   = connection;
        this.sequence     = sequence;
        this.expiryMillis = expiryMillis;
        this.content      = content;
    }

    /**
     * @return {@link RecordKind#PRESENCE}
     */
    @Override
    public @NotNull RecordKind getRecordKind() {
        return RecordKind.PRESENCE;
    }

    /**
     * @return the topic of the presence
     */
    @Override
    public @NotNull TopicKey getTopic() {
        return this.topic;
    }

    /**
     * @return the player's key, which signs the presence
     */
    @Override
    public @NotNull IdentityKey getAuthor() {
        return this.stamp.getKey();
    }

    /**
     * @return the player's stamp
     */
    public @NotNull PlayerStamp getStamp() {
        return this.stamp;
    }

    /**
     * @return the number of the player's connection the presence tells of
     */
    public long getConnection() {
        return this.connection;
    }

    /**
     * @return the sequence; a higher sequence of the same connection of the player in the same topic replaces a lower one
     */
    @Override
    public long getSequence() {
        return this.sequence;
    }

    /**
     * @return the epoch millisecond at which the presence expires
     */
    @Override
    public long getExpiryMillis() {
        return this.expiryMillis;
    }

    /**
     * @return what the presence tells
     */
    public @NotNull PresenceContent getContent() {
        return this.content;
    }

    /**
     * @return {@code true} if the player's signature is valid for the presence, {@code false} otherwise
     */
    @Override
    public boolean isSignatureValid() {
        return this.verify(this.stamp.getKey(), SIGNATURE_DOMAIN);
    }


    /**
     * @return the player key, the connection, the sequence and the content, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.stamp.getKey() + "/" + Long.toHexString(this.connection) + "#" + this.sequence + " " + this.content;
    }
}
