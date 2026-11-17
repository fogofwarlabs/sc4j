package org.fogofwarlabs.sc4j.engine.net.beaconlink.community;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Holds one line of a chat channel: its author with the display name the author signed it with, its kind, its text and the time the author
 * wrote it. Every line is signed by its author, so nobody can forge a line of another player.
 */
public final class ChatLine {
    private final @NotNull LinkPlayer author;
    private final @NotNull String authorName;
    private final @NotNull ChatLineKind kind;
    private final @NotNull String text;
    private final long timestampMillis;

    /**
     * Creates a {@link ChatLine} instance.
     *
     * @param author          the author
     * @param authorName      the author's display name
     * @param kind            the kind
     * @param text            the text
     * @param timestampMillis the epoch millisecond at which the author wrote the line, by the author's clock
     */
    public ChatLine(@NotNull LinkPlayer author, @NotNull String authorName, @NotNull ChatLineKind kind, @NotNull String text, long timestampMillis) {
        this.author          = author;
        this.authorName      = authorName;
        this.kind            = kind;
        this.text            = text;
        this.timestampMillis = timestampMillis;
    }

    /**
     * @return the author
     */
    public @NotNull LinkPlayer getAuthor() {
        return this.author;
    }

    /**
     * @return the author's display name when it wrote the line
     */
    public @NotNull String getAuthorName() {
        return this.authorName;
    }

    /**
     * @return the author's display name with its tag, {@code Name#abcd12}
     */
    public @NotNull String getTaggedAuthorName() {
        return this.author.formatTaggedName(this.authorName);
    }

    /**
     * @return the kind
     */
    public @NotNull ChatLineKind getKind() {
        return this.kind;
    }

    /**
     * @return the text
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the epoch millisecond at which the author wrote the line, by the author's clock
     */
    public long getTimestampMillis() {
        return this.timestampMillis;
    }


    /**
     * @return the tagged author name and the text, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.getTaggedAuthorName() + (this.kind == ChatLineKind.EMOTE ? " " : ": ") + this.text;
    }
}
