package org.fogofwarlabs.sc4j.engine.client.view;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceKind;
import org.fogofwarlabs.sc4j.engine.content.type.OrderType;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the state of one unit for the client, without a way to change it: type, owner, position, bounds, hit points and damage layers in whole
 * points, the current order and its target, what its picture shows (heading, motion, attack, construction), its production queue and the
 * resources it holds or carries. A view reads the unit at each call, so it stays current while the game runs. The engine default is
 * {@link StandardUnitView}; a mod whose units keep this state in its own tables implements its own view.
 */
public interface UnitView {
    /**
     * The construction progress of a complete unit.
     */
    int COMPLETE = 1000;

    /**
     * @return the unit, the identity commands name
     */
    @NotNull Unit getUnit();

    /**
     * @return the unit's type, or {@code null} for a free pool slot
     */
    @Nullable UnitType getType();

    /**
     * @return the owner, or {@code null} for a free pool slot
     */
    @Nullable Player getOwner();

    /**
     * @return the world x of the position
     */
    int getX();

    /**
     * @return the world y of the position
     */
    int getY();

    /**
     * @return the left edge of the bounds
     */
    int getLeft();

    /**
     * @return the top edge of the bounds
     */
    int getTop();

    /**
     * @return the right edge of the bounds, inclusive
     */
    int getRight();

    /**
     * @return the bottom edge of the bounds, inclusive
     */
    int getBottom();

    /**
     * @return {@code true} if the unit is placed on the map, {@code false} otherwise
     */
    boolean isPlaced();

    /**
     * @return the hit points in whole points, a started point counted as whole
     */
    int getHitPoints();

    /**
     * @return the hit points of the unit's type in its type table, {@code 0} for a free pool slot
     */
    int getMaximumHitPoints();

    /**
     * Returns the number of damage layers of the unit, for a bar each: the layers of its type in chain order
     * ({@link org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer}). The default has none.
     *
     * @return the number of damage layers, {@code 0} for a free pool slot
     */
    default int getDamageLayerCount() {
        return 0;
    }

    /**
     * Returns the name of a damage layer, which its bar shows.
     *
     * @param layer the index of the layer, {@code 0} up to the layer count excluded
     * @return the name of the layer
     * @throws IndexOutOfBoundsException if the unit has no such layer
     */
    default @NotNull String getDamageLayerName(int layer) {
        throw Exceptions.newIndexOutOfBoundsException("No damage layer {0}; the unit has none.", layer);
    }

    /**
     * Returns the points a damage layer holds now.
     *
     * @param layer the index of the layer, {@code 0} up to the layer count excluded
     * @return the points in whole points
     * @throws IndexOutOfBoundsException if the unit has no such layer
     */
    default int getDamageLayerPoints(int layer) {
        throw Exceptions.newIndexOutOfBoundsException("No damage layer {0}; the unit has none.", layer);
    }

    /**
     * Returns the points a damage layer holds when it is full.
     *
     * @param layer the index of the layer, {@code 0} up to the layer count excluded
     * @return the points in whole points, {@code 0} if the unit's type has none of the layer
     * @throws IndexOutOfBoundsException if the unit has no such layer
     */
    default int getMaximumDamageLayerPoints(int layer) {
        throw Exceptions.newIndexOutOfBoundsException("No damage layer {0}; the unit has none.", layer);
    }

    /**
     * @return the current order type, or {@code null} if the unit has no order
     */
    @Nullable OrderType getOrderType();

    /**
     * Returns the unit the current order aims at, for the selection panel. The default aims at none.
     *
     * @return the target unit, or {@code null} if the order has no target unit
     */
    default @Nullable Unit getOrderTarget() {
        return null;
    }

    /**
     * Returns the direction the unit faces, for its picture. The default faces up.
     *
     * @return the heading in {@value org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection#DIRECTION_COUNT} world directions, {@code 0} up and clockwise
     */
    default int getHeading() {
        return 0;
    }

    /**
     * Returns whether the unit moves, for its picture. The default does not move.
     *
     * @return {@code true} if the unit moves, {@code false} otherwise
     */
    default boolean isMoving() {
        return false;
    }

    /**
     * Returns whether the unit attacks a target in its range, for its picture. The default does not attack.
     *
     * @return {@code true} if the unit attacks, {@code false} otherwise
     */
    default boolean isAttacking() {
        return false;
    }

