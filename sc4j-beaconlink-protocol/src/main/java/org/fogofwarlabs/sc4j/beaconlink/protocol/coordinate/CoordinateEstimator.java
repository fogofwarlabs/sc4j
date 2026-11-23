package org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate;

import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.random.RandomGenerator;

/**
 * Estimates the network coordinate of the host it runs on with the Vivaldi algorithm (3 dimensions and a height): every round trip time the
 * host measures anyway, to a host whose {@link CoordinateReport} it holds, moves the own coordinate like a spring between the two points
 * would, by the difference of the measured and the estimated time.
 * <p>
 * The step is adaptive: a sample moves the coordinate by {@value #CORRECTION_FACTOR} times the share of the own error in the two errors, so a
 * host that trusts its coordinate little follows a settled host, and a settled host barely moves for a host that knows nothing. The error
 * follows the relative error of the samples as a moving average with the same weight. A point that would leave the bounds of the wire form is
 * held at them, the height stays at least {@value #MINIMUM_HEIGHT_MILLIS} milliseconds (so that it can grow), and a weak pull to the origin
 * keeps the coordinates of the network from drifting away together. Samples above {@value #MAXIMUM_ROUND_TRIP_MILLIS} milliseconds are
 * ignored.
 * <p>
 * A host talks to a few hosts (a client's nodes, a peer's seeds) far more often than to the others, and a coordinate that follows those few
 * fits them alone. So one remote host gives at most one sample every {@value #SAMPLE_INTERVAL_MILLIS} milliseconds (the last
 * {@value #MAXIMUM_REMOTES} remote hosts are remembered), and a coordinate is settled only after samples of
 * {@value #SETTLED_REMOTES} different hosts, enough to fix a point of three dimensions and a height, with an error of at most
 * {@value #SETTLED_ERROR}.
 * <p>
 * A remote host may lie about its coordinate and error within the bounds of the wire form, or delay its answers. A settled coordinate
 * therefore takes no sample whose measured and estimated times differ by more than the factor {@value #OUTLIER_FACTOR} (an outlier, which the
 * caller counts against the remote host). Of a run of outliers it takes every {@value #MAXIMUM_OUTLIER_RUN}th: when the host itself moved,
 * every sample is one, and the taken ones raise the error until the coordinate is no longer settled and follows the samples again.
 * <p>
 * One host has one estimator: a session and the peer that shares its socket feed the same one. It is not thread-safe; its owner uses it on
 * one thread.
 */
public final class CoordinateEstimator {
    /**
     * The largest step of a sample, as a share of the difference of the measured and the estimated time.
     */
    public static final double CORRECTION_FACTOR = 0.25;
    /**
     * The largest weight of a sample's relative error in the moving error.
     */
    public static final double ERROR_FACTOR = 0.25;
    /**
     * The smallest height, in milliseconds.
     */
    public static final double MINIMUM_HEIGHT_MILLIS = 0.1;
    /**
     * The largest round trip time a sample may have, in milliseconds.
     */
    public static final long MAXIMUM_ROUND_TRIP_MILLIS = 5_000L;
    /**
     * The largest error of a settled coordinate, one that records carry.
     */
    public static final double SETTLED_ERROR = 0.6;
    /**
     * The smallest number of different remote hosts a settled coordinate took samples of.
     */
    public static final int SETTLED_REMOTES = 6;
    /**
     * The shortest time between two samples of one remote host, in milliseconds.
     */
    public static final long SAMPLE_INTERVAL_MILLIS = 300_000L;
    /**
     * The number of remote hosts whose last sample time is remembered.
     */
    public static final int MAXIMUM_REMOTES = 64;
    /**
     * The largest factor between the measured and the estimated time of a sample that a settled coordinate takes.
     */
    public static final double OUTLIER_FACTOR = 4.0;
    /**
     * The length of a run of outliers whose last sample a settled coordinate takes, since then the host itself may have moved.
     */
    public static final int MAXIMUM_OUTLIER_RUN = 8;

