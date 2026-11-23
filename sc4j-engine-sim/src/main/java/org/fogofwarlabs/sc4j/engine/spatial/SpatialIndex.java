package org.fogofwarlabs.sc4j.engine.spatial;

import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.system.GameSystem;
import org.fogofwarlabs.sc4j.engine.game.system.SystemKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds units by area: the index of unit bounds that every range, rectangle and nearest-unit search uses. A unit enters
 * the index when it is placed in the world, leaves it when it is hidden or removed, and is moved in it after every
 * position change.
 * <p>
 * Coordinates are pixels, rectangles are inclusive.
 * <p>
 * Determinism contract: a query returns units in the index order the implementation documents (for example ascending
 * left edge, then insertion order), never in a hash order; queries change no simulation state, draw no random value and
 * collect into the caller's {@link UnitList} instead of allocating.
 */
public interface SpatialIndex extends GameSystem {
    /**
     * The key of the spatial index.
     */
    @NotNull SystemKey<SpatialIndex> KEY = SystemKey.of("spatial-index", SpatialIndex.class);

    /**
     * Adds a unit with its current bounds.
     *
     * @param unit the unit
     */
    void insert(@NotNull Unit unit);

    /**
     * Removes a unit.
     *
     * @param unit the unit
     */
    void remove(@NotNull Unit unit);

    /**
     * Moves a unit in the index by the displacement of its position; the unit has already moved. Its size is unchanged.
     *
     * @param unit             the unit
     * @param horizontalChange the horizontal displacement
     * @param verticalChange   the vertical displacement
     */
    void relocate(@NotNull Unit unit, int horizontalChange, int verticalChange);

    /**
     * Finds the units whose bounds intersect a rectangle. The result is cleared first and collects the accepted units in
     * index order up to its capacity.
     *
     * @param left   the left edge
     * @param top    the top edge
     * @param right  the right edge
     * @param bottom the bottom edge
     * @param filter selects the returned units
     * @param result receives the units
     */
    void findInRectangle(int left, int top, int right, int bottom, @NotNull UnitFilter filter, @NotNull UnitList result);

    /**
     * Finds the unit nearest to a point, measured between the point and the unit bounds.
     *
     * @param x               the x coordinate of the point
     * @param y               the y coordinate of the point
     * @param maximumDistance the largest distance searched
     * @param filter          selects the candidate units
     * @return the nearest accepted unit, the first in index order among equally near ones, or {@code null} if none is within the distance
     */
    @Nullable Unit findNearest(int x, int y, int maximumDistance, @NotNull UnitFilter filter);
}
