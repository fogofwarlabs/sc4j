package org.fogofwarlabs.sc4j.engine.spatial;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Indexes the units of the game's unit pool by their sorted edges ({@link SortedEdgeIndex}): the engine default {@link SpatialIndex}. A mod
 * registers it under {@link SpatialIndex#KEY} with the bounds of its units and the largest unit size; the index is created when the system is
 * attached, with its own {@link SearchCounter}.
 * {@snippet :
 * builder.useSystem(SpatialIndex.KEY, context -> new StandardSpatialIndex(bounds, largestWidth, largestHeight));
 *}
 * <p>
 * Determinism contract: a rectangle query returns the units in ascending left-edge order (insertion order among equal edges), a nearest query
 * the first nearest unit of its outward edge scan; queries change no simulation state, draw no random value and allocate nothing. The index is
 * derived from the unit positions and contributes nothing to the state hash; its edge order among equal coordinates depends on the insertion
 * history, so the state snapshot holds it ({@link SortedEdgeIndex#writeSnapshot}).
 */
public final class StandardSpatialIndex implements SpatialIndex, SnapshotContributor {
    private final @NotNull UnitBounds<Unit> bounds;
    private final int largestUnitWidth;
    private final int largestUnitHeight;

    private @Nullable SortedEdgeIndex<Unit> index;

    /**
     * Creates a {@link StandardSpatialIndex} instance; it indexes nothing until it is attached.
     *
     * @param bounds            reads the bounds of a unit
     * @param largestUnitWidth  the largest width of a unit the game creates
     * @param largestUnitHeight the largest height of a unit the game creates
     */
    public StandardSpatialIndex(@NotNull UnitBounds<Unit> bounds, int largestUnitWidth, int largestUnitHeight) {
        this.bounds            = bounds;
        this.largestUnitWidth  = largestUnitWidth;
        this.largestUnitHeight = largestUnitHeight;

        this.index = null;
    }

    /**
     * @return the sorted-edge index over the game's unit pool, for the queries beyond the {@link SpatialIndex} operations
     * @throws IllegalStateException if the system is not attached
     */
    public @NotNull SortedEdgeIndex<Unit> getIndex() {
        SortedEdgeIndex<Unit> attached = this.index;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The spatial index is not attached.");
        }

        return attached;
    }


    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Creates the sorted-edge index over the game's unit pool.
     *
     * @param game the game
     */
    @Override
    public void attach(@NotNull Game game) {
        EntityPool<Unit> units = game.getUnits();
        SortedEdgeIndex<Unit> created = new SortedEdgeIndex<>(units.getCapacity(), units::get, this.bounds, new SearchCounter());

        created.setMinimumQueryDimensions(this.largestUnitWidth, this.largestUnitHeight);

        this.index = created;
    }

    /**
     * Adds a unit with its current bounds.
     *
     * @param unit the unit
     * @throws IllegalStateException if the system is not attached or the index is full
     */
    @Override
    public void insert(@NotNull Unit unit) {
        this.getIndex().insert(unit);
    }

    /**
     * Removes a unit.
     *
     * @param unit the unit
     * @throws IllegalStateException if the system is not attached or the unit's edges are inconsistent
     */
    @Override
    public void remove(@NotNull Unit unit) {
        this.getIndex().remove(unit);
    }

    /**
     * Moves a unit in the index by the displacement of its position; the unit has already moved. Its size is unchanged.
     *
     * @param unit             the unit
     * @param horizontalChange the horizontal displacement
     * @param verticalChange   the vertical displacement
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void relocate(@NotNull Unit unit, int horizontalChange, int verticalChange) {
        this.getIndex().relocate(unit, horizontalChange, verticalChange);
    }

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
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void findInRectangle(int left, int top, int right, int bottom, @NotNull UnitFilter filter, @NotNull UnitList result) {
        this.getIndex().findInRectangle(left, top, right, bottom, filter, result);
    }

    /**
     * Finds the unit nearest to a point, measured between the point and the unit bounds.
     *
     * @param x               the x coordinate of the point
     * @param y               the y coordinate of the point
     * @param maximumDistance the largest distance searched
     * @param filter          selects the candidate units
     * @return the nearest accepted unit, the first in index order among equally near ones, or {@code null} if none is within the distance
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public @Nullable Unit findNearest(int x, int y, int maximumDistance, @NotNull UnitFilter filter) {
        return this.getIndex().findNearestAccepted(x, y, maximumDistance, filter);
    }

    /**
     * Writes this part of the simulation state: the sorted-edge index ({@link SortedEdgeIndex#writeSnapshot}).
     *
     * @param writer the writer of the snapshot
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        this.getIndex().writeSnapshot(writer);
    }

    /**
     * Reads this part of the simulation state back, replacing the current values.
     *
     * @param reader the reader of the snapshot
     * @throws IOException           if the snapshot ends early, holds another capacity or an edge count outside it
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        this.getIndex().readSnapshot(reader);
    }
}
