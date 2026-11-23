package org.fogofwarlabs.sc4j.engine.client.world;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.display.DetailSet;
import org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection;
import org.fogofwarlabs.sc4j.engine.client.view.UnitView;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Chooses the frames of a unit type's picture in a sprite sheet laid out as a grid: one row per facing (row {@code 0} faces up on the
 * screen, the rows turn clockwise; a sheet of one row has no facings) and one column per frame of the unit's poses. For each {@link UnitPose}
 * the sprite names its frame columns; a pose without columns shows the idle columns. A moving, attacking or idle unit steps through its
 * columns every {@link #getStepFrames()} game loops; a building under construction shows the column of its construction progress.
 * <p>
 * The pose comes from the unit's view: under construction while its progress is below complete and the sprite has construction columns,
 * else attacking, else moving, else idle. The facing row comes from the unit's heading turned to the screen through the projection
 * ({@link WorldProjection#toScreenDirection}).
 * <p>
 * The sheet may have detail sheets ({@link DetailSet}) with the same frames at a multiple of the size; a layer places the sheet's frame and
 * draws its pixels from the detail of the drawing's pixels per sheet pixel.
 */
public final class UnitSprite {
    /**
     * The game loops each frame of a pose shows unless the sprite sets another step.
     */
    public static final int DEFAULT_STEP_FRAMES = 4;

    private static final int HALF = 2;

    /**
     * Builds {@link UnitSprite} instances.
     */
    public static final class Builder {
        private final @NotNull DetailSet<SpriteSheet> sheets;
        private final int rows;

        private final @NotNull Map<@NotNull UnitPose, int @NotNull []> columns;
        private int stepFrames;

        /**
         * Creates a {@link Builder} instance of a sheet without pose columns, stepping every {@value UnitSprite#DEFAULT_STEP_FRAMES} game loops.
         *
         * @param sheets the sprite sheet with its detail sheets
         * @param rows   the facing rows of the sheet's grid, {@code 1} for a sheet without facings
         * @throws IllegalArgumentException if the rows are not positive or do not divide the sheet's frames
         */
        public Builder(@NotNull DetailSet<SpriteSheet> sheets, int rows) {
            SpriteSheet sheet = sheets.getResource();

            if (rows < 1 || sheet.getFrameCount() % rows != 0) {
                throw Exceptions.newIllegalArgumentException("A sheet of {0} frames has no grid of {1} rows.", sheet.getFrameCount(), rows);
            }

            this.sheets = sheets;
            this.rows   = rows;

            this.columns    = new EnumMap<>(UnitPose.class);
            this.stepFrames = DEFAULT_STEP_FRAMES;
        }

        /**
         * Sets the frame columns of a pose.
         *
         * @param pose    the pose
         * @param columns the columns in their order, at least one
         * @return this instance for chaining
         * @throws IllegalArgumentException if no column is given or a column is outside the grid
         */
        public @NotNull Builder setColumns(@NotNull UnitPose pose, int @NotNull ... columns) {
            int columnCount = this.sheets.getResource().getFrameCount() / this.rows;

            if (columns.length == 0) {
                throw Exceptions.newIllegalArgumentException("The pose {0} needs a column.", pose);
            }

            for (int column : columns) {
                if (column < 0 || column >= columnCount) {
                    throw Exceptions.newIllegalArgumentException("The column {0} of the pose {1} is outside the grid of {2} columns.",
                                                                 column, pose, columnCount);
                }
            }

            this.columns.put(pose, columns.clone());

            return this;
        }

        /**
         * Sets the game loops each frame of a moving, attacking or idle pose shows.
         *
         * @param stepFrames the game loops per frame, at least {@code 1}
         * @return this instance for chaining
         * @throws IllegalArgumentException if the game loops are not positive
         */
        public @NotNull Builder setStepFrames(int stepFrames) {
            if (stepFrames < 1) {
                throw Exceptions.newIllegalArgumentException("Invalid game loops per frame: {0}", stepFrames);
            }

            this.stepFrames = stepFrames;

            return this;
        }

        /**
         * Builds a {@link UnitSprite} instance of the sheet, the poses and the step.
         *
         * @return the built {@link UnitSprite} instance
         * @throws IllegalStateException if the idle pose has no columns
         */
        public @NotNull UnitSprite build() {
            if (!this.columns.containsKey(UnitPose.IDLE)) {
                throw Exceptions.newIllegalStateException("A unit sprite needs the columns of its idle pose.");
            }

            return new UnitSprite(this.sheets, this.rows, this.columns, this.stepFrames);
        }
    }

    private final @NotNull DetailSet<SpriteSheet> sheets;
    private final int rowCount;
    private final int columnCount;

    private final int @NotNull [] @Nullable [] columns;
    private final int stepFrames;

    private UnitSprite(@NotNull DetailSet<SpriteSheet> sheets, int rows, @NotNull Map<@NotNull UnitPose, int @NotNull []> columns, int stepFrames) {
        UnitPose[] poses = UnitPose.values();

        this.sheets      = sheets;
        this.rowCount    = rows;
        this.columnCount = sheets.getResource().getFrameCount() / rows;

        this.columns    = new int[poses.length][];
        this.stepFrames = stepFrames;

        for (UnitPose pose : poses) {
            int[] poseColumns = columns.get(pose);

            this.columns[pose.ordinal()] = poseColumns != null ? poseColumns : columns.get(UnitPose.IDLE);
        }

        this.columns[UnitPose.CONSTRUCTING.ordinal()] = columns.get(UnitPose.CONSTRUCTING);
    }

    /**
     * @return the sprite sheet, at its own size
     */
    public @NotNull SpriteSheet getSheet() {
        return this.sheets.getResource();
    }

    /**
     * @return the sprite sheet with its detail sheets
     */
    public @NotNull DetailSet<SpriteSheet> getSheets() {
        return this.sheets;
    }

    /**
     * @return the facing rows of the sheet's grid, {@code 1} for a sheet without facings
     */
    public int getRowCount() {
        return this.rowCount;
    }

    /**
     * @return the frame columns of the sheet's grid
     */
    public int getColumnCount() {
        return this.columnCount;
    }

    /**
     * @return the game loops each frame of a moving, attacking or idle pose shows
     */
    public int getStepFrames() {
        return this.stepFrames;
    }

    /**
     * Finds the pose a unit's picture shows.
     *
     * @param unit the unit's view
     * @return the pose
     */
    public @NotNull UnitPose findPose(@NotNull UnitView unit) {
        if (unit.getConstructionProgress() < UnitView.COMPLETE && this.columns[UnitPose.CONSTRUCTING.ordinal()] != null) {
            return UnitPose.CONSTRUCTING;
        }

        if (unit.isAttacking()) {
            return UnitPose.ATTACKING;
        }

        return unit.isMoving() ? UnitPose.MOVING : UnitPose.IDLE;
    }

    /**
     * Selects the frame of the sheet a unit shows.
     *
     * @param unit            the unit's view
     * @param screenDirection the unit's heading on the screen, in {@value WorldProjection#DIRECTION_COUNT} directions, {@code 0} up and clockwise
     * @param frame           the game loop, the clock of the poses' steps
     * @return the frame index in the sheet
     */
    public int selectFrame(@NotNull UnitView unit, int screenDirection, int frame) {
        UnitPose pose = this.findPose(unit);
        int[] poseColumns = this.columns[pose.ordinal()];
        int step;

        if (pose == UnitPose.CONSTRUCTING) {
            step = Math.min(poseColumns.length - 1, (int) ((long) unit.getConstructionProgress() * poseColumns.length / UnitView.COMPLETE));
        } else {
            step = Math.floorMod(frame / this.stepFrames, poseColumns.length);
        }

        int sector = WorldProjection.DIRECTION_COUNT / this.rowCount;
        int row = Math.floorMod(screenDirection + sector / HALF, WorldProjection.DIRECTION_COUNT) / sector;

        return Math.min(row, this.rowCount - 1) * this.columnCount + poseColumns[step];
    }

    /**
     * Selects the frame of a unit of the sprite's type that stands, for example the ghost of a building before it is placed: the first idle
     * column of the first facing row.
     *
     * @return the frame index in the sheet
     */
    public int selectIdleFrame() {
        return this.columns[UnitPose.IDLE.ordinal()][0];
    }
}
