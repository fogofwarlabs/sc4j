package org.fogofwarlabs.sc4j.engine.spatial;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.math.IntegerMathUtils;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Indexes units by the two edges of their bounds on each axis, kept sorted by coordinate, and records the four reciprocal edge indices on each
 * indexed {@link Unit}. Rectangle, point and nearest queries traverse the sorted edges in a defined order; collision, targeting and path searches
 * build on them.
 * <p>
 * Storage: per axis one array of pool slot indices and one of edge coordinates, two entries per unit of the pool. Inserting moves the later
 * entries up, removing moves them down and leaves the old last entry in place past the count, so the entries past the count keep what the
 * removals left ({@code -1} where nothing was ever stored). The index also owns the query markers (one per pool slot), the shared query results
 * and the minimum query dimensions: an expanded rectangle query smaller than them grows to them, so it meets every unit that overlaps it.
 * <p>
 * Coordinates are signed 16-bit pixel values: the queries compute their bounds in 16-bit arithmetic, so a coordinate past {@code 32767} wraps.
 * <p>
 * Determinism contract: every query returns units in a documented edge order, draws no random value and allocates nothing; the unexpanded query
 * advances the {@link SearchCounter}. A query that collects into the shared results is replaced by the next such query of the index.
 *
 * @param <U> the unit class of the indexed pool
 */
public final class SortedEdgeIndex<U extends Unit> implements SnapshotContributor {
    /**
     * The number of edges of an indexed unit on each axis: the minimum and the maximum edge.
     */
    private static final int EDGES_PER_UNIT = 2;
    /**
     * The number of axes, horizontal then vertical.
     */
    private static final int AXIS_COUNT = 2;
    /**
     * The axis index of the horizontal axis.
     */
    private static final int HORIZONTAL_AXIS_INDEX = 0;
    /**
     * The edge index of a unit that is not indexed.
     */
    private static final int NO_EDGE = -1;

    /**
     * The query marker of a unit with a horizontal edge in the expanded query.
     */
    private static final int MARKER_HORIZONTAL = 1;
    /**
     * The query marker of a unit with edges on both axes in the expanded query.
     */
    private static final int MARKER_BOTH_AXES = 3;
    /**
     * The scale of the doubled coordinates the edge search compares.
     */
    private static final int EDGE_SEARCH_SCALE = 2;
    /**
     * The shift that halves the sum of the binary search bounds.
     */
    private static final int BINARY_SEARCH_MIDPOINT_SHIFT = 1;
    /**
     * The mask of an unsigned 16-bit value.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Renumbers the edge of a unit that sits at an edge index: its minimum edge if it is there, otherwise its maximum edge if that is there.
     *
     * @param unit       the unit
     * @param horizontal whether the edge is on the horizontal axis
     * @param edgeIndex  the current edge index
     * @param newIndex   the new edge index
     */
    private static void moveEdgeIndex(@NotNull Unit unit, boolean horizontal, int edgeIndex, int newIndex) {
        if (unit.getMinimumEdgeIndex(horizontal) == edgeIndex) {
            setEdgeIndex(unit, horizontal, false, newIndex);
        } else if (unit.getMaximumEdgeIndex(horizontal) == edgeIndex) {
            setEdgeIndex(unit, horizontal, true, newIndex);
        }
    }

    /**
     * Sets one of the four reciprocal edge indices of a unit.
     *
     * @param unit       the unit
     * @param horizontal whether the edge is on the horizontal axis
     * @param maximum    whether the edge is the maximum edge
     * @param edgeIndex  the edge index
     */
    private static void setEdgeIndex(@NotNull Unit unit, boolean horizontal, boolean maximum, int edgeIndex) {
        if (horizontal) {
            if (maximum) {
                unit.setMaximumHorizontalEdgeIndex(edgeIndex);
            } else {
                unit.setMinimumHorizontalEdgeIndex(edgeIndex);
            }
        } else if (maximum) {
            unit.setMaximumVerticalEdgeIndex(edgeIndex);
        } else {
            unit.setMinimumVerticalEdgeIndex(edgeIndex);
        }
    }


    private final @NotNull IntFunction<U> units;
    private final @NotNull UnitBounds<? super U> bounds;
    private @NotNull SearchCounter searchCounter;

    private final int @NotNull [] horizontalUnitIndices;
    private final int @NotNull [] horizontalCoordinates;
    private final int @NotNull [] verticalUnitIndices;
    private final int @NotNull [] verticalCoordinates;
    private int count;

    /**
     * The query markers ({@code capacity + 1} values, zeroed with the index): the unexpanded query stamps them with the search counter, the
     * expanded query leaves {@code 0} or {@code 3} in them.
     */
    private final int @NotNull [] queryMarkers;
    private final int @NotNull [] queryResults;
    /**
     * The candidates of {@link #findInRectangle}, apart from the shared results so that its filter may run other queries.
     */
    private final int @NotNull [] filteredResults;

    private int minimumQueryWidth;
    private int minimumQueryHeight;
    private boolean queryBoundsInitialized;

    private final @NotNull Predicate<U> nearestTest;
    private @NotNull UnitFilter nearestFilter;

