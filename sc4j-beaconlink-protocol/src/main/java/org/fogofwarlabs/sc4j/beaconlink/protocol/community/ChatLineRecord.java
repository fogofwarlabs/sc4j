package org.fogofwarlabs.sc4j.beaconlink.protocol.community;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.RecordKind;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKind;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Carries one line of a chat channel: a record of the channel's topic, signed by its author and carrying the author's stamp
 * ({@link PlayerStamp}), which binds the line's display name to the author's key. Members keep the lines of the last
 * {@value #LIFETIME_MILLIS} milliseconds (the backlog a new member gets); a line expires that long after its timestamp. Every line is its own
 * record: the author's sequence orders the author's lines, it replaces nothing.
 * <p>
 * On the wire: the flags (one byte, bit 0 emote, the other bits zero), the channel topic, the author key, the stamp nonce (64-bit), the
 * sequence (unsigned 32-bit), the timestamp (epoch milliseconds, 64-bit), the author's display name (at most
 * {@value PresenceContent#MAXIMUM_NAME_LENGTH} UTF-8 bytes), the text (at most {@value #MAXIMUM_TEXT_LENGTH} UTF-8 bytes) and the Ed25519
 * signature of the author over a domain prefix and every byte before it. At most {@value #MAXIMUM_ENCODED_LENGTH} bytes.
 */
public final class ChatLineRecord extends TopicRecord {
    /**
     * The largest sequence number.
     */
    public static final long MAXIMUM_SEQUENCE = 0xffffffffL;
    /**
     * The largest UTF-8 length of a line's text.
     */
    public static final int MAXIMUM_TEXT_LENGTH = 255;
    /**
     * The time a line lives after its timestamp, in milliseconds: the length of the backlog.
     */
    public static final long LIFETIME_MILLIS = 300_000L;
    /**
     * The largest length of a line's wire form.
     */
    public static final int MAXIMUM_ENCODED_LENGTH = 1 + TopicKey.LENGTH + IdentityKey.LENGTH + Long.BYTES + Integer.BYTES + Long.BYTES + 1 +
                                                     PresenceContent.MAXIMUM_NAME_LENGTH + 1 + MAXIMUM_TEXT_LENGTH + IdentityKey.SIGNATURE_LENGTH;

    private static final int EMOTE_FLAG = 1;
    private static final byte @NotNull [] SIGNATURE_DOMAIN = "Beacon.Link chat line 1\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * Creates a {@link ChatLineRecord} instance signed by its author, with the stamp's name as the display name.
     *
     * @param author          the author's key pair
     * @param stamp           the author's stamp, which names the author
     * @param topic           the channel topic
     * @param sequence        the author's sequence of the line
     * @param timestampMillis the epoch millisecond at which the author wrote the line
     * @param emote           whether the line is an emote ({@code /me}) instead of a said line
     * @param text            the text, not empty
     * @return the created {@link ChatLineRecord} instance
     * @throws IllegalArgumentException if the topic is not a channel, the sequence is out of range, the stamp is of another key, or a text is too long or empty
     */
    public static @NotNull ChatLineRecord sign(@NotNull IdentityKeyPair author, @NotNull PlayerStamp stamp, @NotNull TopicKey topic, long sequence,
                                               long timestampMillis, boolean emote, @NotNull String text) {
        String displayName = stamp.getName();

        if (topic.getKind() != TopicKind.CHANNEL || sequence < 0L || sequence > MAXIMUM_SEQUENCE || !stamp.getKey().equals(author.getPublicKey())) {
            throw Exceptions.newIllegalArgumentException("The topic {0} is no channel, the sequence {1} is outside 0..{2}, or the stamp {3} is not " +
                                                         "of the key {4}.", topic, sequence, MAXIMUM_SEQUENCE, stamp, author.getPublicKey());
        }

        if (text.isEmpty() || WireWriter.getTextLength(text) > MAXIMUM_TEXT_LENGTH ||
            WireWriter.getTextLength(displayName) > PresenceContent.MAXIMUM_NAME_LENGTH) {
            throw Exceptions.newIllegalArgumentException("The line of {0} is empty or longer than {1} UTF-8 bytes, or the name is too long.",
                                                         displayName, MAXIMUM_TEXT_LENGTH);
        }

        byte[] unsigned = encodeUnsigned(emote, topic, author.getPublicKey(), stamp.getNonce(), sequence, timestampMillis, displayName, text);

        return new ChatLineRecord(emote, topic, stamp, sequence, timestampMillis, text, unsigned,
                                  TopicRecord.sign(author, SIGNATURE_DOMAIN, unsigned));
    }

    /**
     * Reads a line from the wire; the signature and the stamp (of the key and the display name) are checked separately.
     *
     * @param reader the reader
     * @return the line, or {@code null} if the reader failed or a field is out of range
     */
    public static @Nullable ChatLineRecord read(@NotNull WireReader reader) {
        int flags = reader.readUnsigned8();
        TopicKey topic = TopicKey.read(reader);
        IdentityKey author = IdentityKey.read(reader);
        long stampNonce = reader.readInt64();
        long sequence = reader.readUnsigned32();
        long timestampMillis = reader.readInt64();
        String displayName = reader.readText(PresenceContent.MAXIMUM_NAME_LENGTH);
        String text = reader.readText(MAXIMUM_TEXT_LENGTH);
        byte[] signature = reader.readBytes(IdentityKey.SIGNATURE_LENGTH);

        if ((flags & ~EMOTE_FLAG) != 0 || topic == null || topic.getKind() != TopicKind.CHANNEL || author == null || text.isEmpty() ||
            reader.isFailed()) {
            reader.fail();

            return null;
        }

        boolean emote = (flags & EMOTE_FLAG) != 0;

        return new ChatLineRecord(emote, topic, new PlayerStamp(author, displayName, stampNonce), sequence, timestampMillis, text,
                                  encodeUnsigned(emote, topic, author, stampNonce, sequence, timestampMillis, displayName, text), signature);
    }

    private static byte @NotNull [] encodeUnsigned(boolean emote, @NotNull TopicKey topic, @NotNull IdentityKey author, long stampNonce,
                                                   long sequence, long timestampMillis, @NotNull String displayName, @NotNull String text) {
        WireWriter writer = new WireWriter(MAXIMUM_ENCODED_LENGTH);

        writer.writeUnsigned8(emote ? EMOTE_FLAG : 0);
        topic.write(writer);
        author.write(writer);
        writer.writeInt64(stampNonce);
        writer.writeUnsigned32(sequence);
        writer.writeInt64(timestampMillis);
        writer.writeText(displayName, PresenceContent.MAXIMUM_NAME_LENGTH);
        writer.writeText(text, MAXIMUM_TEXT_LENGTH);

        return writer.toByteArray();
    }


    private final boolean emote;
    private final @NotNull TopicKey topic;
    private final @NotNull PlayerStamp stamp;
    private final long sequence;
    private final long timestampMillis;
    private final @NotNull String text;

    private ChatLineRecord(boolean emote, @NotNull TopicKey topic, @NotNull PlayerStamp stamp, long sequence, long timestampMillis,
                           @NotNull String text, byte @NotNull [] unsigned, byte @NotNull [] signature) {
        super(unsigned, signature);

        this.emote           = emote;
        this.topic           = topic;
        this.stamp           = stamp;
        this.sequence        = sequence;
        this.timestampMillis = timestampMillis;
        this.text            = text;
    }

    /**
     * @return {@link RecordKind#CHAT_LINE}
     */
    @Override
    public @NotNull RecordKind getRecordKind() {
        return RecordKind.CHAT_LINE;
    }

    /**
     * @return {@code true} if the line is an emote, {@code false} otherwise (it is said)
     */
    public boolean isEmote() {
        return this.emote;
    }

    /**
     * @return the channel topic
     */
    @Override
    public @NotNull TopicKey getTopic() {
        return this.topic;
    }

    /**
     * @return the author's key, which signs the line
     */
    @Override
    public @NotNull IdentityKey getAuthor() {
        return this.stamp.getKey();
    }

    /**
     * @return the author's stamp
     */
    public @NotNull PlayerStamp getStamp() {
        return this.stamp;
    }

    /**
     * @return the author's sequence of the line
     */
    @Override
    public long getSequence() {
        return this.sequence;
    }

    /**
     * @return the epoch millisecond at which the author wrote the line
     */
    public long getTimestampMillis() {
        return this.timestampMillis;
    }

    /**
     * @return the epoch millisecond at which the line leaves the backlog: {@value #LIFETIME_MILLIS} milliseconds after its timestamp
     */
    @Override
    public long getExpiryMillis() {
        return this.timestampMillis + LIFETIME_MILLIS;
    }

    /**
     * @return the author's display name: the name the author's stamp binds to its key
     */
    public @NotNull String getDisplayName() {
        return this.stamp.getName();
    }

    /**
     * @return the text
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return {@code true} if the author's signature is valid for the line, {@code false} otherwise
     */
    @Override
    public boolean isSignatureValid() {
        return this.verify(this.stamp.getKey(), SIGNATURE_DOMAIN);
    }


    /**
     * @return the author key, the sequence and the text, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.stamp.getKey() + "#" + this.sequence + (this.emote ? " * " : ": ") + this.text;
    }
}
