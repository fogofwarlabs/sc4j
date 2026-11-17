package org.fogofwarlabs.sc4j.launcher.session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Holds one chat channel as the session last saw it: its name, its member count and, for a channel the account's chat is in, its members in
 * the order the member list shows them. A view never changes; the session publishes a new one.
 */
public final class ChannelView {
    private final @NotNull String name;
    private final int memberCount;
    private final @NotNull List<@NotNull FriendView> members;

    /**
     * Creates a {@link ChannelView} instance.
     *
     * @param name        the channel name, as the session joined it or as the presences name it
     * @param memberCount the number of the channel's members
     * @param members     the members of a channel the chat is in: the account first, then friends, then the others; empty for another channel
     */
    public ChannelView(@NotNull String name, int memberCount, @NotNull List<@NotNull FriendView> members) {
        this.name        = name;
        this.memberCount = memberCount;
        this.members     = List.copyOf(members);
    }

    /**
     * @return the channel name, as the session joined it or as the presences name it
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the number of the channel's members
     */
    public int getMemberCount() {
        return this.memberCount;
    }

    /**
     * @return the members of a channel the chat is in: the account first, then friends, then the others; empty for another channel
     */
    public @NotNull List<@NotNull FriendView> getMembers() {
        return this.members;
    }


    /**
     * Indicates whether some other object is "equal to" this one: a view of the same channel with the same members.
     *
     * @param other the reference object with which to compare
     * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ChannelView view && this.name.equals(view.name) && this.memberCount == view.memberCount &&
               this.members.equals(view.members);
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.name, this.memberCount, this.members);
    }
}
