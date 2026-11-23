package org.fogofwarlabs.sc4j.engine.spatial;

import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.jetbrains.annotations.NotNull;

/**
 * Reads the bounds of a unit as a {@link SortedEdgeIndex} indexes and searches them: the inclusive edges of its rectangle, its size, the point
 * a nearest search measures from and whether the unit is placed in the world. The mod supplies it, because the unit's size lives in its unit
 * type data and its position may live in its own pooled objects.
 * <p>
 * Coordinates are signed 16-bit pixel values. Determinism contract: every method reads the game state only and changes nothing.
 *
 * @param <U> the unit class
 */
public interface UnitBounds<U extends Unit> {
    /**
     * @param unit the unit
     * @return the left edge of the unit
     */
    int getLeft(@NotNull U unit);

    /**
     * @param unit the unit
     * @return the top edge of the unit
     */
    int getTop(@NotNull U unit);

    /**
     * @param unit the unit
     * @return the right edge of the unit, inclusive
     */
    int getRight(@NotNull U unit);

    /**
     * @param unit the unit
     * @return the bottom edge of the unit, inclusive
     */
    int getBottom(@NotNull U unit);

    /**
     * @param unit the unit
     * @return the width of the unit's bounds in pixels
     */
    int getWidth(@NotNull U unit);

    /**
     * @param unit the unit
     * @return the height of the unit's bounds in pixels
     */
    int getHeight(@NotNull U unit);

    /**
     * @param unit the unit
     * @return the x coordinate of the point a nearest search measures the unit from
     */
    int getCenterX(@NotNull U unit);

    /**
     * @param unit the unit
     * @return the y coordinate of the point a nearest search measures the unit from
     */
    int getCenterY(@NotNull U unit);

    /**
     * Checks whether a unit is placed in the world. A search around a placed unit starts at its own edges, around a hidden one at its center point.
     *
     * @param unit the unit
     * @return {@code true} if the unit is placed in the world, {@code false} if it is hidden
     */
    boolean isPlaced(@NotNull U unit);

    /**
     * Measures the gap between the bounds of two units: the larger of the horizontal and the vertical gap between the rectangles, {@code 0}
     * when they touch or overlap.
     *
     * @param unit      the first unit
     * @param otherUnit the second unit
     * @return the gap in pixels
     */
    default int getGap(@NotNull U unit, @NotNull U otherUnit) {
        int horizontalGap = Math.max(this.getLeft(otherUnit) - this.getRight(unit), this.getLeft(unit) - this.getRight(otherUnit)) - 1;
        int verticalGap = Math.max(this.getTop(otherUnit) - this.getBottom(unit), this.getTop(unit) - this.getBottom(otherUnit)) - 1;

        return Math.max(0, Math.max(horizontalGap, verticalGap));
    }
}
