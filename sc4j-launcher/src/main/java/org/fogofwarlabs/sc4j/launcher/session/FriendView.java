package org.fogofwarlabs.sc4j.launcher.session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Holds one friend, or one member of a chat channel, as the session last saw it: the player's code, name and tag, the merged state of the
 * player's devices, the id of the mod the shown state comes from, the player's away text, the number of the player's devices online and
 * whether the account ignores the player. A view never changes; the session publishes a new one.
 */
public final class FriendView {
    private final @NotNull FriendCode code;
    private final @NotNull String taggedName;
    private final @NotNull FriendState state;
    private final @Nullable String modId;
    private final @NotNull String awayText;
    private final int deviceCount;
    private final boolean ignored;

    /**
     * Creates a {@link FriendView} instance.
     *
     * @param code        the friend's code
     * @param taggedName  the friend's name and tag ({@code Name#abcd12}); only the tag ({@code #abcd12}) while the name was never seen
     * @param state       the merged state of the friend's devices
     * @param modId       the id of the mod the shown state comes from, or {@code null} for none (offline, in the launcher)
     * @param awayText    the friend's away text, empty for none
     * @param deviceCount the number of the friend's devices online, {@code 0} while offline
     * @param ignored     whether the account ignores the friend
     */
    public FriendView(@NotNull FriendCode code, @NotNull String taggedName, @NotNull FriendState state, @Nullable String modId,
                      @NotNull String awayText, int deviceCount, boolean ignored) {
        this.code        = code;
        this.taggedName  = taggedName;
        this.state       = state;
        this.modId       = modId;
        this.awayText    = awayText;
        this.deviceCount = deviceCount;
        this.ignored     = ignored;
    }

    /**
     * @return the friend's code
     */
    public @NotNull FriendCode getCode() {
        return this.code;
    }

    /**
     * @return the friend's name and tag ({@code Name#abcd12}); only the tag ({@code #abcd12}) while the name was never seen
     */
    public @NotNull String getTaggedName() {
        return this.taggedName;
    }

    /**
     * @return the merged state of the friend's devices
     */
    public @NotNull FriendState getState() {
        return this.state;
    }

    /**
     * @return the id of the mod the shown state comes from, or {@code null} for none (offline, in the launcher)
     */
    public @Nullable String getModId() {
        return this.modId;
    }

    /**
     * @return the friend's away text, empty for none
     */
    public @NotNull String getAwayText() {
        return this.awayText;
    }

    /**
     * @return the number of the friend's devices online, {@code 0} while offline
     */
    public int getDeviceCount() {
        return this.deviceCount;
    }

    /**
     * @return {@code true} if the account ignores the friend, {@code false} otherwise
     */
    public boolean isIgnored() {
        return this.ignored;
    }


    /**
     * Indicates whether some other object is "equal to" this one: a view of the same friend in the same state.
     *
     * @param other the reference object with which to compare
     * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof FriendView view && this.code.equals(view.code) && this.taggedName.equals(view.taggedName) &&
               this.state == view.state && Objects.equals(this.modId, view.modId) && this.awayText.equals(view.awayText) &&
               this.deviceCount == view.deviceCount && this.ignored == view.ignored;
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.code, this.taggedName, this.state, this.modId, this.awayText, this.deviceCount, this.ignored);
    }
}
