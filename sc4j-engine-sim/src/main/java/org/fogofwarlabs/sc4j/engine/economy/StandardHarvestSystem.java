package org.fogofwarlabs.sc4j.engine.economy;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.collision.BlockingHook;
import org.fogofwarlabs.sc4j.engine.collision.CollisionAnswer;
import org.fogofwarlabs.sc4j.engine.collision.CollisionSystem;
import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceKind;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.TypeRegistry;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.EntityKind;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.entity.pool.SlotList;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.lifecycle.UnitAccountingUtils;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.ProductionSystem;
import org.fogofwarlabs.sc4j.engine.spatial.UnitBounds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the resources of containers and workers in unit columns and carries out the gathering and delivery steps of the engine
 * {@link GatherBehavior} and {@link ReturnResourcesBehavior}: the engine default {@link HarvestSystem}. A mod registers it under
 * {@link HarvestSystem#KEY} with the bounds of its units and its gathering settings, adds its unit columns to the game definition
 * ({@link #addColumns}), and registers the two behaviors for its gather and return orders.
 * {@snippet :
 * StandardHarvestSystem.addColumns(builder);
 * builder.useSystem(HarvestSystem.KEY, context -> new StandardHarvestSystem(context.require(TableUnitBounds.KEY), 8, 40, 4))
 *        .addHook(CollisionSystem.BLOCKING, context -> (StandardHarvestSystem) context.require(HarvestSystem.KEY));
 *}
 * <p>
 * A worker gathers from a container within its reach: the gap between their bounds ({@link UnitBounds#getGap}) is at most the reach. One trip
 * takes the gather time in frames, then the worker carries the gather amount, or what the container has left, of the container's kind; a
 * container that is emptied leaves the game ({@link UnitAccountingUtils#removeUnit}, not as a death). A worker delivers at a drop-off within its
 * reach: the nearest completed unit of its owner whose type is a {@link UnitTrait#RESOURCE_DEPOT}; what it carries is added to the owner's amount
 * of the kind ({@link EconomySystem#setAmount}). Distances of the searches are the Euclidean distances between the unit positions; equally near
 * units go in slot order.
 * <p>
 * As a {@link CollisionSystem#BLOCKING} hook, the system lets two harvesting workers (each has a container to gather from or carries
 * resources) pass each other, so that workers going to and coming from a container do not block each other; for any other pair it gives no
 * answer.
 * <p>
 * Determinism contract: the system draws no random value and allocates nothing after it is attached; searches visit the pool in slot order. Its
 * state is in its unit columns, which the unit pool mixes into the state hash.
 */
public final class StandardHarvestSystem implements HarvestSystem, BlockingHook {
    /**
     * The unit column holding the resource kind index of a container plus one, {@code 0} for a unit without resources.
     */
    public static final @NotNull ColumnKey<IntColumn> RESOURCE_KIND = ColumnKey.ofInt("harvest.resource-kind", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the amount a container holds.
     */
    public static final @NotNull ColumnKey<IntColumn> RESOURCE_AMOUNT = ColumnKey.ofInt("harvest.resource-amount", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the resource kind index a worker carries plus one, {@code 0} for none.
     */
    public static final @NotNull ColumnKey<IntColumn> CARRIED_KIND = ColumnKey.ofInt("harvest.carried-kind", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the amount a worker carries.
     */
    public static final @NotNull ColumnKey<IntColumn> CARRIED_AMOUNT = ColumnKey.ofInt("harvest.carried-amount", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the handle of the container a worker gathers from ({@link EntityPool#createHandle}), {@code 0} for none.
     */
    public static final @NotNull ColumnKey<IntColumn> GATHER_TARGET = ColumnKey.ofInt("harvest.gather-target", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the frames a worker's gathering trip still needs, {@code 0} when it gathers nothing.
     */
    public static final @NotNull ColumnKey<IntColumn> GATHER_REMAINING_TIME = ColumnKey.ofInt("harvest.gather-remaining-time", 0,
                                                                                              ColumnReset.ON_ALLOCATE);

    /**
     * The column value of no kind and no handle.
     */
    private static final int NONE = 0;

    /**
     * Adds the unit columns of this system to a game definition; a game that registers this system needs them.
     *
     * @param builder the game definition builder
     * @throws IllegalArgumentException if a column is already added
     * @throws IllegalStateException    if the builder is closed
     */
    public static void addColumns(@NotNull GameDefinition.Builder builder) {
        builder.addColumn(EntityKind.UNIT, RESOURCE_KIND);
        builder.addColumn(EntityKind.UNIT, RESOURCE_AMOUNT);
        builder.addColumn(EntityKind.UNIT, CARRIED_KIND);
        builder.addColumn(EntityKind.UNIT, CARRIED_AMOUNT);
        builder.addColumn(EntityKind.UNIT, GATHER_TARGET);
        builder.addColumn(EntityKind.UNIT, GATHER_REMAINING_TIME);
    }

    private static long squareDistance(long horizontal, long vertical) {
        return horizontal * horizontal + vertical * vertical;
    }

    private static <T> @NotNull T requireAttached(@Nullable T value) {
        if (value == null) {
            throw Exceptions.newIllegalStateException("The standard harvest system is not attached.");
        }

        return value;
    }

    private final @NotNull UnitBounds<Unit> bounds;
    private final int gatherAmount;
    private final int gatherTime;
    private final int reach;

    private @Nullable Game game;
    private @Nullable EntityPool<Unit> units;
    private @Nullable UnitTypeTable unitTypes;
    private @Nullable TypeRegistry<ResourceKind> resourceKinds;
    private @Nullable IntColumn resourceKindColumn;
    private @Nullable IntColumn resourceAmounts;
    private @Nullable IntColumn carriedKinds;
    private @Nullable IntColumn carriedAmounts;
    private @Nullable IntColumn gatherTargets;
    private @Nullable IntColumn gatherRemainingTimes;

    /**
     * Creates a {@link StandardHarvestSystem} instance; it serves nothing until it is attached.
     *
     * @param bounds       reads the bounds of a unit, for the reach of workers
     * @param gatherAmount the amount a worker carries from one trip, at least {@code 1}
     * @param gatherTime   the frames one gathering trip takes at the container, at least {@code 1}
     * @param reach        the largest gap in pixels between a worker's bounds and a container's or drop-off's bounds at which it gathers or delivers, at least {@code 0}
     * @throws IllegalArgumentException if the amount or the time is below {@code 1} or the reach is negative
     */
    public StandardHarvestSystem(@NotNull UnitBounds<Unit> bounds, int gatherAmount, int gatherTime, int reach) {
        if (gatherAmount < 1 || gatherTime < 1 || reach < 0) {
            throw Exceptions.newIllegalArgumentException("Invalid gathering settings: amount {0}, time {1}, reach {2}",
                                                         gatherAmount, gatherTime, reach);
        }

        this.bounds       = bounds;
        this.gatherAmount = gatherAmount;
        this.gatherTime   = gatherTime;
        this.reach        = reach;

        this.game                 = null;
        this.units                = null;
        this.unitTypes            = null;
        this.resourceKinds        = null;
        this.resourceKindColumn   = null;
        this.resourceAmounts      = null;
        this.carriedKinds         = null;
        this.carriedAmounts       = null;
        this.gatherTargets        = null;
        this.gatherRemainingTimes = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the unit pool with this system's columns, the unit type table and the resource kinds of the content.
     *
     * @param game the game
     * @throws IllegalArgumentException if the unit pool lacks a column of this system ({@link #addColumns})
     */
    @Override
    public void attach(@NotNull Game game) {
        EntityPool<Unit> pool = game.getUnits();

        this.game                 = game;
        this.units                = pool;
        this.unitTypes            = game.getContent().getUnitTypes();
        this.resourceKinds        = game.getContent().getDefinition().getResourceKinds();
        this.resourceKindColumn   = pool.getColumn(RESOURCE_KIND);
        this.resourceAmounts      = pool.getColumn(RESOURCE_AMOUNT);
        this.carriedKinds         = pool.getColumn(CARRIED_KIND);
        this.carriedAmounts       = pool.getColumn(CARRIED_AMOUNT);
        this.gatherTargets        = pool.getColumn(GATHER_TARGET);
        this.gatherRemainingTimes = pool.getColumn(GATHER_REMAINING_TIME);
    }

    /**
     * Fills a resource container.
     *
     * @param container the container unit
     * @param kind      the kind of resource it holds
     * @param amount    the amount it holds, at least {@code 0}
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void setResources(@NotNull Unit container, @NotNull ResourceKind kind, int amount) {
        requireAttached(this.resourceKindColumn).set(container.getIndex(), kind.getIndex() + 1);
        requireAttached(this.resourceAmounts).set(container.getIndex(), amount);
    }

    /**
     * Returns the kind of resource a container holds.
     *
     * @param container the container unit
     * @return the kind, or {@code null} if the unit holds no resources
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public @Nullable ResourceKind getResourceKind(@NotNull Unit container) {
        return this.findKind(requireAttached(this.resourceKindColumn).get(container.getIndex()));
    }

    /**
     * Returns the amount a container holds.
     *
     * @param container the container unit
     * @return the amount, {@code 0} if the unit holds no resources
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public int getResourceAmount(@NotNull Unit container) {
        return requireAttached(this.resourceAmounts).get(container.getIndex());
    }

    /**
     * Returns the kind of resource a worker carries.
     *
     * @param worker the worker unit
     * @return the kind, or {@code null} if the worker carries nothing
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public @Nullable ResourceKind getCarriedKind(@NotNull Unit worker) {
        return this.findKind(requireAttached(this.carriedKinds).get(worker.getIndex()));
    }

    /**
     * Returns the amount a worker carries.
     *
     * @param worker the worker unit
     * @return the amount, {@code 0} if the worker carries nothing
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public int getCarriedAmount(@NotNull Unit worker) {
        return requireAttached(this.carriedAmounts).get(worker.getIndex());
    }

    /**
     * Finds the resource container nearest to a point that holds resources.
     *
     * @param kind  the kind of resource, or {@code null} for any
     * @param x     the x coordinate of the point
     * @param y     the y coordinate of the point
     * @param range the largest distance in pixels from the point
     * @return the container, the first in slot order among equally near ones, or {@code null} if none is within the range
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public @Nullable Unit findResourceContainer(@Nullable ResourceKind kind, int x, int y, int range) {
        EntityPool<Unit> pool = requireAttached(this.units);
        IntColumn kinds = requireAttached(this.resourceKindColumn);
        IntColumn amounts = requireAttached(this.resourceAmounts);
        SlotList used = pool.getUsedSlots();
        Unit nearest = null;
        long nearestDistance = (long) range * range;

        for (int index = used.getFirst(); index != SlotList.NONE; index = used.getNext(index)) {
            if (kinds.get(index) == NONE || amounts.get(index) <= 0 || kind != null && kinds.get(index) != kind.getIndex() + 1) {
                continue;
            }

            Unit candidate = pool.get(index);
            long distance = squareDistance(candidate.getPositionX() - x, candidate.getPositionY() - y);

            if (distance <= nearestDistance && (nearest == null || distance < nearestDistance)) {
                nearest         = candidate;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    /**
     * Answers whether another unit blocks the movement of a unit. The rule asks after it found that both units collide at all.
     * <p>
     * Two harvesting workers pass each other; any other pair gets no answer.
     *
     * @param unit      the moving unit
     * @param otherUnit the unit in the way
     * @return {@link CollisionAnswer#BLOCKS} or {@link CollisionAnswer#PASSES}, or {@link CollisionAnswer#NONE} if this hook gives no answer
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public @NotNull CollisionAnswer answerBlocking(@NotNull Unit unit, @NotNull Unit otherUnit) {
        return this.isHarvesting(unit) && this.isHarvesting(otherUnit) ? CollisionAnswer.PASSES : CollisionAnswer.NONE;
    }

    /**
     * Checks whether a worker is close enough to a container or drop-off to gather or deliver.
     *
     * @param worker the worker
     * @param target the container or drop-off
     * @return {@code true} if the gap between their bounds is at most the reach, {@code false} otherwise
     */
    public boolean isInReach(@NotNull Unit worker, @NotNull Unit target) {
        return this.bounds.getGap(worker, target) <= this.reach;
    }

    /**
     * Returns the container a worker gathers from.
     *
     * @param worker the worker
     * @return the container, or {@code null} if the worker has none, or its container left the game or holds nothing
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable Unit getGatherTarget(@NotNull Unit worker) {
        int handle = requireAttached(this.gatherTargets).get(worker.getIndex());
        Unit container = handle != NONE ? requireAttached(this.units).resolveHandle(handle) : null;

        return container != null && this.getResourceAmount(container) > 0 ? container : null;
    }

    /**
     * Sets the container a worker gathers from, and restarts its gathering trip.
     *
     * @param worker    the worker
     * @param container the container, or {@code null} for none
     * @throws IllegalStateException if the system is not attached
     */
    public void setGatherTarget(@NotNull Unit worker, @Nullable Unit container) {
        int index = worker.getIndex();

        requireAttached(this.gatherTargets).set(index, container != null ? requireAttached(this.units).createHandle(container) : NONE);
        requireAttached(this.gatherRemainingTimes).set(index, 0);
    }

    /**
     * Runs one frame of a worker's gathering trip at a container in reach: the first frame starts the trip, and when the gather time has passed
     * the worker takes the gather amount, or what is left, of the container's kind, adding it to what it carries; an emptied container leaves the
     * game.
     *
     * @param worker    the worker, which carries nothing of another kind
     * @param container the container, which holds resources
     * @return {@code true} if the trip is complete and the worker carries resources, {@code false} if it goes on
     * @throws IllegalStateException if the system is not attached
     */
    public boolean gather(@NotNull Unit worker, @NotNull Unit container) {
        IntColumn remainingTimes = requireAttached(this.gatherRemainingTimes);
        IntColumn amounts = requireAttached(this.resourceAmounts);
        int index = worker.getIndex();
        int remaining = remainingTimes.get(index);

        if (remaining == 0) {
            remaining = this.gatherTime;
        }

        remainingTimes.set(index, --remaining);

        if (remaining > 0) {
            return false;
        }

        int containerIndex = container.getIndex();
        int taken = Math.min(this.gatherAmount, amounts.get(containerIndex));
        IntColumn carried = requireAttached(this.carriedAmounts);

        amounts.set(containerIndex, amounts.get(containerIndex) - taken);
        requireAttached(this.carriedKinds).set(index, requireAttached(this.resourceKindColumn).get(containerIndex));
        carried.set(index, carried.get(index) + taken);

        if (amounts.get(containerIndex) == 0) {
            UnitAccountingUtils.removeUnit(requireAttached(this.game), container, false);
        }

        return true;
    }

    /**
     * Delivers what a worker carries: the owner's amount of the kind grows by it and the worker carries nothing afterwards.
     *
     * @param worker the worker
     * @return the delivered amount, {@code 0} if the worker carried nothing or has no owner
     * @throws IllegalStateException if the system is not attached
     */
    public int deliver(@NotNull Unit worker) {
        IntColumn kinds = requireAttached(this.carriedKinds);
        IntColumn amounts = requireAttached(this.carriedAmounts);
        int index = worker.getIndex();
        ResourceKind kind = this.findKind(kinds.get(index));
        int amount = amounts.get(index);
        Player owner = worker.getOwner();
        Game attachedGame = requireAttached(this.game);

        kinds.set(index, NONE);
        amounts.set(index, 0);

        if (kind == null || owner == null || amount == 0 || !attachedGame.hasSystem(EconomySystem.KEY)) {
            return 0;
        }

        EconomySystem economy = attachedGame.getEconomy();

        economy.setAmount(owner, kind, economy.getAmount(owner, kind) + amount);

        return amount;
    }

    /**
     * Finds where a worker delivers: the nearest completed unit of the worker's owner whose type is a {@link UnitTrait#RESOURCE_DEPOT}.
     *
     * @param worker the worker
     * @return the drop-off, the first in slot order among equally near ones, or {@code null} if the owner has none
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable Unit findDropOff(@NotNull Unit worker) {
        EntityPool<Unit> pool = requireAttached(this.units);
        UnitTypeTable table = requireAttached(this.unitTypes);
        Game attachedGame = requireAttached(this.game);
        ProductionSystem production = attachedGame.hasSystem(ProductionSystem.KEY) ? attachedGame.getProduction() : null;
        SlotList used = pool.getUsedSlots();
        Player owner = worker.getOwner();
        Unit nearest = null;
        long nearestDistance = Long.MAX_VALUE;

        if (owner == null) {
            return null;
        }

        for (int index = used.getFirst(); index != SlotList.NONE; index = used.getNext(index)) {
            Unit candidate = pool.get(index);
            UnitType type = candidate.getType();

            if (candidate.getOwner() != owner || type == null || !table.hasTrait(type, UnitTrait.RESOURCE_DEPOT) ||
                production != null && production.isUnderConstruction(candidate)) {
                continue;
            }

            long distance = squareDistance(candidate.getPositionX() - worker.getPositionX(), candidate.getPositionY() - worker.getPositionY());

            if (distance < nearestDistance) {
                nearest         = candidate;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    private boolean isHarvesting(@NotNull Unit unit) {
        int index = unit.getIndex();

        return requireAttached(this.gatherTargets).get(index) != NONE || requireAttached(this.carriedAmounts).get(index) > 0;
    }

    private @Nullable ResourceKind findKind(int value) {
        return value != NONE ? requireAttached(this.resourceKinds).get(value - 1) : null;
    }
}