    /**
     * Creates a {@link SortedEdgeIndex} instance, empty, with two edges per unit of the pool and no query bounds yet.
     *
     * @param unitCapacity  the capacity of the unit pool; slot indices run from {@code 1} to it
     * @param units         returns the unit of a pool slot index
     * @param bounds        reads the bounds of a unit
     * @param searchCounter the counter of the unexpanded queries
     * @throws ArithmeticException if the edge storage of the capacity exceeds the {@code int} range
     */
    public SortedEdgeIndex(int unitCapacity,
                           @NotNull IntFunction<U> units, @NotNull UnitBounds<? super U> bounds,
                           @NotNull SearchCounter searchCounter) {
        this.units         = units;
        this.bounds        = bounds;
        this.searchCounter = searchCounter;

        int capacity = Math.multiplyExact(unitCapacity, EDGES_PER_UNIT);
        int queryCapacity = unitCapacity + 1;

        this.horizontalUnitIndices = new int[capacity];
        this.horizontalCoordinates = new int[capacity];
        this.verticalUnitIndices   = new int[capacity];
        this.verticalCoordinates   = new int[capacity];
        this.count                 = 0;

        this.queryMarkers    = new int[queryCapacity];
        this.queryResults    = new int[queryCapacity];
        this.filteredResults = new int[queryCapacity];

        this.minimumQueryWidth      = 0;
        this.minimumQueryHeight     = 0;
        this.queryBoundsInitialized = false;

        this.nearestTest   = unit -> this.nearestFilter.accepts(unit);
        this.nearestFilter = UnitFilter.ANY;

        Arrays.fill(this.horizontalUnitIndices, NO_EDGE);
        Arrays.fill(this.horizontalCoordinates, NO_EDGE);
        Arrays.fill(this.verticalUnitIndices, NO_EDGE);
        Arrays.fill(this.verticalCoordinates, NO_EDGE);
    }

    /**
     * Sets the counter of the unexpanded queries, for an owner that shares one counter between indexes.
     *
     * @param searchCounter the counter
     */
    public void setSearchCounter(@NotNull SearchCounter searchCounter) {
        this.searchCounter = searchCounter;
    }

    /**
     * @param slotIndex the pool slot index, {@code 1} up to the unit capacity
     * @return the unit of the slot
     */
    public @NotNull U getUnit(int slotIndex) {
        return this.units.apply(slotIndex);
    }

    /**
     * @return the number of edges on each axis
     */
    public int getCount() {
        return this.count;
    }

    /**
     * @return the number of edge entries of each axis, two per unit of the pool
     */
    public int getEdgeCapacity() {
        return this.horizontalCoordinates.length;
    }

    /**
     * @param horizontal whether to read the horizontal axis
     * @param index      the edge index
     * @return the pool slot index of the unit of the edge
     * @throws IndexOutOfBoundsException if the index is negative or not below the edge count
     */
    public int getUnitIndex(boolean horizontal, int index) {
        Objects.checkIndex(index, this.count);

        return (horizontal ? this.horizontalUnitIndices : this.verticalUnitIndices)[index];
    }

    /**
     * @param horizontal whether to read the horizontal axis
     * @param index      the edge index
     * @return the edge coordinate
     * @throws IndexOutOfBoundsException if the index is negative or not below the edge count
     */
    public int getCoordinate(boolean horizontal, int index) {
        Objects.checkIndex(index, this.count);

        return (horizontal ? this.horizontalCoordinates : this.verticalCoordinates)[index];
    }

    /**
     * Reads a stored slot index anywhere in the edge storage, past the count too.
     *
     * @param horizontal whether to read the horizontal axis
     * @param entry      the entry, below {@link #getEdgeCapacity()}
     * @return the stored slot index: what the last insertion or removal left there, {@code -1} where nothing was ever stored
     * @throws ArrayIndexOutOfBoundsException if the entry is outside the edge storage
     */
    public int getStoredUnitIndex(boolean horizontal, int entry) {
        return (horizontal ? this.horizontalUnitIndices : this.verticalUnitIndices)[entry];
    }

    /**
     * Reads a stored coordinate anywhere in the edge storage, past the count too.
     *
     * @param horizontal whether to read the horizontal axis
     * @param entry      the entry, below {@link #getEdgeCapacity()}
     * @return the stored coordinate: what the last insertion or removal left there, {@code -1} where nothing was ever stored
     * @throws ArrayIndexOutOfBoundsException if the entry is outside the edge storage
     */
    public int getStoredCoordinate(boolean horizontal, int entry) {
        return (horizontal ? this.horizontalCoordinates : this.verticalCoordinates)[entry];
    }

    /**
     * @return the query markers, live; one value per pool slot index from zero
     */
    public int @NotNull [] getQueryMarkers() {
        return this.queryMarkers;
    }

    /**
     * @return the number of entries of a query result list, one more than the unit capacity
     */
    public int getQueryCapacity() {
        return this.queryResults.length;
    }

    /**
     * Returns one unit of the shared results of the last query that collected into them.
     *
     * @param position the position in query order, below the count that query returned
     * @return the pool slot index of the unit
     * @throws ArrayIndexOutOfBoundsException if the position is outside the result storage
     */
    public int getQueryResult(int position) {
        return this.queryResults[position];
    }

    /**
     * @return {@code true} if the minimum query dimensions are set, {@code false} otherwise
     */
    public boolean hasQueryBounds() {
        return this.queryBoundsInitialized;
    }

    /**
     * @param horizontal whether to read the horizontal dimension
     * @return the minimum query width or height
     */
    public int getMinimumQueryDimension(boolean horizontal) {
        return horizontal ? this.minimumQueryWidth : this.minimumQueryHeight;
    }

    /**
     * Sets the minimum query dimensions: the largest width and height of a unit the index may hold. The expanded queries need them.
     *
     * @param width  the minimum query width
     * @param height the minimum query height
     */
    public void setMinimumQueryDimensions(int width, int height) {
        this.minimumQueryWidth      = width;
        this.minimumQueryHeight     = height;
        this.queryBoundsInitialized = true;
    }


