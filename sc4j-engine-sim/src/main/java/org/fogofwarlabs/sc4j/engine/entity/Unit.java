package org.fogofwarlabs.sc4j.engine.entity;

import org.fogofwarlabs.sc4j.engine.content.registry.ContentDefinition;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.content.type.OrderType;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Holds the engine fields of a unit: its position and the motion state of the motion model ({@link MovingEntity}), its owner, its type, its place
 * in the sorted-edge spatial index ({@link org.fogofwarlabs.sc4j.engine.spatial.SortedEdgeIndex}), whether pushing moves it, the hit points the
 * combat system ({@link org.fogofwarlabs.sc4j.engine.combat.CombatSystem}) reduces, and the order state of the order system
 * ({@link org.fogofwarlabs.sc4j.engine.order.OrderSystem}): the current order type, its target point and target unit, and the ends of the
 * queue of the orders that follow it ({@link QueuedOrder}).
 * <p>
 * Engine systems add the fields they own here; everything else a mod needs per unit (such as the points of its damage layers,
 * {@link org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer}) is an extension column of the unit pool. A mod that pools its units
 * itself extends this class, so the engine systems and hooks take its units as engine units.
 */
public class Unit extends MovingEntity {
    private @Nullable Player owner;
    private @Nullable UnitType type;

    private int minimumHorizontalEdgeIndex;
    private int maximumHorizontalEdgeIndex;
    private int minimumVerticalEdgeIndex;
    private int maximumVerticalEdgeIndex;

    private boolean immovable;

    private int hitPoints;

    private @Nullable OrderType orderType;
    private int orderTargetX;
    private int orderTargetY;
    private @Nullable Unit orderTargetUnit;
    private @Nullable QueuedOrder firstOrder;
    private @Nullable QueuedOrder lastOrder;

    /**
     * Creates a {@link Unit} instance for one pool slot, without owner and type at {@code (0, 0)}, with every edge index at {@code 0}
     * and the motion and order fields at their initial values ({@link #resetMotion}, {@link #resetOrders}), with the hit points {@code 0}.
     * <p>
     * The spatial index takes only a negative edge index as not indexed, so a unit of a slot that the index never held is inserted before the
     * index moves or removes it.
     *
     * @param index the slot index, {@code 1} up to the pool capacity
     */
    public Unit(int index) {
        super(index);

        this.owner = null;
        this.type  = null;

        this.minimumHorizontalEdgeIndex = 0;
        this.maximumHorizontalEdgeIndex = 0;
        this.minimumVerticalEdgeIndex   = 0;
        this.maximumVerticalEdgeIndex   = 0;

        this.immovable = false;

        this.hitPoints = 0;

        this.orderType       = null;
        this.orderTargetX    = 0;
        this.orderTargetY    = 0;
        this.orderTargetUnit = null;
        this.firstOrder      = null;
        this.lastOrder       = null;
    }

    /**
     * @return the owner, or {@code null} if no player owns the unit
     */
    public @Nullable Player getOwner() {
        return this.owner;
    }

    /**
     * Sets the owner.
     *
     * @param owner the owner, or {@code null} if no player owns the unit
     */
    public void setOwner(@Nullable Player owner) {
        this.owner = owner;
    }

    /**
     * @return the unit type, or {@code null} before one is set
     */
    public @Nullable UnitType getType() {
        return this.type;
    }

    /**
     * Sets the unit type.
     *
     * @param type the unit type, registered in the game content, or {@code null} for none
     */
    public void setType(@Nullable UnitType type) {
        this.type = type;
    }

    /**
     * @return the index of this unit's minimum edge in the horizontal edge list, {@code -1} after the spatial index removed the unit, {@code 0} in a slot it never held
     */
    public int getMinimumHorizontalEdgeIndex() {
        return this.minimumHorizontalEdgeIndex;
    }

    /**
     * Sets the index of this unit's minimum edge in the horizontal edge list.
     *
     * @param minimumHorizontalEdgeIndex the edge index
     */
    public void setMinimumHorizontalEdgeIndex(int minimumHorizontalEdgeIndex) {
        this.minimumHorizontalEdgeIndex = minimumHorizontalEdgeIndex;
    }

    /**
     * @return the index of this unit's maximum edge in the horizontal edge list, {@code -1} after the spatial index removed the unit, {@code 0} in a slot it never held
     */
    public int getMaximumHorizontalEdgeIndex() {
        return this.maximumHorizontalEdgeIndex;
    }

    /**
     * Sets the index of this unit's maximum edge in the horizontal edge list.
     *
     * @param maximumHorizontalEdgeIndex the edge index
     */
    public void setMaximumHorizontalEdgeIndex(int maximumHorizontalEdgeIndex) {
        this.maximumHorizontalEdgeIndex = maximumHorizontalEdgeIndex;
    }

