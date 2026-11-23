package org.fogofwarlabs.sc4j.engine.client.world;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.display.DetailSet;
import org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Binds a projectile's motion profile to the frames of its sprite sheet and to the effect its impact shows: the sheet is a grid of facing rows
 * (row {@code r} of {@code n} shows the screen direction {@code r * 256 / n}, row {@code 0} up and clockwise) whose columns the flight steps
 * through every {@link #getStepFrames()} game loops; the impact sheet's frames play once, one every {@link #getImpactStepFrames()} game loops,
 * where the flight ended. The sheet's anchor is the projectile's ground position. Either sheet may have detail sheets ({@link DetailSet}),
 * drawn like those of a {@link UnitSprite}.
 */
public final class ProjectileSprite {
    private static final int HALF = 2;

    private final @NotNull DetailSet<SpriteSheet> sheets;
    private final int rowCount;
    private final int columnCount;
    private final int stepFrames;

    private final @Nullable DetailSet<SpriteSheet> impactSheets;
    private final int impactStepFrames;

    /**
     * Creates a {@link ProjectileSprite} instance.
     *
     * @param sheets           the sheet of the flight with its detail sheets
     * @param rows             the facing rows of the sheet
     * @param stepFrames       the game loops each column of the flight shows, at least {@code 1}
     * @param impactSheets     the sheet of the impact effect with its detail sheets, or {@code null} for none
     * @param impactStepFrames the game loops each frame of the impact shows, at least {@code 1}
     * @throws IllegalArgumentException if the rows do not divide the sheet's frames or a step is below {@code 1}
     */
    public ProjectileSprite(@NotNull DetailSet<SpriteSheet> sheets, int rows, int stepFrames,
                            @Nullable DetailSet<SpriteSheet> impactSheets, int impactStepFrames) {
        SpriteSheet sheet = sheets.getResource();

        if (rows < 1 || sheet.getFrameCount() % rows != 0 || sheet.getFrameCount() == 0 || stepFrames < 1 || impactStepFrames < 1) {
            throw Exceptions.newIllegalArgumentException("Invalid projectile sprite: {0} frames in {1} rows, steps {2} and {3}",
                                                         sheet.getFrameCount(), rows, stepFrames, impactStepFrames);
        }

        this.sheets      = sheets;
        this.rowCount    = rows;
        this.columnCount = sheet.getFrameCount() / rows;
        this.stepFrames  = stepFrames;

        this.impactSheets     = impactSheets;
        this.impactStepFrames = impactStepFrames;
    }

    /**
     * @return the sheet of the flight, at its own size
     */
    public @NotNull SpriteSheet getSheet() {
        return this.sheets.getResource();
    }

    /**
     * @return the sheet of the flight with its detail sheets
     */
    public @NotNull DetailSet<SpriteSheet> getSheets() {
        return this.sheets;
    }

    /**
     * @return the game loops each column of the flight shows
     */
    public int getStepFrames() {
        return this.stepFrames;
    }

    /**
     * @return the sheet of the impact effect at its own size, or {@code null} for none
     */
    public @Nullable SpriteSheet getImpactSheet() {
        DetailSet<SpriteSheet> impact = this.impactSheets;

        return impact != null ? impact.getResource() : null;
    }

    /**
     * @return the sheet of the impact effect with its detail sheets, or {@code null} for none
     */
    public @Nullable DetailSet<SpriteSheet> getImpactSheets() {
        return this.impactSheets;
    }

    /**
     * @return the game loops each frame of the impact shows
     */
    public int getImpactStepFrames() {
        return this.impactStepFrames;
    }


    /**
     * Selects the frame of the flight's sheet.
     *
     * @param screenDirection the projectile's heading on the screen, in {@value WorldProjection#DIRECTION_COUNT} directions, {@code 0} up and clockwise
     * @param frame           the game loop, the clock of the columns
     * @return the frame index in the sheet
     */
    public int selectFrame(int screenDirection, int frame) {
        int sector = WorldProjection.DIRECTION_COUNT / this.rowCount;
        int row = Math.floorMod(screenDirection + sector / HALF, WorldProjection.DIRECTION_COUNT) / sector;
        int column = Math.floorMod(frame / this.stepFrames, this.columnCount);

        return Math.min(row, this.rowCount - 1) * this.columnCount + column;
    }

    /**
     * Selects the frame of the impact effect.
     *
     * @param elapsedFrames the game loops since the flight ended
     * @return the frame index in the impact sheet, or {@code -1} once the effect played or without impact sheet
     */
    public int selectImpactFrame(int elapsedFrames) {
        SpriteSheet impact = this.getImpactSheet();
        int frameIndex = elapsedFrames / this.impactStepFrames;

        return impact == null || elapsedFrames < 0 || frameIndex >= impact.getFrameCount() ? -1 : frameIndex;
    }
}
