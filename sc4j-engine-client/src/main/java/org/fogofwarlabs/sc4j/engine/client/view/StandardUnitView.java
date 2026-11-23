package org.fogofwarlabs.sc4j.engine.client.view;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection;
import org.fogofwarlabs.sc4j.engine.combat.CombatSystem;
import org.fogofwarlabs.sc4j.engine.combat.StandardCombatSystem;
import org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceKind;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.OrderType;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.content.type.WeaponType;
import org.fogofwarlabs.sc4j.engine.economy.HarvestSystem;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.math.MutablePoint;
import org.fogofwarlabs.sc4j.engine.movement.MovementSystem;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.ProductionSystem;
import org.fogofwarlabs.sc4j.engine.production.StandardProductionSystem;
import org.fogofwarlabs.sc4j.engine.spatial.UnitBounds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Reads the state of one engine unit for the client: the engine default {@link UnitView}. Position, owner, type, hit points, order, heading and
 * motion come from the engine {@link Unit} fields (the heading converted from the movement system's directions to the world directions), the bounds
 * from the game's {@link UnitBounds}, the full hit points and armour from the engine unit type table, the damage layers from the game content
 * ({@link org.fogofwarlabs.sc4j.engine.content.table.GameContent#getDamageLayers}) and the weapon damage from the weapon type table; hit points and
 * layer points are converted from the combat system's fixed-point unit to whole points, a started point counted as whole. The unit attacks while its
 * order's target unit is an enemy it can attack within range of the game's {@link CombatSystem}; its construction progress comes from the remaining
 * construction time of the {@link StandardProductionSystem} and its type's build time (another production system reports a unit under construction as
 * just placed), its queue from the game's production system and the progress of the queue's front entry, whether it waits for supply, its rally point
 * and whether it blocks a building's placement from the {@link StandardProductionSystem}, its kill count from the {@link StandardCombatSystem}, and
 * the resources it holds or carries from the game's {@link HarvestSystem}. The values are read from the unit at each call, so one view stays current
 * while the game runs.
 */
public final class StandardUnitView implements UnitView {
    private final @NotNull Game game;
    private final @NotNull Unit unit;
    private final @NotNull UnitBounds<Unit> bounds;
    private final @NotNull UnitTypeTable unitTypes;
    private final int fractionBits;
    private final int directionCount;
    private final @NotNull MutablePoint rallyPoint;

    /**
     * Creates a {@link StandardUnitView} instance.
     *
     * @param game         the game of the unit
     * @param unit         the unit
     * @param bounds       the bounds of the game's units
     * @param fractionBits the fraction bits of the unit's hit points and damage layer points (the combat system's fixed-point unit)
     */
    public StandardUnitView(@NotNull Game game, @NotNull Unit unit, @NotNull UnitBounds<Unit> bounds, int fractionBits) {
        this.game           = game;
        this.unit           = unit;
        this.bounds         = bounds;
        this.unitTypes      = game.getContent().getUnitTypes();
        this.fractionBits   = fractionBits;
        this.directionCount = game.hasSystem(MovementSystem.KEY) ? game.getMovement().getDirectionCount() : WorldProjection.DIRECTION_COUNT;
        this.rallyPoint     = new MutablePoint();
    }

    /**
     * @return the unit, the identity commands name
     */
    @Override
    public @NotNull Unit getUnit() {
        return this.unit;
    }

    /**
     * @return the unit's type, or {@code null} for a free pool slot
     */
    @Override
    public @Nullable UnitType getType() {
        return this.unit.getType();
    }

    /**
     * @return the owner, or {@code null} for a free pool slot
     */
    @Override
    public @Nullable Player getOwner() {
        return this.unit.getOwner();
    }

    /**
     * @return the world x of the position
     */
    @Override
    public int getX() {
        return this.unit.getPositionX();
    }

    /**
     * @return the world y of the position
     */
    @Override
    public int getY() {
        return this.unit.getPositionY();
    }

    /**
     * @return the left edge of the bounds
     */
    @Override
    public int getLeft() {
        return this.bounds.getLeft(this.unit);
    }

    /**
     * @return the top edge of the bounds
     */
    @Override
    public int getTop() {
        return this.bounds.getTop(this.unit);
    }

    /**
     * @return the right edge of the bounds, inclusive
     */
    @Override
    public int getRight() {
        return this.bounds.getRight(this.unit);
    }

    /**
     * @return the bottom edge of the bounds, inclusive
     */
    @Override
    public int getBottom() {
        return this.bounds.getBottom(this.unit);
    }

    /**
     * @return {@code true} if the unit is placed on the map, {@code false} otherwise
     */
    @Override
    public boolean isPlaced() {
        return this.bounds.isPlaced(this.unit);
    }

    /**
     * @return the hit points in whole points, a started point counted as whole
     */
    @Override
    public int getHitPoints() {
        return this.toWholePoints(this.unit.getHitPoints());
    }

    /**
     * @return the hit points of the unit's type in its type table, {@code 0} for a free pool slot
     */
    @Override
    public int getMaximumHitPoints() {
        UnitType type = this.unit.getType();

        return type != null ? this.unitTypes.getHitPoints(type) : 0;
    }

    /**
     * Returns the number of damage layers of the unit, for a bar each: the layers of its type in chain order
     * ({@link org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer}).
     *
     * @return the number of damage layers, {@code 0} for a free pool slot
     */
    @Override
    public int getDamageLayerCount() {
        UnitType type = this.unit.getType();

        return type != null ? this.game.getContent().getDamageLayers(type).size() : 0;
    }

    /**
     * Returns the name of a damage layer, which its bar shows.
     *
     * @param layer the index of the layer, {@code 0} up to the layer count excluded
     * @return the name of the layer
     * @throws IndexOutOfBoundsException if the unit has no such layer
     */
    @Override
    public @NotNull String getDamageLayerName(int layer) {
        return this.getDamageLayer(layer).getName();
    }

    /**
     * Returns the points a damage layer holds now.
     *
     * @param layer the index of the layer, {@code 0} up to the layer count excluded
     * @return the points in whole points, a started point counted as whole
     * @throws IndexOutOfBoundsException if the unit has no such layer
     */
    @Override
    public int getDamageLayerPoints(int layer) {
        return this.toWholePoints(this.getDamageLayer(layer).getPoints(this.unit));
    }

    /**
     * Returns the points a damage layer holds when it is full.
     *
     * @param layer the index of the layer, {@code 0} up to the layer count excluded
     * @return the points in whole points, {@code 0} if the unit's type has none of the layer
     * @throws IndexOutOfBoundsException if the unit has no such layer
     */
    @Override
    public int getMaximumDamageLayerPoints(int layer) {
        return this.toWholePoints(this.getDamageLayer(layer).getMaximumPoints(this.unit));
    }

    private @NotNull DamageLayer getDamageLayer(int layer) {
        UnitType type = this.unit.getType();
        List<DamageLayer> layers = type != null ? this.game.getContent().getDamageLayers(type) : List.of();

        if (layer < 0 || layer >= layers.size()) {
            throw Exceptions.newIndexOutOfBoundsException("No damage layer {0}; the unit has {1}.", layer, layers.size());
        }

        return layers.get(layer);
    }

    /**
     * @return the current order type, or {@code null} if the unit has no order
     */
    @Override
    public @Nullable OrderType getOrderType() {
        return this.unit.getOrderType();
    }

    /**
     * Returns the unit the current order aims at, for the selection panel.
     *
     * @return the target unit, or {@code null} if the order has no target unit
     */
    @Override
    public @Nullable Unit getOrderTarget() {
        return this.unit.getOrderTargetUnit();
    }

    /**
     * Returns the direction the unit faces, for its picture.
     *
     * @return the heading in {@value org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection#DIRECTION_COUNT} world directions, {@code 0} up and clockwise
     */
    @Override
    public int getHeading() {
        return WorldProjection.toWorldDirection(this.unit.getHeading(), this.directionCount);
    }

    /**
     * Returns whether the unit moves, for its picture: its current speed is above {@code 0}.
     *
     * @return {@code true} if the unit moves, {@code false} otherwise
     */
    @Override
    public boolean isMoving() {
        return this.unit.getCurrentSpeed() > 0;
    }

    /**
     * Returns whether the unit attacks a target in its range, for its picture: its order's target unit is one it can attack and in range.
     *
     * @return {@code true} if the unit attacks, {@code false} otherwise
     */
    @Override
    public boolean isAttacking() {
        Unit target = this.unit.getOrderTargetUnit();

        if (target == null || target.getType() == null || !this.game.hasSystem(CombatSystem.KEY)) {
            return false;
        }

        CombatSystem combat = this.game.getCombat();

        return combat.canAttack(this.unit, target) && combat.isInRange(this.unit, target);
    }

    /**
     * Returns the unit's armour, for the selection panel.
     *
     * @return the armour of the unit's type in its type table, {@code 0} for a free pool slot
     */
    @Override
    public int getArmor() {
        UnitType type = this.unit.getType();

        return type != null ? this.unitTypes.getArmor(type) : 0;
    }

    /**
     * Returns the damage of one attack of the unit's weapon (its ground weapon, else its air weapon), for the selection panel.
     *
     * @return the damage of the weapon in the weapon type table, {@code 0} for a free pool slot or a type without weapon
     */
    @Override
    public int getWeaponDamage() {
        UnitType type = this.unit.getType();

        if (type == null) {
            return 0;
        }

        WeaponType weapon = this.unitTypes.getGroundWeapon(type);

        if (weapon == null) {
            weapon = this.unitTypes.getAirWeapon(type);
        }

        return weapon != null ? this.game.getContent().getWeaponTypes().getDamage(weapon) : 0;
    }

    /**
     * Returns how far the unit's construction is, for its picture.
     *
     * @return the progress from {@code 0} (just placed) to {@link #COMPLETE}
     */
    @Override
    public int getConstructionProgress() {
        UnitType type = this.unit.getType();

        if (type == null || !this.game.hasSystem(ProductionSystem.KEY) || !this.game.getProduction().isUnderConstruction(this.unit)) {
            return COMPLETE;
        }

        int buildTime = this.unitTypes.getBuildTime(type);

        if (!(this.game.getProduction() instanceof StandardProductionSystem production) || buildTime <= 0) {
            return 0;
        }

        int remaining = Math.min(production.getConstructionRemainingTime(this.unit), buildTime);

        return (int) ((long) (buildTime - remaining) * COMPLETE / buildTime);
    }

    /**
     * Returns how many entries the unit's production queue holds at most, for the queue panel.
     *
     * @return the queue capacity of the {@link StandardProductionSystem}, the queue length under another production system, {@code 0} without one
     */
    @Override
    public int getQueueCapacity() {
        if (!this.game.hasSystem(ProductionSystem.KEY)) {
            return 0;
        }

        return this.game.getProduction() instanceof StandardProductionSystem production ? production.getQueueCapacity() : this.getQueueLength();
    }

    /**
     * Returns how many entries the unit's production queue holds now.
     *
     * @return the queue length, {@code 0} for an empty queue or a unit without owner
     */
    @Override
    public int getQueueLength() {
        if (this.unit.getOwner() == null || !this.game.hasSystem(ProductionSystem.KEY)) {
            return 0;
        }

        return this.game.getProduction().getQueueLength(this.unit);
    }

    /**
     * Returns the unit type of one entry of the unit's production queue, the entry in progress in slot {@code 0}.
     *
     * @param slot the queue slot, below the queue length
     * @return the unit type
     * @throws IndexOutOfBoundsException if the slot is not below the queue length
     */
    @Override
    public @NotNull UnitType getQueuedType(int slot) {
        if (this.getQueueLength() <= slot) {
            return UnitView.super.getQueuedType(slot);
        }

        return this.game.getProduction().getQueuedType(this.unit, slot);
    }

    /**
     * Returns how far the front entry of the unit's production queue is, from the remaining time of the {@link StandardProductionSystem} and
     * the type's build time.
     *
     * @return the progress from {@code 0} (not started) to {@link #COMPLETE}, {@code 0} under another production system
     */
    @Override
    public int getQueueProgress() {
        if (this.getQueueLength() == 0 || !(this.game.getProduction() instanceof StandardProductionSystem production) ||
            !production.isQueueStarted(this.unit)) {
            return 0;
        }

        int buildTime = this.unitTypes.getBuildTime(production.getQueuedType(this.unit, 0));

        if (buildTime <= 0) {
            return COMPLETE;
        }

        int remaining = Math.min(production.getRemainingTime(this.unit), buildTime);

        return (int) ((long) (buildTime - remaining) * COMPLETE / buildTime);
    }

    /**
     * Returns whether the front entry of the unit's production queue waits for its owner's supply, for the queue panel and the HUD's notice:
     * whether the {@link StandardProductionSystem} holds it back for supply ({@link StandardProductionSystem#isWaitingForSupply}).
     *
     * @return {@code true} if the front entry waits for supply, {@code false} otherwise and under another production system
     */
    @Override
    public boolean isWaitingForSupply() {
        return this.game.hasSystem(ProductionSystem.KEY) && this.game.getProduction() instanceof StandardProductionSystem production &&
               production.isWaitingForSupply(this.unit);
    }

    /**
     * Returns whether the unit sends the units it makes to a rally point, for the rally marker.
     *
     * @return {@code true} if the {@link StandardProductionSystem} holds a rally point of the unit, {@code false} otherwise and under another production system
     */
    @Override
    public boolean hasRallyPoint() {
        return this.readRallyPoint();
    }

    /**
     * Returns the world x of the unit's rally point.
     *
     * @return the world x, {@code 0} without a rally point
     */
    @Override
    public int getRallyPointX() {
        return this.readRallyPoint() ? this.rallyPoint.getX() : 0;
    }

    /**
     * Returns the world y of the unit's rally point.
     *
     * @return the world y, {@code 0} without a rally point
     */
    @Override
    public int getRallyPointY() {
        return this.readRallyPoint() ? this.rallyPoint.getY() : 0;
    }

    /**
     * Returns how many units the unit killed, for the selection panel.
     *
     * @return the kill count of the {@link StandardCombatSystem} ({@link StandardCombatSystem#getKillCount}), {@code 0} under another combat system
     */
    @Override
    public int getKillCount() {
        return this.game.hasSystem(CombatSystem.KEY) && this.game.getCombat() instanceof StandardCombatSystem combat
               ? combat.getKillCount(this.unit)
               : 0;
    }

    /**
     * Returns whether the unit keeps a building from being placed over it, for the placement footprint: whether the
     * {@link StandardProductionSystem} counts it as a placement blocker ({@link StandardProductionSystem#isPlacementBlocker}).
     *
     * @return {@code true} if the unit blocks a building's placement, {@code false} otherwise and under another production system
     */
    @Override
    public boolean isPlacementBlocker() {
        return this.game.hasSystem(ProductionSystem.KEY) && this.game.getProduction() instanceof StandardProductionSystem production &&
               production.isPlacementBlocker(this.unit);
    }

    /**
     * Returns the kind of resource a resource container holds, for the selection panel.
     *
     * @return the resource kind, or {@code null} if the unit holds no resources
     */
    @Override
    public @Nullable ResourceKind getResourceKind() {
        HarvestSystem harvest = this.game.findModSystem(HarvestSystem.KEY);

        return harvest != null ? harvest.getResourceKind(this.unit) : null;
    }

    /**
     * Returns the amount a resource container holds, for the selection panel.
     *
     * @return the amount, {@code 0} if the unit holds no resources
     */
    @Override
    public int getResourceAmount() {
        HarvestSystem harvest = this.game.findModSystem(HarvestSystem.KEY);

        return harvest != null ? harvest.getResourceAmount(this.unit) : 0;
    }

    /**
     * Returns the kind of resource a worker carries, for the selection panel.
     *
     * @return the resource kind, or {@code null} if the unit carries nothing
     */
    @Override
    public @Nullable ResourceKind getCarriedKind() {
        HarvestSystem harvest = this.game.findModSystem(HarvestSystem.KEY);

        return harvest != null ? harvest.getCarriedKind(this.unit) : null;
    }

    /**
     * Returns the amount a worker carries, for the selection panel.
     *
     * @return the amount, {@code 0} if the unit carries nothing
     */
    @Override
    public int getCarriedAmount() {
        HarvestSystem harvest = this.game.findModSystem(HarvestSystem.KEY);

        return harvest != null ? harvest.getCarriedAmount(this.unit) : 0;
    }

    private boolean readRallyPoint() {
        return this.game.hasSystem(ProductionSystem.KEY) && this.game.getProduction() instanceof StandardProductionSystem production &&
               production.getRallyPoint(this.unit, this.rallyPoint);
    }

    private int toWholePoints(int points) {
        return (int) (((long) points + (1L << this.fractionBits) - 1) >> this.fractionBits);
    }
}