    /**
     * @return the index of this unit's minimum edge in the vertical edge list, {@code -1} after the spatial index removed the unit, {@code 0} in a slot it never held
     */
    public int getMinimumVerticalEdgeIndex() {
        return this.minimumVerticalEdgeIndex;
    }

    /**
     * Sets the index of this unit's minimum edge in the vertical edge list.
     *
     * @param minimumVerticalEdgeIndex the edge index
     */
    public void setMinimumVerticalEdgeIndex(int minimumVerticalEdgeIndex) {
        this.minimumVerticalEdgeIndex = minimumVerticalEdgeIndex;
    }

    /**
     * @return the index of this unit's maximum edge in the vertical edge list, {@code -1} after the spatial index removed the unit, {@code 0} in a slot it never held
     */
    public int getMaximumVerticalEdgeIndex() {
        return this.maximumVerticalEdgeIndex;
    }

    /**
     * Sets the index of this unit's maximum edge in the vertical edge list.
     *
     * @param maximumVerticalEdgeIndex the edge index
     */
    public void setMaximumVerticalEdgeIndex(int maximumVerticalEdgeIndex) {
        this.maximumVerticalEdgeIndex = maximumVerticalEdgeIndex;
    }

    /**
     * @param horizontal {@code true} for the horizontal edge list of the spatial index, {@code false} for the vertical one
     * @return the index of this unit's minimum edge in that list
     */
    public int getMinimumEdgeIndex(boolean horizontal) {
        return horizontal ? this.minimumHorizontalEdgeIndex : this.minimumVerticalEdgeIndex;
    }

    /**
     * @param horizontal {@code true} for the horizontal edge list of the spatial index, {@code false} for the vertical one
     * @return the index of this unit's maximum edge in that list
     */
    public int getMaximumEdgeIndex(boolean horizontal) {
        return horizontal ? this.maximumHorizontalEdgeIndex : this.maximumVerticalEdgeIndex;
    }

    /**
     * @return {@code true} if pushing does not move the unit until it moves again by itself, {@code false} otherwise
     */
    public boolean isImmovable() {
        return this.immovable;
    }

    /**
     * Sets whether pushing moves the unit.
     *
     * @param immovable whether pushing does not move the unit until it moves again by itself
     */
    public void setImmovable(boolean immovable) {
        this.immovable = immovable;
    }

    /**
     * @return the hit points in the combat system's fixed-point unit; the standard combat system keeps eight fraction bits
     */
    public int getHitPoints() {
        return this.hitPoints;
    }

    /**
     * Sets the hit points, keeping all {@code 32} bits.
     *
     * @param hitPoints the hit points in the combat system's fixed-point unit
     */
    public void setHitPoints(int hitPoints) {
        this.hitPoints = hitPoints;
    }

    /**
     * @return the current order type, or {@code null} if the unit has no order or its order names no order type of the content
     */
    public @Nullable OrderType getOrderType() {
        return this.orderType;
    }

    /**
     * Sets the current order type.
     *
     * @param orderType the order type, or {@code null} if the unit has no order or its order names no order type of the content
     */
    public void setOrderType(@Nullable OrderType orderType) {
        this.orderType = orderType;
    }

    /**
     * @return the horizontal map position of the order target
     */
    public int getOrderTargetX() {
        return this.orderTargetX;
    }

    /**
     * Sets the horizontal map position of the order target.
     *
     * @param orderTargetX the position
     */
    public void setOrderTargetX(int orderTargetX) {
        this.orderTargetX = orderTargetX;
    }

    /**
     * @return the vertical map position of the order target
     */
    public int getOrderTargetY() {
        return this.orderTargetY;
    }

    /**
     * Sets the vertical map position of the order target.
     *
     * @param orderTargetY the position
     */
    public void setOrderTargetY(int orderTargetY) {
        this.orderTargetY = orderTargetY;
    }

    /**
     * @return {@code true} if the current order has a target unit, {@code false} otherwise
     */
    public boolean hasOrderTarget() {
        return this.orderTargetUnit != null;
    }

    /**
     * @return the target unit of the current order, or {@code null} for a point target
     */
    public @Nullable Unit getOrderTargetUnit() {
        return this.orderTargetUnit;
    }

    /**
     * Sets the target unit of the current order.
     *
     * @param orderTargetUnit the target unit, or {@code null} for a point target
     */
    public void setOrderTargetUnit(@Nullable Unit orderTargetUnit) {
        this.orderTargetUnit = orderTargetUnit;
    }

    /**
     * @return the first order of the queue, the one that follows the current order, or {@code null} if the queue is empty
     */
    public @Nullable QueuedOrder getFirstOrder() {
        return this.firstOrder;
    }

