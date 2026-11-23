package org.fogofwarlabs.sc4j.engine.client.world;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;

/**
 * Gives steps that grow linearly with the speed row and the step index: the engine default {@link ScrollStepTable}, of the speed and step
 * counts it is created with. The step of speed {@code s} and index {@code i} is {@code i * (s + 1) * unit}, so a scroll starts at one unit per
 * speed level and reaches its full step after {@link #getStepCount()} {@code - 1} moving ticks.
 */
public final class LinearScrollStepTable implements ScrollStepTable {
    /**
     * The default step unit in pixels.
     */
    public static final int DEFAULT_UNIT = 2;

    private final int speedCount;
    private final int stepCount;
    private final int unit;

    /**
     * Creates a {@link LinearScrollStepTable} instance with the {@link #DEFAULT_UNIT}.
     *
     * @param speedCount the number of speed rows
     * @param stepCount  the number of steps of every speed row
     * @throws IllegalArgumentException if a count is not positive
     */
    public LinearScrollStepTable(int speedCount, int stepCount) {
        this(speedCount, stepCount, DEFAULT_UNIT);
    }

    /**
     * Creates a {@link LinearScrollStepTable} instance.
     *
     * @param speedCount the number of speed rows
     * @param stepCount  the number of steps of every speed row
     * @param unit       the step unit in pixels
     * @throws IllegalArgumentException if a count or the unit is not positive
     */
    public LinearScrollStepTable(int speedCount, int stepCount, int unit) {
        if (speedCount <= 0 || stepCount <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid scroll step table shape {0}x{1}", speedCount, stepCount);
        }

        if (unit <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid scroll step unit {0}", unit);
        }

        this.speedCount = speedCount;
        this.stepCount  = stepCount;
        this.unit       = unit;
    }

    /**
     * @return the number of speed rows, at least {@code 1}
     */
    @Override
    public int getSpeedCount() {
        return this.speedCount;
    }

    /**
     * @return the number of steps of every speed row, at least {@code 1}: a scroll raises its step index by one per moving update up to the last step
     */
    @Override
    public int getStepCount() {
        return this.stepCount;
    }

    /**
     * @return the step unit in pixels
     */
    public int getUnit() {
        return this.unit;
    }


    /**
     * Reads one step.
     *
     * @param speed the speed row from {@code 0} through {@link #getSpeedCount()} {@code - 1}
     * @param index the step index from {@code 0} through {@link #getStepCount()} {@code - 1}
     * @return the step in pixels
     */
    @Override
    public int getStep(int speed, int index) {
        return index * (speed + 1) * this.unit;
    }
}