    private static final double MINIMUM_ROUND_TRIP_MILLIS = 0.1;
    private static final double MINIMUM_DISTANCE_MILLIS = 1.0e-6;
    private static final double MINIMUM_TOTAL_ERROR = 1.0e-6;
    private static final double GRAVITY_MILLIS = 5_000.0;


    private final @NotNull RandomGenerator random;

    private final @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull Long> sampleTimes;
    private final double @NotNull [] components;
    private double height;
    private double error;
    private int sampleCount;
    private int outlierRun;

    /**
     * Creates a {@link CoordinateEstimator} instance at the origin with the largest error.
     *
     * @param random the source of the direction in which two hosts at one point move apart
     */
    public CoordinateEstimator(@NotNull RandomGenerator random) {
        this.random = random;

        this.sampleTimes = new LinkedHashMap<>();
        this.components  = new double[NetworkCoordinate.DIMENSIONS];
        this.height      = MINIMUM_HEIGHT_MILLIS;
        this.error       = CoordinateReport.MAXIMUM_ERROR;
        this.sampleCount = 0;
        this.outlierRun  = 0;
    }

    /**
     * @return the current coordinate
     */
    public @NotNull NetworkCoordinate getCoordinate() {
        return new NetworkCoordinate(this.components, this.height);
    }

    /**
     * @return the current error, {@code 0} to {@link CoordinateReport#MAXIMUM_ERROR}
     */
    public double getError() {
        return this.error;
    }

    /**
     * @return the current coordinate and error, as cards and answers carry them
     */
    public @NotNull CoordinateReport getReport() {
        return new CoordinateReport(this.getCoordinate(), this.error);
    }

    /**
     * @return the number of samples taken
     */
    public int getSampleCount() {
        return this.sampleCount;
    }

    /**
     * Checks whether the coordinate is settled: it took samples of at least {@value #SETTLED_REMOTES} remembered remote hosts and its error
     * is at most {@value #SETTLED_ERROR}.
     *
     * @return {@code true} if the coordinate is settled, {@code false} otherwise
     */
    public boolean isSettled() {
        return this.sampleTimes.size() >= SETTLED_REMOTES && this.error <= SETTLED_ERROR;
    }

    /**
     * @return the current coordinate if it is settled ({@link #isSettled()}), the one that rooms and presences carry, or {@code null} while it is not
     */
    public @Nullable NetworkCoordinate getSettledCoordinate() {
        return this.isSettled() ? this.getCoordinate() : null;
    }


    /**
     * Estimates the round trip time to a host.
     *
     * @param other the other host's coordinate
     * @return the estimated round trip time in milliseconds, or {@code -1} if the own coordinate is not settled
     */
    public long estimateRoundTripMillis(@NotNull NetworkCoordinate other) {
        return this.isSettled() ? Math.round(this.getCoordinate().estimateRoundTripMillis(other)) : -1L;
    }

