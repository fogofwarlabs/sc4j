package org.fogofwarlabs.sc4j.launcher.session;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkConnectivity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Holds the logged-in account's own state as the session last saw it: the session state, the status the user chose, the away text, the
 * number of the account's devices online, why the session has no connection and what the session knows of the network's direct
 * connections. A view never changes; the session publishes a new one.
 */
public final class SelfView {
    private final @NotNull SessionState state;
    private final @NotNull SelfStatus status;
    private final @NotNull String awayText;
    private final int deviceCount;
    private final @Nullable SessionFailure failure;
    private final @NotNull LinkConnectivity connectivity;

    /**
     * Creates a {@link SelfView} instance.
     *
     * @param state        the session state
     * @param status       the status the user chose
     * @param awayText     the away text, empty for none
     * @param deviceCount  the number of the account's devices the session sees online, this launcher included; {@code 0} while offline
     * @param failure      why the session has no connection, or {@code null} while it is connected or connecting
     * @param connectivity what the session knows of the network's direct connections
     */
    public SelfView(@NotNull SessionState state, @NotNull SelfStatus status, @NotNull String awayText, int deviceCount,
                    @Nullable SessionFailure failure, @NotNull LinkConnectivity connectivity) {
        this.state        = state;
        this.status       = status;
        this.awayText     = awayText;
        this.deviceCount  = deviceCount;
        this.failure      = failure;
        this.connectivity = connectivity;
    }

    /**
     * @return the session state
     */
    public @NotNull SessionState getState() {
        return this.state;
    }

    /**
     * @return the status the user chose
     */
    public @NotNull SelfStatus getStatus() {
        return this.status;
    }

    /**
     * @return the away text, empty for none
     */
    public @NotNull String getAwayText() {
        return this.awayText;
    }

    /**
     * @return the number of the account's devices the session sees online, this launcher included; {@code 0} while offline
     */
    public int getDeviceCount() {
        return this.deviceCount;
    }

    /**
     * @return why the session has no connection, or {@code null} while it is connected or connecting
     */
    public @Nullable SessionFailure getFailure() {
        return this.failure;
    }

    /**
     * @return what the session knows of the network's direct connections; {@link LinkConnectivity#UNKNOWN} while it has no connection
     */
    public @NotNull LinkConnectivity getConnectivity() {
        return this.connectivity;
    }


    /**
     * Indicates whether some other object is "equal to" this one: a view of the same state.
     *
     * @param other the reference object with which to compare
     * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof SelfView view && this.state == view.state && this.status == view.status && this.awayText.equals(view.awayText) &&
               this.deviceCount == view.deviceCount && this.failure == view.failure && this.connectivity == view.connectivity;
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.state, this.status, this.awayText, this.deviceCount, this.failure, this.connectivity);
    }
}
