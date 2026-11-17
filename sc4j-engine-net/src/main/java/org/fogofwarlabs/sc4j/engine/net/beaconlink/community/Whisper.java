package org.fogofwarlabs.sc4j.engine.net.beaconlink.community;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Holds one whisper: a private line between this session's player and another player, sent or received. Whispers are sealed end to end: the
 * peers that carry them see only the two players and the size.
 */
public final class Whisper {
    private final @NotNull LinkPlayer other;
    private final @NotNull String otherName;
    private final boolean incoming;
    private final @NotNull String text;
    private final long timestampMillis;

    /**
     * Creates a {@link Whisper} instance.
     *
     * @param other           the other player: the sender of a received whisper, the target of a sent one
     * @param otherName       the other player's display name
     * @param incoming        whether the session received the whisper
     * @param text            the text
     * @param timestampMillis the epoch millisecond at which the sender wrote the whisper, by the sender's clock
     */
    public Whisper(@NotNull LinkPlayer other, @NotNull String otherName, boolean incoming, @NotNull String text, long timestampMillis) {
        this.other           = other;
        this.otherName       = otherName;
        this.incoming        = incoming;
        this.text            = text;
        this.timestampMillis = timestampMillis;
    }

    /**
     * @return the other player: the sender of a received whisper, the target of a sent one
     */
    public @NotNull LinkPlayer getOther() {
        return this.other;
    }

    /**
     * @return the other player's display name, empty when the session knows none
     */
    public @NotNull String getOtherName() {
        return this.otherName;
    }

    /**
     * @return the other player's display name with its tag, {@code Name#abcd12}
     */
    public @NotNull String getTaggedOtherName() {
        return this.other.formatTaggedName(this.otherName);
    }

    /**
     * @return {@code true} if the session received the whisper, {@code false} otherwise (it sent it)
     */
    public boolean isIncoming() {
        return this.incoming;
    }

    /**
     * @return the text
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the epoch millisecond at which the sender wrote the whisper, by the sender's clock
     */
    public long getTimestampMillis() {
        return this.timestampMillis;
    }


    /**
     * @return the direction, the other player and the text, for logs
     */
    @Override
    public @NotNull String toString() {
        return (this.incoming ? "from " : "to ") + this.getTaggedOtherName() + ": " + this.text;
    }
}