    /**
     * Returns the unit's armour, for the selection panel. The default has none.
     *
     * @return the armour, {@code 0} without armour
     */
    default int getArmor() {
        return 0;
    }

    /**
     * Returns the damage of one attack of the unit's weapon (its ground weapon, else its air weapon), for the selection panel. The default has
     * no weapon.
     *
     * @return the damage, {@code 0} without weapon
     */
    default int getWeaponDamage() {
        return 0;
    }

    /**
     * Returns how far the unit's construction is, for its picture. The default is complete.
     *
     * @return the progress from {@code 0} (just placed) to {@link #COMPLETE}
     */
    default int getConstructionProgress() {
        return COMPLETE;
    }

    /**
     * Returns how many entries the unit's production queue holds at most, for the queue panel. The default has no queue.
     *
     * @return the queue capacity, {@code 0} for a unit without queue
     */
    default int getQueueCapacity() {
        return 0;
    }

    /**
     * Returns how many entries the unit's production queue holds now. The default has no queue.
     *
     * @return the queue length, {@code 0} for an empty queue
     */
    default int getQueueLength() {
        return 0;
    }

    /**
     * Returns the unit type of one entry of the unit's production queue, the entry in progress in slot {@code 0}. The default has no queue.
     *
     * @param slot the queue slot, below the queue length
     * @return the unit type
     * @throws IndexOutOfBoundsException if the slot is not below the queue length
     */
    default @NotNull UnitType getQueuedType(int slot) {
        throw Exceptions.newIndexOutOfBoundsException("No queue entry {0}; the unit has no queue.", slot);
    }

    /**
     * Returns how far the front entry of the unit's production queue is. The default has no queue.
     *
     * @return the progress from {@code 0} (not started) to {@link #COMPLETE}
     */
    default int getQueueProgress() {
        return 0;
    }

    /**
     * Returns whether the front entry of the unit's production queue waits for its owner's supply, for the queue panel and the HUD's notice.
     * The default has no queue.
     *
     * @return {@code true} if the front entry waits for supply, {@code false} otherwise
     */
    default boolean isWaitingForSupply() {
        return false;
    }

    /**
     * Returns whether the unit sends the units it makes to a rally point, for the rally marker. The default has none.
     *
     * @return {@code true} if the unit has a rally point, {@code false} otherwise
     */
    default boolean hasRallyPoint() {
        return false;
    }

    /**
     * Returns the world x of the unit's rally point. The default has none.
     *
     * @return the world x, {@code 0} without a rally point
     */
    default int getRallyPointX() {
        return 0;
    }

    /**
     * Returns the world y of the unit's rally point. The default has none.
     *
     * @return the world y, {@code 0} without a rally point
     */
    default int getRallyPointY() {
        return 0;
    }

    /**
     * Returns how many units the unit killed, for the selection panel. The default counts none.
     *
     * @return the kill count, {@code 0} when the simulation counts no kills
     */
    default int getKillCount() {
        return 0;
    }

    /**
     * Returns the kind of resource a resource container holds, for the selection panel. The default holds none.
     *
     * @return the resource kind, or {@code null} if the unit holds no resources
     */
    default @Nullable ResourceKind getResourceKind() {
        return null;
    }

    /**
     * Returns the amount a resource container holds, for the selection panel. The default holds none.
     *
     * @return the amount, {@code 0} if the unit holds no resources
     */
    default int getResourceAmount() {
        return 0;
    }

    /**
     * Returns the kind of resource a worker carries, for the selection panel. The default carries none.
     *
     * @return the resource kind, or {@code null} if the unit carries nothing
     */
    default @Nullable ResourceKind getCarriedKind() {
        return null;
    }

    /**
     * Returns the amount a worker carries, for the selection panel. The default carries none.
     *
     * @return the amount, {@code 0} if the unit carries nothing
     */
    default int getCarriedAmount() {
        return 0;
    }

    /**
     * Returns whether the unit keeps a building from being placed over it, for the placement footprint. The default blocks nothing.
     *
     * @return {@code true} if the unit blocks a building's placement, {@code false} otherwise
     */
    default boolean isPlacementBlocker() {
        return false;
    }
}