    /**
     * Updates the coordinate from a measured round trip time to another host. A time of a resent request must not be given, since its answer
     * may belong to the first send. A sample is skipped if its time is negative or above {@value #MAXIMUM_ROUND_TRIP_MILLIS}, or the other host
     * gave a sample less than {@value #SAMPLE_INTERVAL_MILLIS} milliseconds ago.
     *
     * @param remoteKey       the other host's key
     * @param remote          the other host's report
     * @param roundTripMillis the measured round trip time, in milliseconds
     * @param nowMillis       the current time
     * @return {@link CoordinateSample#TAKEN} if the sample moved the coordinate, otherwise why it was not used
     */
    public @NotNull CoordinateSample update(@NotNull IdentityKey remoteKey, @NotNull CoordinateReport remote, long roundTripMillis, long nowMillis) {
        if (roundTripMillis < 0L || roundTripMillis > MAXIMUM_ROUND_TRIP_MILLIS) {
            return CoordinateSample.SKIPPED;
        }

        Long lastMillis = this.sampleTimes.remove(remoteKey);

        if (lastMillis != null && nowMillis - lastMillis < SAMPLE_INTERVAL_MILLIS) {
            this.sampleTimes.put(remoteKey, lastMillis);

            return CoordinateSample.SKIPPED;
        }

        this.sampleTimes.put(remoteKey, nowMillis);

        if (this.sampleTimes.size() > MAXIMUM_REMOTES) {
            Iterator<IdentityKey> eldest = this.sampleTimes.keySet().iterator();

            eldest.next();
            eldest.remove();
        }

        NetworkCoordinate other = remote.getCoordinate();
        double measured = Math.max(roundTripMillis, MINIMUM_ROUND_TRIP_MILLIS);
        double[] difference = new double[NetworkCoordinate.DIMENSIONS];
        double distance = 0.0;

        for (int axis = 0; axis < difference.length; axis++) {
            difference[axis] = this.components[axis] - other.getComponent(axis);
            distance += difference[axis] * difference[axis];
        }

        distance = Math.sqrt(distance);

        double estimated = distance + this.height + other.getHeight();

        if (this.isSettled() && Math.max(measured / estimated, estimated / measured) > OUTLIER_FACTOR) {
            this.outlierRun++;

            if (this.outlierRun < MAXIMUM_OUTLIER_RUN) {
                return CoordinateSample.OUTLIER;
            }

            // The last outlier of a run is taken: the host itself may have moved.
        }

        this.outlierRun = 0;

        double weight = this.error / Math.max(this.error + remote.getError(), MINIMUM_TOTAL_ERROR);
        double relativeError = Math.abs(estimated - measured) / measured;

        this.error = Math.min(CoordinateReport.MAXIMUM_ERROR, ERROR_FACTOR * weight * relativeError + this.error * (1.0 - ERROR_FACTOR * weight));

        if (distance < MINIMUM_DISTANCE_MILLIS) {
            // Two hosts at one point move apart in a random direction.
            distance  = this.createRandomDirection(difference);
            estimated = distance + this.height + other.getHeight();
        }

        double force = CORRECTION_FACTOR * weight * (measured - estimated);

        for (int axis = 0; axis < difference.length; axis++) {
            this.components[axis] += force * difference[axis] / estimated;
        }

        this.height += force * (this.height + other.getHeight()) / estimated;
        this.applyGravity();
        this.clamp();
        this.sampleCount++;

        return CoordinateSample.TAKEN;
    }

    private double createRandomDirection(double @NotNull [] direction) {
        double length = 0.0;

        while (length < MINIMUM_DISTANCE_MILLIS) {
            length = 0.0;

            for (int axis = 0; axis < direction.length; axis++) {
                direction[axis] = this.random.nextGaussian();
                length += direction[axis] * direction[axis];
            }

            length = Math.sqrt(length);
        }

        for (int axis = 0; axis < direction.length; axis++) {
            direction[axis] /= length;
        }

        return 1.0;
    }

    private void applyGravity() {
        double length = 0.0;

        for (double component : this.components) {
            length += component * component;
        }

        length = Math.sqrt(length);

        if (length < MINIMUM_DISTANCE_MILLIS) {
            return;
        }

        double pull = length / GRAVITY_MILLIS;
        double scale = Math.max(0.0, 1.0 - pull * pull / length);

        for (int axis = 0; axis < this.components.length; axis++) {
            this.components[axis] *= scale;
        }
    }

    private void clamp() {
        for (int axis = 0; axis < this.components.length; axis++) {
            this.components[axis] = Math.clamp(this.components[axis], -NetworkCoordinate.MAXIMUM_MILLIS, NetworkCoordinate.MAXIMUM_MILLIS);
        }

        this.height = Math.clamp(this.height, MINIMUM_HEIGHT_MILLIS, NetworkCoordinate.MAXIMUM_MILLIS);
    }

    /**
     * @return the coordinate, the error and the number of samples, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.getCoordinate() + " error " + this.error + " after " + this.sampleCount + " samples";
    }
}