    /**
     * Sets the first order of the queue.
     *
     * @param firstOrder the first queued order, or {@code null} if the queue is empty
     */
    public void setFirstOrder(@Nullable QueuedOrder firstOrder) {
        this.firstOrder = firstOrder;
    }

    /**
     * @return the last order of the queue, or {@code null} if the queue is empty
     */
    public @Nullable QueuedOrder getLastOrder() {
        return this.lastOrder;
    }

    /**
     * Sets the last order of the queue.
     *
     * @param lastOrder the last queued order, or {@code null} if the queue is empty
     */
    public void setLastOrder(@Nullable QueuedOrder lastOrder) {
        this.lastOrder = lastOrder;
    }

    /**
     * Resets every motion field to its initial value: no motion profile, every direction, speed, flag and coordinate {@code 0}, the position stays.
     * <p>
     * A unit also becomes movable again ({@link #isImmovable}).
     */
    @Override
    public void resetMotion() {
        super.resetMotion();

        this.immovable = false;
    }

    /**
     * Resets every order field to its initial value: no order type, target point {@code (0, 0)}, no target unit and an empty queue. The
     * queued orders are not returned to their pool; the order system that owns them does that first.
     */
    public void resetOrders() {
        this.orderType       = null;
        this.orderTargetX    = 0;
        this.orderTargetY    = 0;
        this.orderTargetUnit = null;
        this.firstOrder      = null;
        this.lastOrder       = null;
    }


    /**
     * Mixes this part of the simulation state into the hash: the owner, the type index, the position, the motion fields, the immovable flag and
     * the hit points in declaration order, then the order type index, the order target point, the slot index of the order
     * target unit ({@code 0} for none) and each queued order in queue order (its type index, target point and target unit slot index).
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        hash.mix(this.owner != null ? this.owner.getIndex() : -1);
        hash.mix(ContentType.indexOf(this.type));
        this.contributeMotionStateHash(hash);
        hash.mixBoolean(this.immovable);
        hash.mix(this.hitPoints);
        hash.mix(ContentType.indexOf(this.orderType));
        hash.mix(this.orderTargetX);
        hash.mix(this.orderTargetY);
        hash.mix(this.orderTargetUnit != null ? this.orderTargetUnit.getIndex() : 0);

        for (QueuedOrder order = this.firstOrder; order != null; order = order.getNext()) {
            Unit targetUnit = order.getTargetUnit();

            hash.mix(ContentType.indexOf(order.getType()));
            hash.mix(order.getTargetX());
            hash.mix(order.getTargetY());
            hash.mix(targetUnit != null ? targetUnit.getIndex() : 0);
        }
    }

    /**
     * Writes this part of the simulation state: the owner, the type index, the position and the motion fields, the four edge indices of the
     * spatial index, the immovable flag, the hit points, the order type index, the order target point and the slot index
     * of the order target unit. The order queue is written by the order system that pools the queued orders.
     *
     * @param writer the writer of the snapshot
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        writer.writePlayer(this.owner);
        writer.writeType(this.type);
        this.writeMotionSnapshot(writer);
        writer.writeInt(this.minimumHorizontalEdgeIndex);
        writer.writeInt(this.maximumHorizontalEdgeIndex);
        writer.writeInt(this.minimumVerticalEdgeIndex);
        writer.writeInt(this.maximumVerticalEdgeIndex);
        writer.writeBoolean(this.immovable);
        writer.writeInt(this.hitPoints);
        writer.writeType(this.orderType);
        writer.writeInt(this.orderTargetX);
        writer.writeInt(this.orderTargetY);
        writer.writeEntity(this.orderTargetUnit);
    }

    /**
     * Reads this part of the simulation state back, replacing the current values; the order target unit is a unit of the game's unit pool. The
     * order queue is read by the order system that pools the queued orders.
     *
     * @param reader the reader of the snapshot
     * @throws IOException if the snapshot ends early or names no player, type or unit of the game
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        ContentDefinition definition = reader.getGame().getContent().getDefinition();

        this.owner = reader.readPlayer();
        this.type  = reader.readType(definition.getUnitTypes().getTypes());
        this.readMotionSnapshot(reader);

        this.minimumHorizontalEdgeIndex = reader.readInt();
        this.maximumHorizontalEdgeIndex = reader.readInt();
        this.minimumVerticalEdgeIndex   = reader.readInt();
        this.maximumVerticalEdgeIndex   = reader.readInt();
        this.immovable                  = reader.readBoolean();
        this.hitPoints                  = reader.readInt();
        this.orderType                  = reader.readType(definition.getOrderTypes().getTypes());
        this.orderTargetX               = reader.readInt();
        this.orderTargetY               = reader.readInt();
        this.orderTargetUnit            = reader.readEntity(reader.getGame().getUnits());
    }
}