    /**
     * Empties the edge storage and the query markers; the edge indices of the units are not changed.
     */
    public void clear() {
        Arrays.fill(this.queryMarkers, 0);
        Arrays.fill(this.verticalCoordinates, NO_EDGE);
        Arrays.fill(this.verticalUnitIndices, NO_EDGE);
        Arrays.fill(this.horizontalCoordinates, NO_EDGE);
        Arrays.fill(this.horizontalUnitIndices, NO_EDGE);

        this.count = 0;
    }

    /**
     * Inserts the four edges of a unit's bounds, the minimum edges before the maximum edges; each edge goes before the first stored edge at a
     * larger coordinate.
     *
     * @param unit the unit to insert, from the indexed pool
     * @throws IllegalStateException if the edge storage is full
     */
    public void insert(@NotNull U unit) {
        if (this.count > this.horizontalCoordinates.length - EDGES_PER_UNIT) {
            throw Exceptions.newIllegalStateException("The spatial index holds no further unit.");
        }

        this.insertEdge(unit, this.bounds.getLeft(unit), true, false);
        this.insertEdge(unit, this.bounds.getTop(unit), false, false);

        this.count++;

        this.insertEdge(unit, this.bounds.getRight(unit), true, true);
        this.insertEdge(unit, this.bounds.getBottom(unit), false, true);

        this.count++;
    }

    /**
     * Removes both edges on each axis without changing the order of surviving entries: the count drops by one, the maximum edges go, the count
     * drops again and the minimum edges go. Each removal renumbers the following entries' units and moves them down one slot, leaving the old
     * last entry in place past the count.
     * <p>
     * All edge indices of the removed unit become {@code -1}. A unit that is not indexed is left alone.
     *
     * @param unit the unit to remove
     * @throws IllegalStateException if its edge indices do not identify its entries
     */
    public void remove(@NotNull U unit) {
        int unitIndex = unit.getIndex();

        if (unit.getMinimumHorizontalEdgeIndex() < 0) {
            return;
        }

        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            boolean horizontal = axis == HORIZONTAL_AXIS_INDEX;
            int firstIndex = unit.getMinimumEdgeIndex(horizontal);
            int secondIndex = unit.getMaximumEdgeIndex(horizontal);
            int[] unitIndices = horizontal ? this.horizontalUnitIndices : this.verticalUnitIndices;

            if (firstIndex < 0 || firstIndex >= this.count || secondIndex < 0 || secondIndex >= this.count ||
                firstIndex == secondIndex || unitIndices[firstIndex] != unitIndex || unitIndices[secondIndex] != unitIndex) {
                throw Exceptions.newIllegalStateException("Spatial removal requires reciprocal edges for both axes.");
            }
        }

        this.count--;

        this.removeEdge(true, unit.getMaximumHorizontalEdgeIndex());
        this.removeEdge(false, unit.getMaximumVerticalEdgeIndex());
        unit.setMaximumHorizontalEdgeIndex(NO_EDGE);
        unit.setMaximumVerticalEdgeIndex(NO_EDGE);

        this.count--;

