package org.fogofwarlabs.sc4j.beaconlink.protocol.topic;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PeerId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies a Beacon.Link topic: its kind and the SHA-256 hash of a domain text, the kind code and the topic name (UTF-8). The hash is a
 * place in the routing space ({@link #getTarget()}): the full peers closest to it store who is in the topic, and the members of the topic
 * form its swarm. A mod's topic is named by its mod id, so every version of the mod shares one topic; a chat channel's topic by its name in
 * lower case ({@link #ofChannel}), shared by every mod; a player's topic by the player key ({@link #ofPlayer}).
 * <p>
 * On the wire: the kind code (one byte) and the hash ({@value PeerId#LENGTH} bytes).
 */
public final class TopicKey {
    /**
     * The length of the wire form.
     */
    public static final int LENGTH = 1 + PeerId.LENGTH;
    /**
     * The largest UTF-8 length of a channel name.
     */
    public static final int MAXIMUM_CHANNEL_NAME_LENGTH = 32;

    private static final @NotNull String DIGEST_ALGORITHM = "SHA-256";
    private static final byte @NotNull [] DOMAIN = "BLNK topic 1\0".getBytes(StandardCharsets.US_ASCII);
    private static final @NotNull Pattern WHITE_SPACE = Pattern.compile("\\s+");

    /**
     * Creates a {@link TopicKey} instance from a topic's kind and name.
     *
     * @param kind the topic's kind
     * @param name the topic's name, for a mod topic the mod id
     * @return the created {@link TopicKey} instance
     */
    public static @NotNull TopicKey of(@NotNull TopicKind kind, @NotNull String name) {
        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The JDK has no {0} digest.", DIGEST_ALGORITHM);
        }

        digest.update(DOMAIN);
        digest.update((byte) kind.getCode());
        digest.update(name.getBytes(StandardCharsets.UTF_8));

        return new TopicKey(kind, PeerId.of(digest.digest()));
    }

    /**
     * Creates a {@link TopicKey} instance of a chat channel: the topic of its cleaned name ({@link #cleanChannelName}) in lower case, so that
     * names that differ in case or in white space only name one channel.
     *
     * @param name the channel name
     * @return the created {@link TopicKey} instance
     * @throws IllegalArgumentException if the cleaned name is empty, longer than {@value #MAXIMUM_CHANNEL_NAME_LENGTH} UTF-8 bytes or has a control character
     */
    public static @NotNull TopicKey ofChannel(@NotNull String name) {
        return of(TopicKind.CHANNEL, cleanChannelName(name).toLowerCase(Locale.ROOT));
    }

    /**
     * Creates a {@link TopicKey} instance of a player: the topic named by the player key in hexadecimal.
     *
     * @param player the player key
     * @return the created {@link TopicKey} instance
     */
    public static @NotNull TopicKey ofPlayer(@NotNull IdentityKey player) {
        return of(TopicKind.PLAYER, HexFormat.of().formatHex(player.getBytes()));
    }

    /**
     * Cleans a channel name for display: the white space at both ends removed and every run of white space inside replaced by one space.
     *
     * @param name the channel name as a person typed it
     * @return the cleaned name
     * @throws IllegalArgumentException if the cleaned name is empty, longer than {@value #MAXIMUM_CHANNEL_NAME_LENGTH} UTF-8 bytes or has a control character
     */
    public static @NotNull String cleanChannelName(@NotNull String name) {
        String cleaned = WHITE_SPACE.matcher(name.strip()).replaceAll(" ");

        if (cleaned.isEmpty() || cleaned.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_CHANNEL_NAME_LENGTH ||
            cleaned.codePoints().anyMatch(Character::isISOControl)) {
            throw Exceptions.newIllegalArgumentException("The channel name {0} is empty, longer than {1} UTF-8 bytes or has a control character.",
                                                         name, MAXIMUM_CHANNEL_NAME_LENGTH);
        }

        return cleaned;
    }

    /**
     * Reads a topic key from the wire.
     *
     * @param reader the reader
     * @return the topic key, or {@code null} if the reader failed or the kind is unknown
     */
    public static @Nullable TopicKey read(@NotNull WireReader reader) {
        TopicKind kind = TopicKind.find(reader.readUnsigned8());
        PeerId target = PeerId.read(reader);

        if (kind == null || target == null) {
            reader.fail();

            return null;
        }

        return new TopicKey(kind, target);
    }


    private final @NotNull TopicKind kind;
    private final @NotNull PeerId target;

    private TopicKey(@NotNull TopicKind kind, @NotNull PeerId target) {
        this.kind   = kind;
        this.target = target;
    }

    /**
     * @return the topic's kind
     */
    public @NotNull TopicKind getKind() {
        return this.kind;
    }

    /**
     * @return the topic's place in the routing space: the target of the lookup of its storing peers
     */
    public @NotNull PeerId getTarget() {
        return this.target;
    }


    /**
     * Writes the topic key.
     *
     * @param writer the writer
     */
    public void write(@NotNull WireWriter writer) {
        writer.writeUnsigned8(this.kind.getCode());
        this.target.write(writer);
    }

    /**
     * @param other the other object
     * @return {@code true} if the other object is a topic key of the same kind and hash, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TopicKey key && this.kind == key.kind && this.target.equals(key.target);
    }

    /**
     * @return the hash code of the kind and the hash
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.kind, this.target);
    }

    /**
     * @return the kind and the first bytes of the hash, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.kind + ":" + this.target;
    }
}
