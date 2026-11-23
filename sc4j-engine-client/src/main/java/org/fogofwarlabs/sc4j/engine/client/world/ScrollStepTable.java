package org.fogofwarlabs.sc4j.engine.client.world;

/**
 * Gives the step of a stepped camera scroll in pixels by speed row and step index: the table of a {@link SteppedScrollRate} and of a
 * {@link CameraTargetScroll}. The table reports its own shape, {@link #getSpeedCount()} rows of {@link #getStepCount()} steps each, and a
 * scroll keeps its speed row and its step index inside it. A scroll reads the table at every tick, so a table that changes while a game runs
 * changes the scrolling at once. The engine default is {@link LinearScrollStepTable}.
 */
public interface ScrollStepTable {
    /**
     * @return the number of speed rows, at least {@code 1}
     */
    int getSpeedCount();

    /**
     * @return the number of steps of every speed row, at least {@code 1}: a scroll raises its step index by one per moving update up to the last step
     */
    int getStepCount();

    /**
     * Reads one step.
     *
     * @param speed the speed row from {@code 0} through {@link #getSpeedCount()} {@code - 1}
     * @param index the step index from {@code 0} through {@link #getStepCount()} {@code - 1}
     * @return the step in pixels
     */
    int getStep(int speed, int index);
}
