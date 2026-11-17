package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Identifies a Beacon.Link player by the public key of its account ({@value #KEY_LENGTH} bytes): friends, presences, whispers, chat lines
 * and the ignore list are about the player, whichever device it uses. A new key is a new player. Two players are equal when their keys are
 * equal. One player may hold several connections at once ({@link LinkParticipant}).
 */
public final class LinkPlayer {
    /**
     * The byte length of a player key.
     */
    public static final int KEY_LENGTH = 32;
    /**
     * The character between a display name and the tag in a tagged name ({@code Name#abcd12}).
     */
    public static final char TAG_SEPARATOR = '#';

    private static final int DISPLAY_BYTES = 8;
    private static final int TAG_BYTES = 3;
    private static final @NotNull String TAG_DIGEST = "SHA-256";

    /**
     * Creates a {@link LinkPlayer} instance.
     *
     * @param key the {@value #KEY_LENGTH} key bytes, copied
     * @return the created {@link LinkPlayer} instance
     * @throws IllegalArgumentException if the key length is not {@value #KEY_LENGTH}
     */
    public static @NotNull LinkPlayer of(byte @NotNull [] key) {
        if (key.length != KEY_LENGTH) {
            throw Exceptions.newIllegalArgumentException("A player key has {0} bytes, not {1}.", KEY_LENGTH, key.length);
        }

        return new LinkPlayer(key.clone());
    }


    private final byte @NotNull [] key;

    private LinkPlayer(byte @NotNull [] key) {
        this.key = key;
    }

    /**
     * @return a copy of the key bytes
     */
    public byte @NotNull [] getKey() {
        return this.key.clone();
    }

    /**
     * @return the player's tag: the first six hexadecimal digits of the SHA-256 hash of its key, shown after a display name
     * ({@code Name#abcd12}) so that players of equal names stay apart without a registry
     */
    public @NotNull String getTag() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(TAG_DIGEST).digest(this.key), 0, TAG_BYTES);
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The JDK has no {0} digest.", TAG_DIGEST);
        }
    }

    /**
     * @return the tag with its separator, {@code #abcd12}, as a tagged name ends
     */
    public @NotNull String getTagText() {
        return TAG_SEPARATOR + this.getTag();
    }

    /**
     * Formats a display name of this player with the player's tag.
     *
     * @param name the display name
     * @return the tagged name, {@code Name#abcd12}
     */
    public @NotNull String formatTaggedName(@NotNull String name) {
        return name + this.getTagText();
    }


    /**
     * @param other the other object
     * @return {@code true} if the other object is a player with the same key, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof LinkPlayer player && Arrays.equals(this.key, player.key);
    }

    /**
     * @return the hash code of the key
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(this.key);
    }

    /**
     * @return the first key bytes in hexadecimal, for logs
     */
    @Override
    public @NotNull String toString() {
        return HexFormat.of().formatHex(this.key, 0, DISPLAY_BYTES);
    }
}
