package org.fogofwarlabs.sc4j.engine.order;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.OrderType;
import org.fogofwarlabs.sc4j.engine.content.type.TypeRegistry;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.QueuedOrder;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.game.ControllerKind;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.PlayerSlot;
import org.fogofwarlabs.sc4j.engine.game.frame.FramePhase;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Keeps the orders of units on the engine {@link Unit} order fields and runs their {@link OrderBehavior}s: the engine default
 * {@link OrderSystem}. A mod registers it under {@link OrderSystem#KEY} with the behaviors of its order types, and either calls {@link #update}
 * for each unit from its own unit phase or lists the system as the order phase ({@link #run}).
 * {@snippet :
 * builder.useSystem(OrderSystem.KEY, context -> new StandardOrderSystem((game, behaviors) -> behaviors.register(move, moveBehavior)))
 *        .addFramePhase(OrderSystem.KEY, orders -> (StandardOrderSystem) orders);
 *}
 * <p>
 * Issuing an order empties the unit's queue and makes the order current. Queuing appends an order taken from the system's pool of queued
 * orders, whose size is the order limit of the game ({@link org.fogofwarlabs.sc4j.engine.game.EngineLimits#getOrders()}). Finishing the
 * current order makes the first queued order current, or, with an empty queue, the idle order of the unit's type for the controller kind of
 * its owner ({@link UnitTypeTable#getIdleOrder(UnitType, ControllerKind)}), targeted at the unit's position. A unit without owner, or
 * whose owner has no player slot in the setup, idles as a {@link ControllerKind#NEUTRAL} unit. Whenever issuing or finishing made an order
 * current, the system starts it ({@link OrderBehavior#start}). The update runs the behavior of the current order type; a unit without order
 * type, or whose type has no behavior, does nothing. The standard order system runs no animation program
 * itself; the instruction limit reaches the behaviors through their context.
 * <p>
 * As a phase, the system updates every allocated unit of the pool in ascending slot order, with the phase instruction limit of the constructor; a
 * unit freed during the phase before its turn is skipped, a unit allocated during the phase in a later slot is updated in it.
 * <p>
 * Determinism contract: the operations change only the named unit's order fields and the pool's free list, in call order; they draw no random
 * value and allocate nothing. A freed queued order is reused first. The phase visits the slots in ascending order. The order state is part of
 * the unit hash; the system writes the queues of the state snapshot.
 */
public final class StandardOrderSystem implements OrderSystem, FramePhase, SnapshotContributor {
    /**
     * Carries the game and the instruction limit of one update to a behavior.
     */
    private static final class Context implements OrderContext {
        private final @NotNull Game game;

        private int instructionLimit;

        private Context(@NotNull Game game) {
            this.game = game;

            this.instructionLimit = 0;
        }

        /**
         * @return the game whose order system runs the behavior
         */
        @Override
        public @NotNull Game getGame() {
            return this.game;
        }

        /**
         * @return the instruction limit of each animation program run the behavior starts, which it also passes to the order system when it changes an order
         */
        @Override
        public int getInstructionLimit() {
            return this.instructionLimit;
        }
    }

    private static <T> @NotNull T requireAttached(@Nullable T value) {
        if (value == null) {
            throw Exceptions.newIllegalStateException("The standard order system is not attached.");
        }

        return value;
    }

    private final @NotNull BiConsumer<@NotNull Game, @NotNull OrderRegistry<OrderBehavior<OrderContext, Unit>>> registration;
    private final int phaseInstructionLimit;

    private @Nullable EntityPool<Unit> units;
    private @Nullable OrderRegistry<OrderBehavior<OrderContext, Unit>> behaviors;
    private @Nullable Context context;
    private @Nullable UnitTypeTable unitTypes;
    private @NotNull ControllerKind @NotNull [] controllerKinds;
    private @Nullable QueuedOrder firstFreeOrder;
    private int freeOrderCount;

    /**
     * Creates a {@link StandardOrderSystem} instance whose phase runs the orders with the instruction limit {@code 0}; it holds no behavior and
     * no queued order until it is attached.
     *
     * @param registration registers the behavior of each order type that has one when the system is attached ({@link #attach})
     */
    public StandardOrderSystem(@NotNull BiConsumer<@NotNull Game, @NotNull OrderRegistry<OrderBehavior<OrderContext, Unit>>> registration) {
        this(registration, 0);
    }

    /**
     * Creates a {@link StandardOrderSystem} instance; it holds no behavior and no queued order until it is attached.
     *
     * @param registration          registers the behavior of each order type that has one when the system is attached ({@link #attach})
     * @param phaseInstructionLimit the instruction limit of each animation program run an order of the phase starts
     */
    public StandardOrderSystem(@NotNull BiConsumer<@NotNull Game, @NotNull OrderRegistry<OrderBehavior<OrderContext, Unit>>> registration,
                               int phaseInstructionLimit) {
        this.registration          = registration;
        this.phaseInstructionLimit = phaseInstructionLimit;

        this.units           = null;
        this.behaviors       = null;
        this.context         = null;
        this.unitTypes       = null;
        this.controllerKinds = new ControllerKind[0];
        this.firstFreeOrder  = null;
        this.freeOrderCount  = 0;
    }

    /**
     * @return the frozen registry of the behaviors, over the order types of the game content
     * @throws IllegalStateException if the system is not attached
     */
    public @NotNull OrderRegistry<OrderBehavior<OrderContext, Unit>> getBehaviors() {
        return requireAttached(this.behaviors);
    }

    /**
     * @return the number of queued orders the pool can still hand out
     */
    public int getFreeOrderCount() {
        return this.freeOrderCount;
    }

    /**
     * Returns the order a unit idles with: the idle order of its type for the controller kind of its owner.
     *
     * @param unit the unit
     * @return the idle order type, or {@code null} if the unit has no type or its type has no idle order for that kind
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable OrderType getIdleOrder(@NotNull Unit unit) {
        UnitTypeTable attachedUnitTypes = requireAttached(this.unitTypes);
        UnitType type = unit.getType();

        return type != null ? attachedUnitTypes.getIdleOrder(type, this.getControllerKind(unit.getOwner())) : null;
    }


    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Creates the behavior registry over the order types of the content, calls the registration once with the game (whose hooks and systems a
     * behavior may keep) and freezes the registry; binds the unit type table, reads the controller kind of each player slot of the setup and
     * fills the pool with the game's order limit of queued orders.
     *
     * @param game the game
     */
    @Override
    public void attach(@NotNull Game game) {
        List<OrderType> orderTypes = game.getContent().getDefinition().getOrderTypes().getTypes().getTypes();
        OrderRegistry<OrderBehavior<OrderContext, Unit>> registry = new OrderRegistry<>(orderTypes);

        this.registration.accept(game, registry);
        registry.freeze();

        this.units           = game.getUnits();
        this.behaviors       = registry;
        this.context         = new Context(game);
        this.unitTypes       = game.getContent().getUnitTypes();
        this.controllerKinds = new ControllerKind[game.getPlayerCount()];

        Arrays.fill(this.controllerKinds, ControllerKind.NEUTRAL);

        for (PlayerSlot slot : game.getPlayers()) {
            if (slot.getSlotIndex() < this.controllerKinds.length) {
                this.controllerKinds[slot.getSlotIndex()] = slot.getControllerKind();
            }
        }

        this.firstFreeOrder = null;
        this.freeOrderCount = 0;

        for (int count = 0; count < game.getLimits().getOrders(); count++) {
            this.freeOrder(new QueuedOrder());
        }
    }

    /**
     * Replaces a unit's orders with one order, which becomes the current order.
     * <p>
     * The queued orders return to the pool; then the new order starts ({@link OrderBehavior#start}).
     *
     * @param unit             the unit
     * @param orderType        the order type
     * @param targetX          the x coordinate of the target point
     * @param targetY          the y coordinate of the target point
     * @param targetUnit       the target unit, or {@code null}
     * @param instructionLimit the instruction limit of each animation program run the change of the current order starts
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void issue(@NotNull Unit unit, @NotNull OrderType orderType, int targetX, int targetY, @Nullable Unit targetUnit, int instructionLimit) {
        requireAttached(this.behaviors);

        this.clearQueue(unit);

        unit.setOrderType(orderType);
        unit.setOrderTargetX(targetX);
        unit.setOrderTargetY(targetY);
        unit.setOrderTargetUnit(targetUnit);

        this.startOrder(unit, instructionLimit);
    }

    /**
     * Appends an order after a unit's queued orders.
     *
     * @param unit       the unit
     * @param orderType  the order type
     * @param targetX    the x coordinate of the target point
     * @param targetY    the y coordinate of the target point
     * @param targetUnit the target unit, or {@code null}
     * @return {@code true} if the order was queued, {@code false} if the queue is full
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean queue(@NotNull Unit unit, @NotNull OrderType orderType, int targetX, int targetY, @Nullable Unit targetUnit) {
        requireAttached(this.behaviors);

        return this.appendOrder(unit, orderType, targetX, targetY, targetUnit);
    }

    /**
     * Ends a unit's current order; the next queued order, or the idle order of the unit's type, becomes current.
     * <p>
     * The first queued order leaves the queue and returns to the pool; the idle order targets the unit's position. Then the new current order
     * starts ({@link OrderBehavior#start}).
     *
     * @param unit             the unit
     * @param instructionLimit the instruction limit of each animation program run the change of the current order starts
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void finishOrder(@NotNull Unit unit, int instructionLimit) {
        requireAttached(this.behaviors);

        QueuedOrder first = unit.getFirstOrder();

        if (first == null) {
            OrderType idleOrder = this.getIdleOrder(unit);

            unit.setOrderType(idleOrder);
            unit.setOrderTargetX(unit.getPositionX());
            unit.setOrderTargetY(unit.getPositionY());
            unit.setOrderTargetUnit(null);

            this.startOrder(unit, instructionLimit);

            return;
        }

        QueuedOrder next = first.getNext();

        unit.setFirstOrder(next);

        if (next == null) {
            unit.setLastOrder(null);
        } else {
            next.setPrevious(null);
        }

        unit.setOrderType(first.getType());
        unit.setOrderTargetX(first.getTargetX());
        unit.setOrderTargetY(first.getTargetY());
        unit.setOrderTargetUnit(first.getTargetUnit());

        this.freeOrder(first);
        this.startOrder(unit, instructionLimit);
    }

    /**
     * Runs the current order of one unit for one frame.
     * <p>
     * Runs the behavior of the unit's order type with the context of this system.
     *
     * @param unit             the unit
     * @param instructionLimit the instruction limit of each animation program run the order starts
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void update(@NotNull Unit unit, int instructionLimit) {
        OrderRegistry<OrderBehavior<OrderContext, Unit>> registry = requireAttached(this.behaviors);
        Context dispatch = requireAttached(this.context);
        OrderType orderType = unit.getOrderType();

        if (orderType == null) {
            return;
        }

        OrderBehavior<OrderContext, Unit> behavior = registry.find(orderType);

        if (behavior == null) {
            return;
        }

        dispatch.instructionLimit = instructionLimit;

        behavior.update(dispatch, unit);
    }

    /**
     * Runs this phase of the current frame.
     * <p>
     * Updates the order of every allocated unit in ascending slot order with the phase instruction limit.
     *
     * @param game the game
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void run(@NotNull Game game) {
        EntityPool<Unit> pool = requireAttached(this.units);

        for (int index = 1; index <= pool.getCapacity(); index++) {
            if (pool.isAllocated(index)) {
                this.update(pool.get(index), this.phaseInstructionLimit);
            }
        }
    }

    /**
     * Removes the queued orders of a unit that leaves the game and returns them to the order pool.
     * <p>
     * The standard order system also resets the unit's order state ({@link Unit#resetOrders()}).
     *
     * @param unit the unit
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void removeOrders(@NotNull Unit unit) {
        requireAttached(this.behaviors);

        this.clearQueue(unit);

        unit.resetOrders();
    }

    /**
     * Writes this part of the simulation state: the number of unit slots, then the queue of every slot in slot order, each as its length and
     * every queued order in queue order (its type index, target point and target unit slot index).
     *
     * @param writer the writer of the snapshot
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        EntityPool<Unit> pool = requireAttached(this.units);

        writer.writeInt(pool.getCapacity());

        for (int index = 1; index <= pool.getCapacity(); index++) {
            Unit unit = pool.get(index);
            int length = 0;

            for (QueuedOrder order = unit.getFirstOrder(); order != null; order = order.getNext()) {
                length++;
            }

            writer.writeInt(length);

            for (QueuedOrder order = unit.getFirstOrder(); order != null; order = order.getNext()) {
                writer.writeType(order.getType());
                writer.writeInt(order.getTargetX());
                writer.writeInt(order.getTargetY());
                writer.writeEntity(order.getTargetUnit());
            }
        }
    }

    /**
     * Reads this part of the simulation state back, replacing the current values: every queue returns to the pool first, then the written
     * queues are rebuilt from it in slot order. The snapshot is invalid if it ends early, holds another number of slots, more queued orders
     * than the order limit or names no order type or unit of the game.
     *
     * @param reader the reader of the snapshot
     * @throws IOException           if the snapshot is invalid
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        EntityPool<Unit> pool = requireAttached(this.units);
        TypeRegistry<OrderType> orderTypes = reader.getGame().getContent().getDefinition().getOrderTypes().getTypes();

        reader.readLength(pool.getCapacity());

        for (int index = 1; index <= pool.getCapacity(); index++) {
            this.clearQueue(pool.get(index));
        }

        for (int index = 1; index <= pool.getCapacity(); index++) {
            Unit unit = pool.get(index);
            int length = reader.readInt();

            for (int position = 0; position < length; position++) {
                OrderType orderType = reader.readType(orderTypes);
                int targetX = reader.readInt();
                int targetY = reader.readInt();
                Unit targetUnit = reader.readEntity(pool);

                if (!this.appendOrder(unit, orderType, targetX, targetY, targetUnit)) {
                    throw Exceptions.newIOException("The state snapshot queues more orders than the order limit of the game.");
                }
            }
        }
    }

    /**
     * Takes a queued order from the pool and appends it to a unit's queue.
     *
     * @return {@code true} if the order was queued, {@code false} if the pool is empty
     */
    private boolean appendOrder(@NotNull Unit unit, @Nullable OrderType orderType, int targetX, int targetY, @Nullable Unit targetUnit) {
        QueuedOrder order = this.firstFreeOrder;

        if (order == null) {
            return false;
        }

        this.firstFreeOrder = order.getNext();
        this.freeOrderCount--;

        QueuedOrder last = unit.getLastOrder();

        order.set(orderType, targetX, targetY, targetUnit);
        order.setPrevious(last);
        order.setNext(null);

        if (last == null) {
            unit.setFirstOrder(order);
        } else {
            last.setNext(order);
        }

        unit.setLastOrder(order);

        return true;
    }

    private void startOrder(@NotNull Unit unit, int instructionLimit) {
        OrderRegistry<OrderBehavior<OrderContext, Unit>> registry = requireAttached(this.behaviors);
        Context dispatch = requireAttached(this.context);
        OrderType orderType = unit.getOrderType();
        OrderBehavior<OrderContext, Unit> behavior = orderType != null ? registry.find(orderType) : null;

        if (behavior == null) {
            return;
        }

        dispatch.instructionLimit = instructionLimit;

        behavior.start(dispatch, unit);
    }

    private void clearQueue(@NotNull Unit unit) {
        QueuedOrder order = unit.getFirstOrder();

        while (order != null) {
            QueuedOrder next = order.getNext();

            this.freeOrder(order);

            order = next;
        }

        unit.setFirstOrder(null);
        unit.setLastOrder(null);
    }

    private void freeOrder(@NotNull QueuedOrder order) {
        order.set(null, 0, 0, null);
        order.setPrevious(null);
        order.setNext(this.firstFreeOrder);

        this.firstFreeOrder = order;
        this.freeOrderCount++;
    }

    private @NotNull ControllerKind getControllerKind(@Nullable Player owner) {
        return owner != null && owner.getIndex() < this.controllerKinds.length ? this.controllerKinds[owner.getIndex()] : ControllerKind.NEUTRAL;
    }
}