        this.removeEdge(true, unit.getMinimumHorizontalEdgeIndex());
        this.removeEdge(false, unit.getMinimumVerticalEdgeIndex());
        unit.setMinimumHorizontalEdgeIndex(NO_EDGE);
        unit.setMinimumVerticalEdgeIndex(NO_EDGE);
    }

    /**
     * Moves a unit's edges by the displacement of its bounds and swaps them past their neighbors until the arrays are sorted again (the leading
     * edge first). A unit that is not indexed, or a zero displacement, changes nothing.
     *
     * @param unit             the unit whose bounds have already moved
     * @param horizontalChange the horizontal displacement
     * @param verticalChange   the vertical displacement
     */
    public void relocate(@NotNull U unit, int horizontalChange, int verticalChange) {
        if (unit.getMinimumHorizontalEdgeIndex() < 0 || horizontalChange == 0 && verticalChange == 0) {
            return;
        }

        int left = this.horizontalCoordinates[unit.getMinimumHorizontalEdgeIndex()] += horizontalChange;
        int right = this.horizontalCoordinates[unit.getMaximumHorizontalEdgeIndex()] += horizontalChange;
        int upper = this.verticalCoordinates[unit.getMinimumVerticalEdgeIndex()] += verticalChange;
        int lower = this.verticalCoordinates[unit.getMaximumVerticalEdgeIndex()] += verticalChange;

        if (horizontalChange > 0) {
            this.relocateEdge(unit, true, true, right, 1);
            this.relocateEdge(unit, true, false, left, 1);
        } else if (horizontalChange < 0) {
            this.relocateEdge(unit, true, false, left, -1);
            this.relocateEdge(unit, true, true, right, -1);
        }

        if (verticalChange > 0) {
            this.relocateEdge(unit, false, true, lower, 1);
            this.relocateEdge(unit, false, false, upper, 1);
        } else if (verticalChange < 0) {
            this.relocateEdge(unit, false, false, upper, -1);
            this.relocateEdge(unit, false, true, lower, -1);
        }
    }

    /**
     * Finds the units whose bounds intersect a rectangle, as {@link SpatialIndex#findInRectangle} defines it: the candidates of the expanded
     * query ({@link #findCandidates(int, int, int, int, int[])}) in its order, accepted by the filter, up to the capacity of the result list.
     *
     * @param left   the left edge
     * @param top    the top edge
     * @param right  the right edge
     * @param bottom the bottom edge
     * @param filter selects the returned units
     * @param result receives the units; cleared first
     * @throws IllegalStateException if the minimum query dimensions are not set
     */
    public void findInRectangle(int left, int top, int right, int bottom, @NotNull UnitFilter filter, @NotNull UnitList result) {
        int[] found = this.filteredResults;
        int foundCount = this.findCandidates(left, top, right, bottom, found);

        result.clear();

        for (int position = 0; position < foundCount; position++) {
            U unit = this.units.apply(found[position]);

            if (filter.accepts(unit) && !result.add(unit)) {
                return;
            }
        }
    }

    /**
     * Finds the unit nearest to a point, as {@link SpatialIndex#findNearest} defines it: the nearest search around the point
     * ({@link #findNearest(int, int, int, Predicate)}) with the filter as its candidate test. A filter may start another nearest search.
     *
     * @param x               the x coordinate of the point
     * @param y               the y coordinate of the point
     * @param maximumDistance the largest distance searched
     * @param filter          selects the candidate units
     * @return the nearest accepted unit, or {@code null} if none is within the distance
     */
    public @Nullable U findNearestAccepted(int x, int y, int maximumDistance, @NotNull UnitFilter filter) {
        UnitFilter outerFilter = this.nearestFilter;

        this.nearestFilter = filter;

        try {
            return this.findNearest(x, y, maximumDistance, this.nearestTest);
        } finally {
            this.nearestFilter = outerFilter;
        }
    }

    /**
     * Collects the expanded rectangle query into the shared results, read back with {@link #getQueryResult}.
     *
     * @param left   the left edge
     * @param top    the top edge
     * @param right  the right edge
     * @param bottom the bottom edge
     * @return the number of results
     * @throws IllegalStateException if the minimum query dimensions are not set
     */
    public int findCandidates(int left, int top, int right, int bottom) {
        return this.findCandidates(left, top, right, bottom, this.queryResults);
    }

    /**
     * Applies the expanded rectangle query: a query box smaller than the minimum query dimensions grows to them, the units with a horizontal edge
     * in the box are marked, those also with a vertical edge in it are marked again, and the doubly marked units are collected in horizontal-edge
     * order; in a grown box, a unit whose minimum edge lies past the requested box is left out. The markers are cleared again and the results
     * end with a {@code 0}.
     * <p>
     * The box edges are read as signed 16-bit values; the grown right and bottom edges, and the edges of a box that does not grow, are read as
     * unsigned 16-bit values.
     *
     * @param left    the left edge
     * @param top     the top edge
     * @param right   the right edge
     * @param bottom  the bottom edge
     * @param results receives the pool slot indices in horizontal-edge order, then a {@code 0}; at least {@link #getQueryCapacity()} entries
     * @return the number of results
     * @throws IllegalStateException if the minimum query dimensions are not set
     */
    public int findCandidates(int left, int top, int right, int bottom, int @NotNull [] results) {
        if (!this.queryBoundsInitialized) {
            throw Exceptions.newIllegalStateException("The expanded query requires the minimum query dimensions.");
        }

        left   = (short) left;
        top    = (short) top;
        right  = (short) right;
        bottom = (short) bottom;

        int[] markers = this.queryMarkers;
        int width = right - left + 1;
        int height = bottom - top + 1;
        int expandedRight = width < this.minimumQueryWidth ? (left - 1 + this.minimumQueryWidth) & WORD_MASK : right & WORD_MASK;
        int expandedBottom = height < this.minimumQueryHeight ? (top - 1 + this.minimumQueryHeight) & WORD_MASK : bottom & WORD_MASK;
        int firstHorizontal = this.findEdge(this.horizontalCoordinates, left);
        int lastHorizontal = this.findEdge(this.horizontalCoordinates, expandedRight);
        int firstVertical = this.findEdge(this.verticalCoordinates, top);
        int lastVertical = this.findEdge(this.verticalCoordinates, expandedBottom);

        if (lastHorizontal <= firstHorizontal || lastVertical <= firstVertical) {
            return 0;
        }

        for (int edgeIndex = firstHorizontal; edgeIndex < lastHorizontal; edgeIndex++) {
            int candidateIndex = this.horizontalUnitIndices[edgeIndex];
            int marker = MARKER_HORIZONTAL;

            if (width < this.minimumQueryWidth && this.bounds.getLeft(this.units.apply(candidateIndex)) > right) {
                marker = 0;
            }

            markers[candidateIndex] = marker;
        }

        for (int edgeIndex = firstVertical; edgeIndex < lastVertical; edgeIndex++) {
            int candidateIndex = this.verticalUnitIndices[edgeIndex];

            if (height < this.minimumQueryHeight && this.bounds.getTop(this.units.apply(candidateIndex)) > bottom) {
                continue;
            }

            if (markers[candidateIndex] == MARKER_HORIZONTAL) {
                markers[candidateIndex] = MARKER_BOTH_AXES;
            }
        }

        int resultCount = 0;

        for (int edgeIndex = firstHorizontal; edgeIndex < lastHorizontal; edgeIndex++) {
            int candidateIndex = this.horizontalUnitIndices[edgeIndex];

            if (markers[candidateIndex] == MARKER_BOTH_AXES) {
                results[resultCount++] = candidateIndex;
            }

            markers[candidateIndex] = 0;
        }

        results[resultCount] = 0;

        return resultCount;
    }

    /**
     * Collects the unexpanded rectangle query into the shared results, read back with {@link #getQueryResult}.
     *
     * @param left   the left edge
     * @param top    the top edge
     * @param right  the right edge
     * @param bottom the bottom edge
     * @return the number of results
     */
    public int findUnexpanded(int left, int top, int right, int bottom) {
        return this.findUnexpanded(left, top, right, bottom, this.queryResults);
    }

    /**
     * Applies the unexpanded rectangle query: advances the search counter, then walks the horizontal edges from the first at {@code left} while
     * they lie before {@code right}; each unit not yet stamped with the counter is stamped and kept when its vertical edges meet
     * {@code [top, bottom)} (by edge index). Nothing is walked when no vertical edge lies in {@code [top, bottom)}. The edges are read as signed
     * 16-bit values.
     *
     * @param left    the left edge
     * @param top     the top edge
     * @param right   the right edge
     * @param bottom  the bottom edge
     * @param results receives the pool slot indices in horizontal-edge order
     * @return the number of results
     */
    public int findUnexpanded(int left, int top, int right, int bottom, int @NotNull [] results) {
        left   = (short) left;
        top    = (short) top;
        right  = (short) right;
        bottom = (short) bottom;

        int stamp = this.searchCounter.advance();
        int firstVertical = this.findEdge(this.verticalCoordinates, top);
        int lastVertical = firstVertical;

        while (lastVertical < this.count && this.verticalCoordinates[lastVertical] < bottom) {
            lastVertical++;
        }

        int firstHorizontal = this.findEdge(this.horizontalCoordinates, left);

        if (lastVertical <= firstVertical) {
            return 0;
        }

        int resultCount = 0;

        for (int edge = firstHorizontal; edge < this.count && this.horizontalCoordinates[edge] < right; edge++) {
            int poolIndex = this.horizontalUnitIndices[edge];

            if (this.queryMarkers[poolIndex] == stamp) {
                continue;
            }

            this.queryMarkers[poolIndex] = stamp;

            U candidate = this.units.apply(poolIndex);

            if (candidate.getMaximumVerticalEdgeIndex() >= firstVertical && candidate.getMinimumVerticalEdgeIndex() < lastVertical) {
                results[resultCount++] = poolIndex;
            }
        }

        return resultCount;
    }

    /**
     * Applies the point query: the unexpanded query over {@code [x, y, x + minimum width, y + minimum height]}, keeping the units whose stored
     * edges contain the point (left edge at most {@code x} and right edge above it, the same vertically). The point is read as signed 16-bit
     * values.
     *
     * @param x       the x coordinate
     * @param y       the y coordinate
     * @param results receives the pool slot indices in query order; at least {@link #getCount()} entries
     * @return the number of results
     */
    public int findAtPoint(int x, int y, int @NotNull [] results) {
        int horizontal = (short) x;
        int vertical = (short) y;
        int resultCount = this.findUnexpanded(horizontal, vertical, horizontal + this.minimumQueryWidth, vertical + this.minimumQueryHeight, results);
        int keptCount = 0;

        for (int index = 0; index < resultCount; index++) {
            U candidate = this.units.apply(results[index]);

            if (this.horizontalCoordinates[candidate.getMinimumHorizontalEdgeIndex()] <= horizontal &&
                horizontal < this.horizontalCoordinates[candidate.getMaximumHorizontalEdgeIndex()] &&
                this.verticalCoordinates[candidate.getMinimumVerticalEdgeIndex()] <= vertical &&
                vertical < this.verticalCoordinates[candidate.getMaximumVerticalEdgeIndex()]) {
                results[keptCount++] = results[index];
            }
        }

        return keptCount;
    }

    /**
     * Finds the nearest accepted unit in the radius box around a unit, excluding that unit.
     *
     * @param unit      the searching unit
     * @param radius    the radius, read as an unsigned 16-bit value
     * @param predicate the candidate test, run before the distance comparison
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable U findNearest(@NotNull U unit, int radius, @NotNull Predicate<? super U> predicate) {
        return this.findNearest(unit, radius, predicate, null);
    }

    /**
     * Finds the nearest accepted unit around a unit, passing that unit as the candidate test's context.
     *
     * @param unit      the searching unit and candidate-test context
     * @param radius    the radius, read as an unsigned 16-bit value
     * @param predicate the candidate and context test, run before the distance comparison
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable U findNearest(@NotNull U unit, int radius, @NotNull BiPredicate<? super U, ? super U> predicate) {
        return this.findNearest(unit, radius, null, predicate);
    }

    private @Nullable U findNearest(@NotNull U unit, int radius,
                                    @Nullable Predicate<? super U> predicate, @Nullable BiPredicate<? super U, ? super U> contextualPredicate) {
        int centerX = this.bounds.getCenterX(unit);
        int centerY = this.bounds.getCenterY(unit);
        int searchRadius = radius & WORD_MASK;
        int left = (short) (centerX - searchRadius);
        int right = (short) (centerX + searchRadius);
        int top = (short) (centerY - searchRadius);
        int bottom = (short) (centerY + searchRadius);

        return this.findNearest(unit, left, top, right, bottom, predicate, contextualPredicate);
    }

    /**
     * Finds the nearest accepted unit in a rectangle, starting at the searching unit's edges, or at its center when it is not placed.
     * <p>
     * Bounds are half-open and read as signed 16-bit values. Each round visits left, right, up and down before shrinking the box. Candidate tests
     * precede the distance comparison. The shared query markers, results and search counter are unchanged.
     *
     * @param unit      the searching unit, excluded before the candidate test
     * @param left      the inclusive left bound
     * @param top       the inclusive top bound
     * @param right     the exclusive right bound
     * @param bottom    the exclusive bottom bound
     * @param predicate the candidate test
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable U findNearest(@NotNull U unit, int left, int top, int right, int bottom, @NotNull Predicate<? super U> predicate) {
        return this.findNearest(unit, left, top, right, bottom, predicate, null);
    }

    /**
     * Finds the nearest accepted unit in a rectangle, passing the searching unit as the candidate test's context.
     * <p>
     * Bounds are half-open and read as signed 16-bit values. Each round visits left, right, up and down before shrinking the box. Candidate tests
     * precede the distance comparison. The shared query markers, results and search counter are unchanged.
     *
     * @param unit      the searching unit and candidate-test context, excluded before the candidate test
     * @param left      the inclusive left bound
     * @param top       the inclusive top bound
     * @param right     the exclusive right bound
     * @param bottom    the exclusive bottom bound
     * @param predicate the candidate and context test
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable U findNearest(@NotNull U unit, int left, int top, int right, int bottom, @NotNull BiPredicate<? super U, ? super U> predicate) {
        return this.findNearest(unit, left, top, right, bottom, null, predicate);
    }

    private @Nullable U findNearest(@NotNull U unit, int left, int top, int right, int bottom,
                                    @Nullable Predicate<? super U> predicate, @Nullable BiPredicate<? super U, ? super U> contextualPredicate) {
        int leftIndex;
        int rightIndex;
        int upperIndex;
        int lowerIndex;

        if (this.bounds.isPlaced(unit)) {
            rightIndex = unit.getMinimumHorizontalEdgeIndex() + 1;
            lowerIndex = unit.getMinimumVerticalEdgeIndex() + 1;
            leftIndex  = unit.getMaximumHorizontalEdgeIndex() - 1;
            upperIndex = unit.getMaximumVerticalEdgeIndex() - 1;
        } else {
            int horizontal = this.findEdge(this.horizontalCoordinates, this.bounds.getCenterX(unit));
            int vertical = this.findEdge(this.verticalCoordinates, this.bounds.getCenterY(unit));

            leftIndex  = horizontal - 1;
            rightIndex = horizontal;
            upperIndex = vertical - 1;
            lowerIndex = vertical;
        }

        int centerY = this.bounds.getCenterY(unit);
        int centerX = this.bounds.getCenterX(unit);

        return this.findNearest(unit,
                                centerX, centerY,
                                leftIndex, rightIndex, upperIndex, lowerIndex,
                                (short) left, (short) top, (short) right, (short) bottom,
                                predicate, contextualPredicate);
    }

    /**
     * Finds the nearest accepted unit in the radius box around a position.
     *
     * @param x         the x coordinate, read as a signed 16-bit value
     * @param y         the y coordinate, read as a signed 16-bit value
     * @param radius    the radius, read as an unsigned 16-bit value
     * @param predicate the candidate test, run before the distance comparison
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable U findNearest(int x, int y, int radius, @NotNull Predicate<? super U> predicate) {
        int centerX = (short) x;
        int centerY = (short) y;
        int searchRadius = radius & WORD_MASK;

        return this.findNearest(centerX, centerY,
                                (short) (centerX - searchRadius), (short) (centerY - searchRadius),
                                (short) (centerX + searchRadius), (short) (centerY + searchRadius),
                                predicate);
    }

    /**
     * Finds the nearest accepted unit in a rectangle around a position.
     * <p>
     * Bounds are half-open and read as signed 16-bit values. Each round visits left, right, up and down before shrinking the box. Candidate tests
     * precede the distance comparison. The shared query markers, results and search counter are unchanged.
     *
     * @param x         the x coordinate, read as a signed 16-bit value
     * @param y         the y coordinate, read as a signed 16-bit value
     * @param left      the inclusive left bound
     * @param top       the inclusive top bound
     * @param right     the exclusive right bound
     * @param bottom    the exclusive bottom bound
     * @param predicate the candidate test
     * @return the nearest accepted unit, or {@code null}
     */
    public @Nullable U findNearest(int x, int y, int left, int top, int right, int bottom, @NotNull Predicate<? super U> predicate) {
        int centerX = (short) x;
        int centerY = (short) y;
        int horizontal = this.findEdge(this.horizontalCoordinates, centerX);
        int vertical = this.findEdge(this.verticalCoordinates, centerY);

        return this.findNearest(null,
                                centerX, centerY,
                                horizontal - 1, horizontal, vertical - 1, vertical,
                                (short) left, (short) top, (short) right, (short) bottom,
                                predicate, null);
    }

    /**
     * Applies the nearest search of the public overloads: from the start edge indices, each round visits the next left, right, upper and lower
     * edge, tests a candidate inside the bounds that is not the searcher, keeps the nearest accepted one (by the approximate distance of
     * {@link IntegerMathUtils}) and shrinks the bounds to its distance, until every scan has left the bounds.
     *
     * @param searcher            the searching unit, excluded and passed to the contextual test, or {@code null} for a position search
     * @param centerX             the horizontal center of the distance comparison
     * @param centerY             the vertical center of the distance comparison
     * @param leftIndex           the first horizontal edge index of the left scan
     * @param rightIndex          the first horizontal edge index of the right scan
     * @param upperIndex          the first vertical edge index of the upward scan
     * @param lowerIndex          the first vertical edge index of the downward scan
     * @param left                the inclusive left bound
     * @param top                 the inclusive top bound
     * @param right               the exclusive right bound
     * @param bottom              the exclusive bottom bound
     * @param predicate           the candidate test, or {@code null} with a contextual test
     * @param contextualPredicate the candidate and context test, or {@code null} with a candidate test
     * @return the nearest accepted unit, or {@code null}
     * @throws IllegalStateException if not exactly one candidate test is supplied or the contextual test has no searcher
     */
    private @Nullable U findNearest(@Nullable U searcher,
                                    int centerX, int centerY,
                                    int leftIndex, int rightIndex, int upperIndex, int lowerIndex,
                                    int left, int top, int right, int bottom,
                                    @Nullable Predicate<? super U> predicate, @Nullable BiPredicate<? super U, ? super U> contextualPredicate) {
        if (predicate == null) {
            if (contextualPredicate == null || searcher == null) {
                throw Exceptions.newIllegalStateException("The nearest search requires a candidate test and its unit context.");
            }
        } else if (contextualPredicate != null) {
            throw Exceptions.newIllegalStateException("The nearest search requires exactly one candidate test.");
        }

        int bestDistance = IntegerMathUtils.getApproximateDistance(0, 0,
                                                                   Math.max(right - centerX, centerX - left), Math.max(bottom - centerY, centerY - top));
        U result = null;
        boolean active;

        do {
            active = false;

            boolean improved = false;

            if (leftIndex >= 0) {
                U candidate = this.units.apply(this.horizontalUnitIndices[leftIndex]);
                int candidateX = this.bounds.getCenterX(candidate);

                if (candidateX < left) {
                    leftIndex = -1;
                } else {
                    int candidateY = this.bounds.getCenterY(candidate);

                    if (candidateY >= top && candidateY < bottom && candidate != searcher &&
                        (predicate != null ? predicate.test(candidate) : contextualPredicate.test(candidate, searcher))) {
                        int distance = IntegerMathUtils.getApproximateDistance(candidateX, candidateY, centerX, centerY);

                        if (distance < bestDistance) {
                            bestDistance = distance;
                            result       = candidate;
                            improved     = true;
                        }
                    }
                }

                leftIndex--;
                active = true;
            }

            if (rightIndex < this.count) {
                U candidate = this.units.apply(this.horizontalUnitIndices[rightIndex]);
                int candidateX = this.bounds.getCenterX(candidate);

                if (candidateX >= right) {
                    rightIndex = this.count;
                } else {
                    int candidateY = this.bounds.getCenterY(candidate);

                    if (candidateY >= top && candidateY < bottom && candidate != searcher &&
                        (predicate != null ? predicate.test(candidate) : contextualPredicate.test(candidate, searcher))) {
                        int distance = IntegerMathUtils.getApproximateDistance(candidateX, candidateY, centerX, centerY);

                        if (distance < bestDistance) {
                            bestDistance = distance;
                            result       = candidate;
                            improved     = true;
                        }
                    }
                }

                rightIndex++;
                active = true;
            }

            if (upperIndex >= 0) {
                U candidate = this.units.apply(this.verticalUnitIndices[upperIndex]);
                int candidateY = this.bounds.getCenterY(candidate);

                if (candidateY < top) {
                    upperIndex = -1;
                } else {
                    int candidateX = this.bounds.getCenterX(candidate);

                    if (candidateX >= left && candidateX < right && candidate != searcher &&
                        (predicate != null ? predicate.test(candidate) : contextualPredicate.test(candidate, searcher))) {
                        int distance = IntegerMathUtils.getApproximateDistance(candidateX, candidateY, centerX, centerY);

                        if (distance < bestDistance) {
                            bestDistance = distance;
                            result       = candidate;
                            improved     = true;
                        }
                    }
                }

                upperIndex--;
                active = true;
            }

            if (lowerIndex < this.count) {
                U candidate = this.units.apply(this.verticalUnitIndices[lowerIndex]);
                int candidateY = this.bounds.getCenterY(candidate);

                if (candidateY >= bottom) {
                    lowerIndex = this.count;
                } else {
                    int candidateX = this.bounds.getCenterX(candidate);

                    if (candidateX >= left && candidateX < right && candidate != searcher &&
                        (predicate != null ? predicate.test(candidate) : contextualPredicate.test(candidate, searcher))) {
                        int distance = IntegerMathUtils.getApproximateDistance(candidateX, candidateY, centerX, centerY);

                        if (distance < bestDistance) {
                            bestDistance = distance;
                            result       = candidate;
                            improved     = true;
                        }
                    }
                }

                lowerIndex++;
                active = true;
            }

            if (improved) {
                if (left < centerX - bestDistance) {
                    left = (short) (centerX - bestDistance);
                }

                if (centerX + bestDistance < right) {
                    right = (short) (centerX + bestDistance);
                }

                if (top < centerY - bestDistance) {
                    top = (short) (centerY - bestDistance);
                }

                if (centerY + bestDistance < bottom) {
                    bottom = (short) (centerY + bestDistance);
                }
            }
        } while (active);

        return result;
    }

    /**
     * Finds the first edge whose coordinate is at least the given one: the number of edges below it.
     *
     * @param coordinates the edge coordinates of one axis
     * @param coordinate  the coordinate
     * @return the edge index, from {@code 0} to the count
     */
    private int findEdge(int @NotNull [] coordinates, int coordinate) {
        int lowerIndex = 0;
        int upperIndex = this.count;
        int doubledCoordinate = coordinate * EDGE_SEARCH_SCALE - 1;

        while (lowerIndex < upperIndex) {
            int middleIndex = (lowerIndex + upperIndex) >>> BINARY_SEARCH_MIDPOINT_SHIFT;

            if (doubledCoordinate < coordinates[middleIndex] * EDGE_SEARCH_SCALE) {
                upperIndex = middleIndex;
            } else {
                lowerIndex = middleIndex + 1;
            }
        }

        return upperIndex;
    }

    /**
     * Removes one entry with the already lowered count: nothing when the entry is at or past the count; otherwise every entry from the next one
     * through the count's slot has its unit's matching edge index (minimum first) lowered by one, and those entries move down one slot, the entry
     * at the count staying where it was.
     *
     * @param horizontal whether the entry is on the horizontal axis
     * @param edge       the edge index of the entry
     */
    private void removeEdge(boolean horizontal, int edge) {
        if (edge >= this.count) {
            return;
        }

        int[] unitIndices = horizontal ? this.horizontalUnitIndices : this.verticalUnitIndices;
        int[] coordinates = horizontal ? this.horizontalCoordinates : this.verticalCoordinates;

        for (int source = edge + 1; source <= this.count; source++) {
            int poolIndex = unitIndices[source];

            if (poolIndex == 0) {
                continue;
            }

            moveEdgeIndex(this.units.apply(poolIndex), horizontal, source, source - 1);
        }

        System.arraycopy(unitIndices, edge + 1, unitIndices, edge, this.count - edge);
        System.arraycopy(coordinates, edge + 1, coordinates, edge, this.count - edge);
    }

    private void relocateEdge(@NotNull U unit, boolean horizontal, boolean maximum, int coordinate, int direction) {
        int[] unitIndices = horizontal ? this.horizontalUnitIndices : this.verticalUnitIndices;
        int[] coordinates = horizontal ? this.horizontalCoordinates : this.verticalCoordinates;
        int edgeIndex = maximum ? unit.getMaximumEdgeIndex(horizontal) : unit.getMinimumEdgeIndex(horizontal);
        int neighborIndex = edgeIndex + direction;

        while (neighborIndex >= 0 && neighborIndex < this.count &&
               (direction > 0 ? coordinate > coordinates[neighborIndex] : coordinate < coordinates[neighborIndex])) {
            moveEdgeIndex(this.units.apply(unitIndices[neighborIndex]), horizontal, neighborIndex, neighborIndex - direction);

            int previousUnitIndex = unitIndices[edgeIndex];
            int previousCoordinate = coordinates[edgeIndex];

            unitIndices[edgeIndex]     = unitIndices[neighborIndex];
            coordinates[edgeIndex]     = coordinates[neighborIndex];
            unitIndices[neighborIndex] = previousUnitIndex;
            coordinates[neighborIndex] = previousCoordinate;

            edgeIndex = (maximum ? unit.getMaximumEdgeIndex(horizontal) : unit.getMinimumEdgeIndex(horizontal)) + direction;

            setEdgeIndex(unit, horizontal, maximum, edgeIndex);

            neighborIndex = edgeIndex + direction;
        }
    }

    private void insertEdge(@NotNull U unit, int coordinate, boolean horizontal, boolean maximum) {
        int[] unitIndices = horizontal ? this.horizontalUnitIndices : this.verticalUnitIndices;
        int[] coordinates = horizontal ? this.horizontalCoordinates : this.verticalCoordinates;
        int upperIndex = this.findEdge(coordinates, coordinate);

        for (int existingIndex = this.count - 1; existingIndex >= upperIndex; existingIndex--) {
            moveEdgeIndex(this.units.apply(unitIndices[existingIndex]), horizontal, existingIndex, existingIndex + 1);
        }

        System.arraycopy(unitIndices, upperIndex, unitIndices, upperIndex + 1, this.count - upperIndex);
        System.arraycopy(coordinates, upperIndex, coordinates, upperIndex + 1, this.count - upperIndex);

        setEdgeIndex(unit, horizontal, maximum, upperIndex);

        unitIndices[upperIndex] = unit.getIndex();
        coordinates[upperIndex] = coordinate;
    }

    /**
     * Writes this part of the simulation state: the edge count, both axes' slot indices and coordinates (every entry, also those past the
     * count), the query markers, the search counter, the minimum query dimensions and whether they are set. The edge indices on the units are
     * written with the units.
     *
     * @param writer the writer of the snapshot
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        writer.writeInt(this.count);
        writer.writeInts(this.horizontalUnitIndices);
        writer.writeInts(this.horizontalCoordinates);
        writer.writeInts(this.verticalUnitIndices);
        writer.writeInts(this.verticalCoordinates);
        writer.writeInts(this.queryMarkers);
        writer.writeInt(this.searchCounter.getValue());
        writer.writeInt(this.minimumQueryWidth);
        writer.writeInt(this.minimumQueryHeight);
        writer.writeBoolean(this.queryBoundsInitialized);
    }

    /**
     * Reads this part of the simulation state back, replacing the current values: the edges, the query markers, the search counter value and
     * the query dimensions.
     *
     * @param reader the reader of the snapshot
     * @throws IOException if the snapshot ends early, holds another capacity or an edge count outside it
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        int edgeCount = reader.readInt();

        if (edgeCount < 0 || edgeCount > this.horizontalUnitIndices.length) {
            throw Exceptions.newIOException("The state snapshot holds {0} edges of an index of {1}.", edgeCount, this.horizontalUnitIndices.length);
        }

        this.count = edgeCount;

        reader.readInts(this.horizontalUnitIndices);
        reader.readInts(this.horizontalCoordinates);
        reader.readInts(this.verticalUnitIndices);
        reader.readInts(this.verticalCoordinates);
        reader.readInts(this.queryMarkers);
        this.searchCounter.setValue(reader.readInt());

        this.minimumQueryWidth      = reader.readInt();
        this.minimumQueryHeight     = reader.readInt();
        this.queryBoundsInitialized = reader.readBoolean();
    }
}
