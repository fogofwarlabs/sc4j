package org.fogofwarlabs.sc4j.engine.visibility;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Holds the sight descriptors of one map width and reveals them on a {@link SightGrid}: for each sight radius from {@code 1} tile, the tiles of
 * a midpoint-circle mask ordered as a spiral from the center, each record naming the one or two records nearer the center that a ground reveal
 * tests before it reveals the record's tile.
 * <p>
 * A ground reveal writes the central nine tiles, then each further tile with a predecessor whose cached flags (the flags its reveal returned, or
 * {@link SightGrid#getNotVisibleFlags()} if it was not revealed) have none of the blocking bits; an air reveal writes every record but the last
 * nine. Both clip at the map edges.
 * <p>
 * Determinism contract: the records are built once, in spiral order; a reveal visits them in that order, changes only the grid's tiles and the
 * descriptor's cached flags, draws no random value and allocates nothing.
 */
public final class SightDescriptors {
    /**
     * The number of central records (the three by three tiles around the center), which every reveal writes.
     */
    public static final int CENTRAL_RECORD_COUNT = 9;

    /**
     * The horizontal steps of the four spiral sides: right, down, left, up.
     */
    private static final int @NotNull [] HORIZONTAL_STEPS = {1, 0, -1, 0};
    /**
     * The vertical steps of the four spiral sides: right, down, left, up.
     */
    private static final int @NotNull [] VERTICAL_STEPS = {0, 1, 0, -1};
    /**
     * The side length of the first spiral ring around the central tiles; each ring adds this much.
     */
    private static final int SPIRAL_SIDE_GROWTH = 2;
    /**
     * The tiles a spiral side longer than the mask width visits: only the tile at its end, which the side jumps to.
     */
    private static final int JUMPED_SIDE_TILE_COUNT = 1;
    /**
     * The largest number of predecessor records a sight record has.
     */
    private static final int MAXIMUM_PREDECESSOR_COUNT = 2;
    /**
     * The number of predecessor records of a sight record with only a first predecessor.
     */
    private static final int SINGLE_PREDECESSOR_COUNT = 1;
    /**
     * The records an air reveal leaves out at the end of a descriptor.
     */
    private static final int AIR_EXCLUDED_RECORD_COUNT = 9;
    /**
     * The factor from a sight radius to the last row and column index of its circle mask, whose width is one more.
     */
    private static final int CIRCLE_DIAMETER_FACTOR = 2;
    /**
     * The exclusive end, relative to the center column, of the three central columns every circle mask includes.
     */
    private static final int INITIAL_MASK_UPPER_OFFSET = 2;
    /**
     * The amount the midpoint-circle step grows in each iteration and the diameter shrinks when the outer edge moves in.
     */
    private static final int CIRCLE_STEP_INCREMENT = 2;

    /**
     * Holds the records of one sight radius as one array per field, indexed by the record index.
     */
    private static final class Descriptor {
        private final int width;
        private final int @NotNull [] horizontal;
        private final int @NotNull [] vertical;
        private final int @NotNull [] offsets;

        private final int @NotNull [] firstPredecessors;
        private final int @NotNull [] secondPredecessors;
        private final int @NotNull [] predecessorCounts;

        private final int @NotNull [] cachedFlags;

        private Descriptor(int width, int count) {
            this.width      = width;
            this.horizontal = new int[count];
            this.vertical   = new int[count];
            this.offsets    = new int[count];

            this.firstPredecessors  = new int[count];
            this.secondPredecessors = new int[count];
            this.predecessorCounts  = new int[count];

            this.cachedFlags = new int[count];
        }
    }

    private final int mapWidth;
    private final @NotNull Descriptor @NotNull [] descriptors;

    /**
     * Creates a {@link SightDescriptors} instance with one descriptor per sight radius, from {@code 1} up to the descriptor count.
     *
     * @param mapWidth        the map width in tiles, which the tile offsets of the records use
     * @param descriptorCount the number of descriptors, the largest sight radius in tiles
     * @throws IllegalArgumentException if the map width or the descriptor count is not positive
     * @throws IllegalStateException    if the traversal references a record that is not initialized
     */
    public SightDescriptors(int mapWidth, int descriptorCount) {
        if (mapWidth <= 0 || descriptorCount <= 0) {
            throw Exceptions.newIllegalArgumentException("Sight descriptors require a positive map width and descriptor count: {0}, {1}", mapWidth,
                                                         descriptorCount);
        }

        this.mapWidth    = mapWidth;
        this.descriptors = new Descriptor[descriptorCount];

        for (int radiusIndex = 0; radiusIndex < this.descriptors.length; radiusIndex++) {
            this.descriptors[radiusIndex] = createDescriptor(radiusIndex + 1, mapWidth);
        }
    }

    private static @NotNull Descriptor createDescriptor(int radius, int mapWidth) {
        int width = radius * CIRCLE_DIAMETER_FACTOR + 1;
        boolean[] mask = new boolean[width * width];

        for (int row = radius - 1; row <= radius + 1; row++) {
            Arrays.fill(mask, row * width + radius - 1, row * width + radius + INITIAL_MASK_UPPER_OFFSET, true);
        }

        int inner = radius;
        int outer = radius * CIRCLE_DIAMETER_FACTOR;
        int error = radius * radius;
        int threshold = radius * (radius - 1);
        int step = 0;
        int diameter = radius * CIRCLE_DIAMETER_FACTOR;

        do {
            int firstHorizontal = radius * CIRCLE_DIAMETER_FACTOR - outer;

            Arrays.fill(mask, (radius * CIRCLE_DIAMETER_FACTOR - inner) * width + firstHorizontal,
                        (radius * CIRCLE_DIAMETER_FACTOR - inner) * width + outer + 1, true);
            Arrays.fill(mask, inner * width + firstHorizontal, inner * width + outer + 1, true);

            firstHorizontal = radius * CIRCLE_DIAMETER_FACTOR - inner;

            Arrays.fill(mask, (radius * CIRCLE_DIAMETER_FACTOR - outer) * width + firstHorizontal,
                        (radius * CIRCLE_DIAMETER_FACTOR - outer) * width + inner + 1, true);
            Arrays.fill(mask, outer * width + firstHorizontal, outer * width + inner + 1, true);

            step += CIRCLE_STEP_INCREMENT;
            error += 1 - step;
            inner++;

            if (Integer.compareUnsigned(error, threshold) <= 0) {
                outer--;
                diameter -= CIRCLE_STEP_INCREMENT;
                threshold -= diameter;
            }
        } while (inner <= outer);

        int count = 0;

        for (boolean included : mask) {
            if (included) {
                count++;
            }
        }

        Descriptor descriptor = new Descriptor(width, count);

        descriptor.firstPredecessors[0]  = -1;
        descriptor.secondPredecessors[0] = -1;

        int[] recordIndices = new int[mask.length];

        Arrays.fill(recordIndices, -1);

        int center = radius * width + radius;

        recordIndices[center] = 0;

        int nextRecord = 1;
        int horizontal = -1;
        int vertical = -1;
        int sideLength = SPIRAL_SIDE_GROWTH;

        while (nextRecord < count) {
            for (int direction = 0; direction < HORIZONTAL_STEPS.length; direction++) {
                int horizontalStep = HORIZONTAL_STEPS[direction];
                int verticalStep = VERTICAL_STEPS[direction];
                int remaining;

                if (sideLength > width) {
                    horizontal += horizontalStep * sideLength;
                    vertical += verticalStep * sideLength;
                    remaining = JUMPED_SIDE_TILE_COUNT;
                } else {
                    horizontal += horizontalStep;
                    vertical += verticalStep;
                    remaining = Math.min(sideLength, width - 1);
                }

                while (remaining-- > 0) {
                    int position = center + vertical * width + horizontal;

                    if (mask[position]) {
                        int predecessorHorizontal = horizontal - Integer.signum(horizontal);
                        int predecessorVertical = vertical - Integer.signum(vertical);
                        int horizontalDistance = Math.abs(predecessorHorizontal);
                        int verticalDistance = Math.abs(predecessorVertical);
                        int first = recordIndices[center + predecessorVertical * width + predecessorHorizontal];
                        int second = first;
                        int predecessorCount = SINGLE_PREDECESSOR_COUNT;

                        if (horizontalDistance != verticalDistance && (horizontal != 0 || horizontalStep == 0) &&
                            (vertical != 0 || verticalStep == 0)) {
                            if (horizontalDistance > verticalDistance) {
                                predecessorVertical += vertical < 0 ? -1 : 1;
                            } else {
                                predecessorHorizontal += horizontal < 0 ? -1 : 1;
                            }

                            second           = recordIndices[center + predecessorVertical * width + predecessorHorizontal];
                            predecessorCount = MAXIMUM_PREDECESSOR_COUNT;
                        }

                        if (first < 0 || second < 0) {
                            throw Exceptions.newIllegalStateException("The sight traversal references a record that is not initialized.");
                        }

                        descriptor.horizontal[nextRecord]         = horizontal;
                        descriptor.vertical[nextRecord]           = vertical;
                        descriptor.offsets[nextRecord]            = vertical * mapWidth + horizontal;
                        descriptor.firstPredecessors[nextRecord]  = first;
                        descriptor.secondPredecessors[nextRecord] = second;
                        descriptor.predecessorCounts[nextRecord]  = predecessorCount;
                        recordIndices[position]                   = nextRecord++;
                    }

                    horizontal += horizontalStep;
                    vertical += verticalStep;
                }

                horizontal -= horizontalStep;
                vertical -= verticalStep;
            }

            if (sideLength < width - 1) {
                horizontal--;
                vertical--;
            }

            sideLength += SPIRAL_SIDE_GROWTH;
        }

        return descriptor;
    }

    /**
     * @return the map width in tiles the tile offsets of the records use
     */
    public int getMapWidth() {
        return this.mapWidth;
    }

    /**
     * @return the number of descriptors, the largest sight radius in tiles
     */
    public int getDescriptorCount() {
        return this.descriptors.length;
    }

    /**
     * @param radiusIndex the descriptor index, the sight radius in tiles minus one
     * @return the number of ordered records, including the central nine records
     */
    public int getRecordCount(int radiusIndex) {
        return this.descriptors[radiusIndex].offsets.length;
    }

    /**
     * @param radiusIndex the descriptor index
     * @param recordIndex the ordered record index
     * @return the index of the first record nearer the center a ground reveal tests, {@code -1} for the center
     */
    public int getFirstPredecessor(int radiusIndex, int recordIndex) {
        return this.descriptors[radiusIndex].firstPredecessors[recordIndex];
    }

    /**
     * @param radiusIndex the descriptor index
     * @param recordIndex the ordered record index
     * @return the index of the second record nearer the center a ground reveal tests, the first one if there is one only, {@code -1} for the center
     */
    public int getSecondPredecessor(int radiusIndex, int recordIndex) {
        return this.descriptors[radiusIndex].secondPredecessors[recordIndex];
    }

    /**
     * @param radiusIndex the descriptor index
     * @param recordIndex the ordered record index
     * @return the tile index offset of the record from the center tile
     */
    public int getTileOffset(int radiusIndex, int recordIndex) {
        return this.descriptors[radiusIndex].offsets[recordIndex];
    }

    /**
     * @param radiusIndex the descriptor index
     * @param recordIndex the ordered record index
     * @return the tile flags the last ground reveal cached for the record
     */
    public int getCachedFlags(int radiusIndex, int recordIndex) {
        return this.descriptors[radiusIndex].cachedFlags[recordIndex];
    }

    /**
     * @param radiusIndex the descriptor index
     * @param recordIndex the ordered record index
     * @return the signed horizontal tile offset of the record from the center
     */
    public int getHorizontalOffset(int radiusIndex, int recordIndex) {
        return this.descriptors[radiusIndex].horizontal[recordIndex];
    }

    /**
     * @param radiusIndex the descriptor index
     * @param recordIndex the ordered record index
     * @return the signed vertical tile offset of the record from the center
     */
    public int getVerticalOffset(int radiusIndex, int recordIndex) {
        return this.descriptors[radiusIndex].vertical[recordIndex];
    }

    /**
     * @param radiusIndex the descriptor index
     * @param recordIndex the ordered record index
     * @return the number of predecessor records, {@code 0} for the center
     */
    public int getPredecessorCount(int radiusIndex, int recordIndex) {
        return this.descriptors[radiusIndex].predecessorCounts[recordIndex];
    }


    /**
     * Reveals the tiles of a descriptor around a tile to players.
     *
     * @param grid        the tile storage, as wide as the descriptors' map width
     * @param column      the column of the center tile
     * @param row         the row of the center tile
     * @param air         whether the reveal is an air reveal, which tests no predecessor
     * @param playerMask  the players, one bit per player index
     * @param radiusIndex the descriptor index
     * @param blockers    the tile flag bits that block the sight of a ground reveal (the players' bits and the blocking terrain bits)
     */
    public void reveal(@NotNull SightGrid grid, int column, int row, boolean air, int playerMask, int radiusIndex, int blockers) {
        Descriptor descriptor = this.descriptors[radiusIndex];
        int width = grid.getWidth();
        int height = grid.getHeight();
        int radius = descriptor.width / CIRCLE_DIAMETER_FACTOR;
        boolean clipped = column < radius || column + radius >= width || row < radius || row + radius >= height;
        int notVisible = grid.getNotVisibleFlags();
        int center = row * width + column;
        int recordCount = air ? descriptor.offsets.length - AIR_EXCLUDED_RECORD_COUNT : descriptor.offsets.length;

        for (int recordIndex = 0; recordIndex < recordCount; recordIndex++) {
            if (!air) {
                descriptor.cachedFlags[recordIndex] = notVisible;
            }

            int targetColumn = column + descriptor.horizontal[recordIndex];
            int targetRow = row + descriptor.vertical[recordIndex];

            if (clipped && (Integer.compareUnsigned(targetColumn, width) >= 0 || Integer.compareUnsigned(targetRow, height) >= 0)) {
                continue;
            }

            if (!air && recordIndex >= CENTRAL_RECORD_COUNT) {
                int predecessors = descriptor.predecessorCounts[recordIndex];
                boolean visible = predecessors >= MAXIMUM_PREDECESSOR_COUNT &&
                                  (descriptor.cachedFlags[descriptor.secondPredecessors[recordIndex]] & blockers) == 0 ||
                                  predecessors >= SINGLE_PREDECESSOR_COUNT &&
                                  (descriptor.cachedFlags[descriptor.firstPredecessors[recordIndex]] & blockers) == 0;

                if (!visible) {
                    continue;
                }
            }

            int tileFlags = grid.revealTile(center + descriptor.offsets[recordIndex], playerMask);

            if (!air) {
                descriptor.cachedFlags[recordIndex] = tileFlags;
            }
        }
    }
}
