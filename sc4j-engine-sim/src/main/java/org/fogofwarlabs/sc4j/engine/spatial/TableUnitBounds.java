package org.fogofwarlabs.sc4j.engine.spatial;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.system.GameSystem;
import org.fogofwarlabs.sc4j.engine.game.system.SystemKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the bounds of a unit from the collision extents of its type in the game's unit type table: the engine default {@link UnitBounds}. The
 * rectangle reaches {@link UnitTypeTable#getCollisionLeft} pixels left of the position, {@link UnitTypeTable#getCollisionUp} up,
 * {@link UnitTypeTable#getCollisionRight} right and {@link UnitTypeTable#getCollisionDown} down, edges inclusive; a unit without type is the
 * single pixel of its position. A unit is placed while it has a type. A mod registers the bounds as a system before the systems that read them,
 * so that they are bound to the game's table when the game is created, and passes the game's instance to those systems.
 * {@snippet :
 * builder.useSystem(TableUnitBounds.KEY, context -> new TableUnitBounds())
 *        .useSystem(SpatialIndex.KEY, context -> new StandardSpatialIndex(context.require(TableUnitBounds.KEY), largestWidth, largestHeight));
 *}
 * <p>
 * Determinism contract: the bounds read the unit and the table only, change nothing and allocate nothing.
 */
public final class TableUnitBounds implements UnitBounds<Unit>, GameSystem {
    /**
     * The key of the table bounds.
     */
    public static final @NotNull SystemKey<TableUnitBounds> KEY = SystemKey.of("unit-bounds", TableUnitBounds.class);

    private @Nullable UnitTypeTable unitTypes;

    /**
     * Creates a {@link TableUnitBounds} instance; it reads no table until it is attached.
     */
    public TableUnitBounds() {
        this.unitTypes = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the unit type table of the game.
     *
     * @param game the game
     */
    @Override
    public void attach(@NotNull Game game) {
        this.unitTypes = game.getContent().getUnitTypes();
    }

    /**
     * @param unit the unit
     * @return the left edge of the unit
     * @throws IllegalStateException if the bounds are not attached
     */
    @Override
    public int getLeft(@NotNull Unit unit) {
        UnitType type = unit.getType();

        return type != null ? unit.getPositionX() - this.getUnitTypes().getCollisionLeft(type) : unit.getPositionX();
    }

    /**
     * @param unit the unit
     * @return the top edge of the unit
     * @throws IllegalStateException if the bounds are not attached
     */
    @Override
    public int getTop(@NotNull Unit unit) {
        UnitType type = unit.getType();

        return type != null ? unit.getPositionY() - this.getUnitTypes().getCollisionUp(type) : unit.getPositionY();
    }

    /**
     * @param unit the unit
     * @return the right edge of the unit, inclusive
     * @throws IllegalStateException if the bounds are not attached
     */
    @Override
    public int getRight(@NotNull Unit unit) {
        UnitType type = unit.getType();

        return type != null ? unit.getPositionX() + this.getUnitTypes().getCollisionRight(type) : unit.getPositionX();
    }

    /**
     * @param unit the unit
     * @return the bottom edge of the unit, inclusive
     * @throws IllegalStateException if the bounds are not attached
     */
    @Override
    public int getBottom(@NotNull Unit unit) {
        UnitType type = unit.getType();

        return type != null ? unit.getPositionY() + this.getUnitTypes().getCollisionDown(type) : unit.getPositionY();
    }

    /**
     * @param unit the unit
     * @return the width of the unit's bounds in pixels
     * @throws IllegalStateException if the bounds are not attached
     */
    @Override
    public int getWidth(@NotNull Unit unit) {
        return this.getRight(unit) - this.getLeft(unit) + 1;
    }

    /**
     * @param unit the unit
     * @return the height of the unit's bounds in pixels
     * @throws IllegalStateException if the bounds are not attached
     */
    @Override
    public int getHeight(@NotNull Unit unit) {
        return this.getBottom(unit) - this.getTop(unit) + 1;
    }

    /**
     * @param unit the unit
     * @return the x coordinate of the point a nearest search measures the unit from
     */
    @Override
    public int getCenterX(@NotNull Unit unit) {
        return unit.getPositionX();
    }

    /**
     * @param unit the unit
     * @return the y coordinate of the point a nearest search measures the unit from
     */
    @Override
    public int getCenterY(@NotNull Unit unit) {
        return unit.getPositionY();
    }

    /**
     * Checks whether a unit is placed in the world. A search around a placed unit starts at its own edges, around a hidden one at its center point.
     *
     * @param unit the unit
     * @return {@code true} if the unit is placed in the world, {@code false} if it is hidden
     */
    @Override
    public boolean isPlaced(@NotNull Unit unit) {
        return unit.getType() != null;
    }

    private @NotNull UnitTypeTable getUnitTypes() {
        UnitTypeTable table = this.unitTypes;

        if (table == null) {
            throw Exceptions.newIllegalStateException("The table unit bounds are not attached.");
        }

        return table;
    }
}
