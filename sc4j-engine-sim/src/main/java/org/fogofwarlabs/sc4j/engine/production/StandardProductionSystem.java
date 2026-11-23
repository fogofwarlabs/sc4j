package org.fogofwarlabs.sc4j.engine.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.collision.CollisionSystem;
import org.fogofwarlabs.sc4j.engine.combat.StandardCombatSystem;
import org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer;
import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceKind;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementAction;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementSystem;
import org.fogofwarlabs.sc4j.engine.content.table.*;
import org.fogofwarlabs.sc4j.engine.content.type.*;
import org.fogofwarlabs.sc4j.engine.economy.EconomySystem;
import org.fogofwarlabs.sc4j.engine.entity.EntityKind;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.entity.column.BooleanColumn;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.entity.pool.SlotList;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.game.frame.FramePhase;
import org.fogofwarlabs.sc4j.engine.hook.HookList;
import org.fogofwarlabs.sc4j.engine.lifecycle.UnitAccountingUtils;
import org.fogofwarlabs.sc4j.engine.map.grid.GridTopology;
import org.fogofwarlabs.sc4j.engine.math.MutablePoint;
import org.fogofwarlabs.sc4j.engine.movement.MotionModel;
import org.fogofwarlabs.sc4j.engine.movement.MovementSystem;
import org.fogofwarlabs.sc4j.engine.order.OrderSystem;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.spatial.SpatialIndex;
import org.fogofwarlabs.sc4j.engine.spatial.UnitFilter;
import org.fogofwarlabs.sc4j.engine.spatial.UnitList;
import org.fogofwarlabs.sc4j.engine.terrain.TerrainGrid;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Trains units in a queue of each producer and researches techs and upgrades: the engine default {@link ProductionSystem}. A mod registers it
 * under {@link ProductionSystem#KEY} and as a frame phase, adds its unit columns to the game definition ({@link #addColumns}), and uses
 * {@link #createUnit} for the units its map places.
 * {@snippet :
 * StandardProductionSystem.addColumns(builder, 5);
 * builder.useSystem(ProductionSystem.KEY, context -> new StandardProductionSystem.Builder(5).setRallyOrder(move).build())
 *        .addFramePhase(ProductionSystem.KEY, production -> (StandardProductionSystem) production);
 *}
 * <p>
 * A producer queues up to the queue capacity the mod declares ({@link Builder#Builder(int)}) unit types; the game definition needs the entry
 * columns of that capacity ({@link #addColumns(GameDefinition.Builder, int)}). An entry is accepted when the producer has an owner and is not
 * under construction, the queue has room, the requirement system (if the game has one) allows {@link RequirementAction#BUILD_UNIT} for the
 * owner and the producer, and the owner holds the whole cost of the unit type in every resource kind; the cost is spent at once. The supply
 * (supply provided by the faction of the unit type minus supply used, if the game has an economy system) is held from the start of the front
 * entry, which waits until the owner has it; with {@link Builder#setSupplyHeldWhenQueued} it is held from the enqueue instead, which the owner
 * must have then. The front entry takes the build time of the unit type, at least one frame; then the unit is created next to the producer
 * ({@link #createUnit}), the slot is announced ({@link ProductionSystem#QUEUE_SLOT_CLEARED}) and the next entry moves up. A unit that finds
 * no free position waits a frame. A producer with a rally point ({@link #setRallyPoint}) sends each unit it makes there with the rally order
 * of the settings ({@link Builder#setRallyOrder}), if there is one. The animation programs the phase starts run with the phase instruction
 * limit of the settings ({@link Builder#setPhaseInstructionLimit}). Cancelling an entry refunds the refund share of its cost
 * ({@link Builder#setQueueRefundPercent}, the whole cost unless the mod sets a share) and the supply it holds; each later entry moves up one
 * slot ({@link ProductionSystem#QUEUE_SLOT_MOVED}). A producer that leaves the game ({@link #clearProduction}) gives back the supply its
 * entries hold; their cost is lost.
 * <p>
 * A research runs at most one at a time per producer, techs and upgrades alike, besides the queue: it needs the requirement system's
 * {@link RequirementAction#RESEARCH_TECH} or {@link RequirementAction#RESEARCH_UPGRADE} (without one: a tech not researched yet, an upgrade below
 * its maximum level), costs the tech's cost or the upgrade's base cost plus its cost factor per reached level, and takes the research time or the
 * upgrade's base time plus its time factor per reached level. A finished tech is researched for the owner, a finished upgrade one level higher.
 * <p>
 * Workers construct buildings ({@link #orderConstruction}, {@link BuildBehavior}). A building's footprint is its placement width and height
 * centered on its position (its collision bounds when the type has no placement size); it can be placed where every tile under it is buildable
 * on the terrain of the settings ({@link Builder#setTerrain}) and no unit that cannot be pushed aside (a building, a resource container or an
 * immovable unit) overlaps it. Placing a building ({@link #placeBuilding}) needs the requirement system's {@link RequirementAction#BUILD_UNIT}
 * with the worker as actor, the supply and the whole cost, which is spent at once; the building then stands under construction for its build
 * time with a share of its hit points ({@link Builder#setConstructionStartPercent}, the full hit points unless the mod sets a share) that grows
 * each frame to the full hit points. Under construction it counts towards its owner's unit count but neither towards the completed count nor
 * the supply it provides, and it neither queues nor researches; when the time has passed it is complete, gets its idle order and is announced
 * as completed. Cancelling a construction ({@link #cancelConstruction}) refunds a share of the cost
 * ({@link Builder#setConstructionRefundPercent}, the whole cost unless the mod sets a share) and takes the building out of the game.
 * <p>
 * Determinism contract: the phase visits the producers in unit slot order, each frame first the queue, then the research; the system draws no
 * random value and allocates nothing after it is attached. Its state is in its unit columns, which the unit pool mixes into the state hash.
 */
public final class StandardProductionSystem implements ProductionSystem, FramePhase {
    /**
     * The largest number of entries a producer's queue can hold.
     */
    public static final int MAXIMUM_QUEUE_CAPACITY = 16;
    /**
     * The unit column holding the number of entries of a producer's queue.
     */
    public static final @NotNull ColumnKey<IntColumn> QUEUE_LENGTH = ColumnKey.ofInt("production.queue-length", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding whether the front entry of a producer's queue has started.
     */
    public static final @NotNull ColumnKey<BooleanColumn> QUEUE_STARTED = ColumnKey.ofBoolean("production.queue-started", false,
                                                                                              ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the frames the front entry of a producer's queue still needs.
     */
    public static final @NotNull ColumnKey<IntColumn> QUEUE_REMAINING_TIME = ColumnKey.ofInt("production.queue-remaining-time", 0,
                                                                                             ColumnReset.ON_ALLOCATE);
    /**
     * The unit columns holding the unit type index of each queue entry plus one, {@code 0} for an empty slot, one per slot up to
     * {@link #MAXIMUM_QUEUE_CAPACITY}; a game adds those of its queue capacity.
     */
    public static final @NotNull List<@NotNull ColumnKey<IntColumn>> QUEUE_ENTRIES;
    /**
     * The unit column holding the tech index of a producer's research plus one, {@code 0} for none.
     */
    public static final @NotNull ColumnKey<IntColumn> RESEARCH_TECH = ColumnKey.ofInt("production.research-tech", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the upgrade index of a producer's research plus one, {@code 0} for none.
     */
    public static final @NotNull ColumnKey<IntColumn> RESEARCH_UPGRADE = ColumnKey.ofInt("production.research-upgrade", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the frames a producer's research still needs.
     */
    public static final @NotNull ColumnKey<IntColumn> RESEARCH_REMAINING_TIME = ColumnKey.ofInt("production.research-remaining-time", 0,
                                                                                                ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding whether a producer has a rally point.
     */
    public static final @NotNull ColumnKey<BooleanColumn> RALLY_POINT_SET = ColumnKey.ofBoolean("production.rally-point-set", false,
                                                                                                ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the x coordinate of a producer's rally point.
     */
    public static final @NotNull ColumnKey<IntColumn> RALLY_POINT_X = ColumnKey.ofInt("production.rally-point-x", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the y coordinate of a producer's rally point.
     */
    public static final @NotNull ColumnKey<IntColumn> RALLY_POINT_Y = ColumnKey.ofInt("production.rally-point-y", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the frames a building under construction still needs, {@code 0} for a complete unit.
     */
    public static final @NotNull ColumnKey<IntColumn> CONSTRUCTION_REMAINING_TIME = ColumnKey.ofInt("production.construction-remaining-time", 0,
                                                                                                    ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the unit type index plus one of the building a worker is ordered to construct, {@code 0} for none.
     */
    public static final @NotNull ColumnKey<IntColumn> CONSTRUCTION_TYPE = ColumnKey.ofInt("production.construction-type", 0, ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the handle of the building a worker constructs ({@link EntityPool#createHandle}), {@code 0} for none.
     */
    public static final @NotNull ColumnKey<IntColumn> CONSTRUCTED_BUILDING = ColumnKey.ofInt("production.constructed-building", 0,
                                                                                             ColumnReset.ON_ALLOCATE);
    /**
     * The smallest number of frames a production entry or research takes.
     */
    private static final int MINIMUM_TIME = 1;
    /**
     * The whole in percent.
     */
    private static final int PERCENT = 100;
    /**
     * The divisor that gives the half of a footprint.
     */
    private static final int HALF = 2;
    /**
     * The column value of no unit type, tech, upgrade or handle.
     */
    private static final int NONE = 0;
    /**
     * The name of a queue entry column before its slot.
     */
    private static final @NotNull String QUEUE_ENTRY_NAME = "production.queue-entry-";

    /**
     * Builds {@link StandardProductionSystem} instances: the settings of the production rules.
     */
    public static final class Builder {
        private @Nullable OrderType rallyOrder;
        private @Nullable OrderType buildOrder;
        private @Nullable TerrainGrid terrain;
        private final int queueCapacity;
        private int queueRefundPercent;
        private boolean supplyHeldWhenQueued;
        private int constructionRefundPercent;
        private int constructionStartPercent;
        private int phaseInstructionLimit;

        /**
         * Creates a {@link Builder} instance for a queue capacity, without rally order, build order and terrain, with the neutral shares (a
         * cancelled entry or construction refunds the whole cost, a building starts its construction with its full hit points), the supply
         * held from the start of an entry and the phase instruction limit {@code 0}. The game definition needs the entry columns of the queue
         * capacity ({@link StandardProductionSystem#addColumns(GameDefinition.Builder, int)}).
         *
         * @param queueCapacity the number of entries a producer's queue holds, {@code 1} up to {@link StandardProductionSystem#MAXIMUM_QUEUE_CAPACITY}
         * @throws IllegalArgumentException if the number is outside {@code 1} to {@link StandardProductionSystem#MAXIMUM_QUEUE_CAPACITY}
         */
        public Builder(int queueCapacity) {
            this.rallyOrder                = null;
            this.buildOrder                = null;
            this.terrain                   = null;
            this.queueCapacity             = requireQueueCapacity(queueCapacity);
            this.queueRefundPercent        = PERCENT;
            this.supplyHeldWhenQueued      = false;
            this.constructionRefundPercent = PERCENT;
            this.constructionStartPercent  = PERCENT;
            this.phaseInstructionLimit     = 0;
        }

        /**
         * Sets the order a unit gets towards its producer's rally point when it is made.
         *
         * @param rallyOrder the order type, or {@code null} to send no unit to a rally point
         * @return this instance for chaining
         */
        public @NotNull Builder setRallyOrder(@Nullable OrderType rallyOrder) {
            this.rallyOrder = rallyOrder;

            return this;
        }

        /**
         * Sets the order a worker gets to construct a building ({@link StandardProductionSystem#orderConstruction}).
         *
         * @param buildOrder the order type, or {@code null} if workers do not construct
         * @return this instance for chaining
         */
        public @NotNull Builder setBuildOrder(@Nullable OrderType buildOrder) {
            this.buildOrder = buildOrder;

            return this;
        }

        /**
         * Sets the terrain whose buildable tiles buildings are placed on.
         *
         * @param terrain the terrain, or {@code null} to place buildings anywhere free of units
         * @return this instance for chaining
         */
        public @NotNull Builder setTerrain(@Nullable TerrainGrid terrain) {
            this.terrain = terrain;

            return this;
        }

        /**
         * Sets the share of its cost a cancelled queue entry refunds.
         *
         * @param queueRefundPercent the share in percent, {@code 0} up to {@code 100}
         * @return this instance for chaining
         * @throws IllegalArgumentException if the share is outside {@code 0} to {@code 100}
         */
        public @NotNull Builder setQueueRefundPercent(int queueRefundPercent) {
            this.queueRefundPercent = requirePercent(queueRefundPercent);

            return this;
        }

        /**
         * Sets when a queue entry holds the supply of its unit type: from the enqueue, which the owner's free supply must allow then, or from
         * the start of the front entry, which waits until the owner has the supply.
         *
         * @param supplyHeldWhenQueued {@code true} to hold the supply from the enqueue, {@code false} from the start
         * @return this instance for chaining
         */
        public @NotNull Builder setSupplyHeldWhenQueued(boolean supplyHeldWhenQueued) {
            this.supplyHeldWhenQueued = supplyHeldWhenQueued;

            return this;
        }

        /**
         * Sets the share of its cost a cancelled construction refunds.
         *
         * @param constructionRefundPercent the share in percent, {@code 0} up to {@code 100}
         * @return this instance for chaining
         * @throws IllegalArgumentException if the share is outside {@code 0} to {@code 100}
         */
        public @NotNull Builder setConstructionRefundPercent(int constructionRefundPercent) {
            this.constructionRefundPercent = requirePercent(constructionRefundPercent);

            return this;
        }

        /**
         * Sets the share of its hit points a building starts its construction with.
         *
         * @param constructionStartPercent the share in percent, {@code 0} up to {@code 100}
         * @return this instance for chaining
         * @throws IllegalArgumentException if the share is outside {@code 0} to {@code 100}
         */
        public @NotNull Builder setConstructionStartPercent(int constructionStartPercent) {
            this.constructionStartPercent = requirePercent(constructionStartPercent);

            return this;
        }

        /**
         * Sets the instruction limit of each animation program run the phase starts: when a construction completes, when a queue makes a unit
         * and when that unit gets its rally order.
         *
         * @param phaseInstructionLimit the instruction limit
         * @return this instance for chaining
         */
        public @NotNull Builder setPhaseInstructionLimit(int phaseInstructionLimit) {
            this.phaseInstructionLimit = phaseInstructionLimit;

            return this;
        }

        /**
         * Builds a {@link StandardProductionSystem} instance with these settings.
         *
         * @return the built {@link StandardProductionSystem} instance
         */
        public @NotNull StandardProductionSystem build() {
            return new StandardProductionSystem(this);
        }
    }

    static {
        List<ColumnKey<IntColumn>> entries = new ArrayList<>(MAXIMUM_QUEUE_CAPACITY);

        for (int slot = 0; slot < MAXIMUM_QUEUE_CAPACITY; slot++) {
            entries.add(ColumnKey.ofInt(QUEUE_ENTRY_NAME + slot, 0, ColumnReset.ON_ALLOCATE));
        }

        QUEUE_ENTRIES = List.copyOf(entries);
    }

    /**
     * Adds the unit columns of this system with the entry columns of a queue capacity to a game definition; a game that registers this system
     * with that queue capacity ({@link Builder#Builder(int)}) needs them.
     *
     * @param builder       the game definition builder
     * @param queueCapacity the number of entries a producer's queue holds, {@code 1} up to {@link #MAXIMUM_QUEUE_CAPACITY}
     * @throws IllegalArgumentException if a column is already added or the capacity is outside {@code 1} to {@link #MAXIMUM_QUEUE_CAPACITY}
     * @throws IllegalStateException    if the builder is closed
     */
    public static void addColumns(@NotNull GameDefinition.Builder builder, int queueCapacity) {
        requireQueueCapacity(queueCapacity);
        builder.addColumn(EntityKind.UNIT, QUEUE_LENGTH);
        builder.addColumn(EntityKind.UNIT, QUEUE_STARTED);
        builder.addColumn(EntityKind.UNIT, QUEUE_REMAINING_TIME);

        for (int slot = 0; slot < queueCapacity; slot++) {
            builder.addColumn(EntityKind.UNIT, QUEUE_ENTRIES.get(slot));
        }

        builder.addColumn(EntityKind.UNIT, RESEARCH_TECH);
        builder.addColumn(EntityKind.UNIT, RESEARCH_UPGRADE);
        builder.addColumn(EntityKind.UNIT, RESEARCH_REMAINING_TIME);
        builder.addColumn(EntityKind.UNIT, RALLY_POINT_SET);
        builder.addColumn(EntityKind.UNIT, RALLY_POINT_X);
        builder.addColumn(EntityKind.UNIT, RALLY_POINT_Y);
        builder.addColumn(EntityKind.UNIT, CONSTRUCTION_REMAINING_TIME);
        builder.addColumn(EntityKind.UNIT, CONSTRUCTION_TYPE);
        builder.addColumn(EntityKind.UNIT, CONSTRUCTED_BUILDING);
    }

    private static int requirePercent(int percent) {
        if (percent < 0 || percent > PERCENT) {
            throw Exceptions.newIllegalArgumentException("Invalid share in percent: {0}", percent);
        }

        return percent;
    }

    private static int requireQueueCapacity(int queueCapacity) {
        if (queueCapacity < 1 || queueCapacity > MAXIMUM_QUEUE_CAPACITY) {
            throw Exceptions.newIllegalArgumentException("Invalid queue capacity: {0}", queueCapacity);
        }

        return queueCapacity;
    }

    private static <T> @NotNull T requireAttached(@Nullable T value) {
        if (value == null) {
            throw Exceptions.newIllegalStateException("The standard production system is not attached.");
        }

        return value;
    }

    private final @Nullable OrderType rallyOrder;
    private final @Nullable OrderType buildOrder;
    private final @Nullable TerrainGrid terrain;
    private final int queueCapacity;
    private final int queueRefundPercent;
    private final boolean supplyHeldWhenQueued;
    private final int constructionRefundPercent;
    private final int constructionStartPercent;
    private final int phaseInstructionLimit;

    private final @NotNull MutablePoint freePosition;
    private final @NotNull IntColumn @NotNull [] entries;
    private final @NotNull UnitFilter placementFilter;
    private final @NotNull UnitList placementBlockers;

    private int @NotNull [] cost;

    private @Nullable Game game;
    private @Nullable GameContent content;
    private @Nullable EntityPool<Unit> units;
    private @Nullable List<@NotNull ResourceKind> resourceKinds;
    private @Nullable HookList<QueueSlotClearedHook> slotClearedHooks;
    private @Nullable HookList<QueueSlotMovedHook> slotMovedHooks;
    private @Nullable IntColumn lengths;
    private @Nullable BooleanColumn started;
    private @Nullable IntColumn remainingTimes;
    private @Nullable IntColumn researchTechs;
    private @Nullable IntColumn researchUpgrades;
    private @Nullable IntColumn researchRemainingTimes;
    private @Nullable BooleanColumn rallyPointsSet;
    private @Nullable IntColumn rallyPointsX;
    private @Nullable IntColumn rallyPointsY;
    private @Nullable IntColumn constructionRemainingTimes;
    private @Nullable IntColumn constructionTypes;
    private @Nullable IntColumn constructedBuildings;

    private StandardProductionSystem(@NotNull Builder builder) {
        this.rallyOrder                = builder.rallyOrder;
        this.buildOrder                = builder.buildOrder;
        this.terrain                   = builder.terrain;
        this.queueCapacity             = builder.queueCapacity;
        this.queueRefundPercent        = builder.queueRefundPercent;
        this.supplyHeldWhenQueued      = builder.supplyHeldWhenQueued;
        this.constructionRefundPercent = builder.constructionRefundPercent;
        this.constructionStartPercent  = builder.constructionStartPercent;
        this.phaseInstructionLimit     = builder.phaseInstructionLimit;

        this.freePosition      = new MutablePoint();
        this.entries           = new IntColumn[builder.queueCapacity];
        this.placementFilter   = this::isPlacementBlocker;
        this.placementBlockers = new UnitList(1);

        this.cost = new int[0];

        this.game                       = null;
        this.content                    = null;
        this.units                      = null;
        this.resourceKinds              = null;
        this.slotClearedHooks           = null;
        this.slotMovedHooks             = null;
        this.lengths                    = null;
        this.started                    = null;
        this.remainingTimes             = null;
        this.researchTechs              = null;
        this.researchUpgrades           = null;
        this.researchRemainingTimes     = null;
        this.rallyPointsSet             = null;
        this.rallyPointsX               = null;
        this.rallyPointsY               = null;
        this.constructionRemainingTimes = null;
        this.constructionTypes          = null;
        this.constructedBuildings       = null;
    }

    /**
     * @return the terrain buildings are placed on, or {@code null} if buildings are placed anywhere free of units
     */
    public @Nullable TerrainGrid getTerrain() {
        return this.terrain;
    }

    /**
     * @return the number of entries a producer's queue holds
     */
    public int getQueueCapacity() {
        return this.queueCapacity;
    }

    /**
     * Checks whether a queue entry holds its supply from the enqueue, which then refuses an entry without free supply, or from the start of the
     * front entry.
     *
     * @return {@code true} if an entry holds its supply from the enqueue, {@code false} if from the start of the front entry
     */
    public boolean isSupplyHeldWhenQueued() {
        return this.supplyHeldWhenQueued;
    }


    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the content, the resource kinds, the queue hooks and the unit pool with this system's columns
     * ({@link #addColumns(GameDefinition.Builder, int)}).
     *
     * @param game the game
     * @throws IllegalArgumentException if the unit pool lacks a column of this system or an entry column of its queue capacity
     */
    @Override
    public void attach(@NotNull Game game) {
        EntityPool<Unit> pool = game.getUnits();

        this.game                       = game;
        this.content                    = game.getContent();
        this.units                      = pool;
        this.resourceKinds              = game.getContent().getDefinition().getResourceKinds().getTypes();
        this.cost                       = new int[this.resourceKinds.size()];
        this.slotClearedHooks           = game.getHooks(QUEUE_SLOT_CLEARED);
        this.slotMovedHooks             = game.getHooks(QUEUE_SLOT_MOVED);
        this.lengths                    = pool.getColumn(QUEUE_LENGTH);
        this.started                    = pool.getColumn(QUEUE_STARTED);
        this.remainingTimes             = pool.getColumn(QUEUE_REMAINING_TIME);
        this.researchTechs              = pool.getColumn(RESEARCH_TECH);
        this.researchUpgrades           = pool.getColumn(RESEARCH_UPGRADE);
        this.researchRemainingTimes     = pool.getColumn(RESEARCH_REMAINING_TIME);
        this.rallyPointsSet             = pool.getColumn(RALLY_POINT_SET);
        this.rallyPointsX               = pool.getColumn(RALLY_POINT_X);
        this.rallyPointsY               = pool.getColumn(RALLY_POINT_Y);
        this.constructionRemainingTimes = pool.getColumn(CONSTRUCTION_REMAINING_TIME);
        this.constructionTypes          = pool.getColumn(CONSTRUCTION_TYPE);
        this.constructedBuildings       = pool.getColumn(CONSTRUCTED_BUILDING);

        for (int slot = 0; slot < this.queueCapacity; slot++) {
            this.entries[slot] = pool.getColumn(QUEUE_ENTRIES.get(slot));
        }
    }

    /**
     * Adds a unit to train, build or morph at the end of a producer's queue after testing its cost; the cost is spent now or when the entry
     * starts, as the implementation documents.
     * <p>
     * The standard queue also refuses an entry the requirement system does not allow or of a producer under construction, and spends the cost
     * now; with the supply held when queued it also refuses an entry whose supply the owner lacks, and holds it now.
     *
     * @param producer the producing unit
     * @param type     the unit type to make
     * @return the queue slot of the new entry, or {@code -1} if the queue is full or the cost cannot be paid
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public int enqueueUnit(@NotNull Unit producer, @NotNull UnitType type) {
        IntColumn queueLengths = requireAttached(this.lengths);
        Player owner = producer.getOwner();
        int index = producer.getIndex();
        int length = queueLengths.get(index);

        if (owner == null || length >= this.queueCapacity || this.isUnderConstruction(producer) ||
            !this.isAllowed(RequirementAction.BUILD_UNIT, type, owner, producer)) {
            return -1;
        }

        if (this.supplyHeldWhenQueued && !this.hasSupply(owner, type)) {
            return -1;
        }

        this.fillUnitCost(type);

        if (!this.spendCost(owner)) {
            return -1;
        }

        if (this.supplyHeldWhenQueued) {
            this.addSupplyUsed(owner, type, 1);
        }

        this.entries[length].set(index, type.getIndex() + 1);
        queueLengths.set(index, length + 1);

        return length;
    }

    /**
     * Starts researching a tech at a producer after testing and spending its cost.
     *
     * @param producer         the producing unit
     * @param type             the tech
     * @param instructionLimit the instruction limit of each animation program run the start starts
     * @return {@code true} if the research started, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean startResearch(@NotNull Unit producer, @NotNull TechType type, int instructionLimit) {
        GameContent attachedContent = requireAttached(this.content);
        Player owner = producer.getOwner();

        if (owner == null || this.isResearching(producer) || this.isUnderConstruction(producer) ||
            !this.isAllowed(RequirementAction.RESEARCH_TECH, type, owner, producer)) {
            return false;
        }

        if (!requireAttached(this.game).hasSystem(RequirementSystem.KEY) && attachedContent.getProgress().isTechResearched(owner, type)) {
            return false;
        }

        TechTypeTable techs = attachedContent.getTechTypes();
        List<ResourceKind> kinds = requireAttached(this.resourceKinds);

        for (int kind = 0; kind < kinds.size(); kind++) {
            this.cost[kind] = techs.getCost(type, kinds.get(kind));
        }

        if (!this.spendCost(owner)) {
            return false;
        }

        requireAttached(this.researchTechs).set(producer.getIndex(), type.getIndex() + 1);
        requireAttached(this.researchRemainingTimes).set(producer.getIndex(), Math.max(MINIMUM_TIME, techs.getResearchTime(type)));

        return true;
    }

    /**
     * Starts the next level of an upgrade at a producer after testing and spending its cost.
     *
     * @param producer         the producing unit
     * @param type             the upgrade
     * @param instructionLimit the instruction limit of each animation program run the start starts
     * @return {@code true} if the upgrade started, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean startUpgrade(@NotNull Unit producer, @NotNull UpgradeType type, int instructionLimit) {
        GameContent attachedContent = requireAttached(this.content);
        UpgradeTypeTable upgrades = attachedContent.getUpgradeTypes();
        Player owner = producer.getOwner();

        if (owner == null || this.isResearching(producer) || this.isUnderConstruction(producer) ||
            !this.isAllowed(RequirementAction.RESEARCH_UPGRADE, type, owner, producer)) {
            return false;
        }

        int level = attachedContent.getProgress().getUpgradeLevel(owner, type);

        if (!requireAttached(this.game).hasSystem(RequirementSystem.KEY) && level >= upgrades.getMaximumLevel(type)) {
            return false;
        }

        List<ResourceKind> kinds = requireAttached(this.resourceKinds);

        for (int kind = 0; kind < kinds.size(); kind++) {
            this.cost[kind] = upgrades.getBaseCost(type, kinds.get(kind)) + level * upgrades.getCostFactor(type, kinds.get(kind));
        }

        if (!this.spendCost(owner)) {
            return false;
        }

        int time = upgrades.getBaseTime(type) + level * upgrades.getTimeFactor(type);

        requireAttached(this.researchUpgrades).set(producer.getIndex(), type.getIndex() + 1);
        requireAttached(this.researchRemainingTimes).set(producer.getIndex(), Math.max(MINIMUM_TIME, time));

        return true;
    }

    /**
     * Cancels one entry of a producer's queue; the later entries move up.
     * <p>
     * The standard queue refunds the queue refund share of the entry's cost and the supply the entry holds.
     *
     * @param producer         the producing unit
     * @param slot             the queue slot
     * @param instructionLimit the instruction limit of each animation program run the cancellation starts
     * @return {@code true} if an entry was cancelled, {@code false} if the slot is empty
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean cancel(@NotNull Unit producer, int slot, int instructionLimit) {
        int length = this.getQueueLength(producer);

        if (slot < 0 || slot >= length) {
            return false;
        }

        UnitType type = this.getQueuedType(producer, slot);
        Player owner = producer.getOwner();
        int index = producer.getIndex();

        if (owner != null) {
            this.fillUnitCost(type);
            this.refundCost(owner, this.queueRefundPercent);

            if (this.holdsSupply(producer, slot)) {
                this.addSupplyUsed(owner, type, -1);
            }
        }

        if (slot == 0) {
            requireAttached(this.started).set(index, false);
            requireAttached(this.remainingTimes).set(index, 0);
        }

        this.removeEntry(producer, slot);

        return true;
    }

    /**
     * Clears the production of a producer that leaves the game, before its counts and supply leave its owner: the queue and the research end,
     * the supply the queue entries hold is given back, and their spent cost is lost. The default has nothing to clear.
     * <p>
     * The standard system clears the queue, its front entry's time and the research without announcing the emptied slots.
     *
     * @param producer the producing unit
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void clearProduction(@NotNull Unit producer) {
        IntColumn queueLengths = requireAttached(this.lengths);
        Player owner = producer.getOwner();
        int index = producer.getIndex();
        int length = queueLengths.get(index);

        for (int slot = 0; slot < length; slot++) {
            if (owner != null && this.holdsSupply(producer, slot)) {
                this.addSupplyUsed(owner, this.getQueuedType(producer, slot), -1);
            }
        }

        for (int slot = 0; slot < length; slot++) {
            this.entries[slot].set(index, NONE);
        }

        queueLengths.set(index, 0);
        requireAttached(this.started).set(index, false);
        requireAttached(this.remainingTimes).set(index, 0);
        requireAttached(this.researchTechs).set(index, NONE);
        requireAttached(this.researchUpgrades).set(index, NONE);
        requireAttached(this.researchRemainingTimes).set(index, 0);
    }

    /**
     * Returns the length of a producer's queue.
     *
     * @param producer the producing unit
     * @return the number of entries in the producer's queue
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public int getQueueLength(@NotNull Unit producer) {
        return requireAttached(this.lengths).get(producer.getIndex());
    }

    /**
     * Returns the unit type of one queue entry.
     *
     * @param producer the producing unit
     * @param slot     the queue slot, below the queue length
     * @return the unit type
     * @throws IndexOutOfBoundsException if the slot is not below the queue length
     * @throws IllegalStateException     if the system is not attached
     */
    @Override
    public @NotNull UnitType getQueuedType(@NotNull Unit producer, int slot) {
        int length = this.getQueueLength(producer);

        if (slot < 0 || slot >= length) {
            throw Exceptions.newIndexOutOfBoundsException("No queue entry {0}; the queue holds {1}.", slot, length);
        }

        return this.getUnitTypes().get(this.entries[slot].get(producer.getIndex()) - 1);
    }

    /**
     * Returns the frames the front entry of a producer's queue still needs.
     *
     * @param producer the producing unit
     * @return the remaining frames, or {@code 0} if the front entry has not started or the queue is empty
     * @throws IllegalStateException if the system is not attached
     */
    public int getRemainingTime(@NotNull Unit producer) {
        return requireAttached(this.remainingTimes).get(producer.getIndex());
    }

    /**
     * @param producer the producing unit
     * @return {@code true} if the front entry of the producer's queue has started, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean isQueueStarted(@NotNull Unit producer) {
        return requireAttached(this.started).get(producer.getIndex());
    }

    /**
     * Checks whether the front entry of a producer's queue waits for its owner's supply: it has not started, the supply is held from the
     * start ({@link Builder#setSupplyHeldWhenQueued} not set) and the owner lacks the supply of its unit type now. Reads the state only.
     *
     * @param producer the producing unit
     * @return {@code true} if the front entry waits for supply, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean isWaitingForSupply(@NotNull Unit producer) {
        Player owner = producer.getOwner();
        int index = producer.getIndex();

        if (owner == null || this.supplyHeldWhenQueued || requireAttached(this.lengths).get(index) == 0 ||
            requireAttached(this.started).get(index) || this.isUnderConstruction(producer)) {
            return false;
        }

        return !this.hasSupply(owner, this.getQueuedType(producer, 0));
    }

    /**
     * @param producer the producing unit
     * @return {@code true} if the producer researches a tech or an upgrade, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean isResearching(@NotNull Unit producer) {
        int index = producer.getIndex();

        return requireAttached(this.researchTechs).get(index) != NONE || requireAttached(this.researchUpgrades).get(index) != NONE;
    }

    /**
     * Sets the rally point of a producer: the units it makes from now on are sent there with the rally order.
     *
     * @param producer the producing unit
     * @param x        the x coordinate of the rally point in pixels
     * @param y        the y coordinate of the rally point in pixels
     * @throws IllegalStateException if the system is not attached
     */
    public void setRallyPoint(@NotNull Unit producer, int x, int y) {
        int index = producer.getIndex();

        requireAttached(this.rallyPointsSet).set(index, true);
        requireAttached(this.rallyPointsX).set(index, x);
        requireAttached(this.rallyPointsY).set(index, y);
    }

    /**
     * Removes the rally point of a producer: the units it makes stay where they appear.
     *
     * @param producer the producing unit
     * @throws IllegalStateException if the system is not attached
     */
    public void clearRallyPoint(@NotNull Unit producer) {
        requireAttached(this.rallyPointsSet).set(producer.getIndex(), false);
    }

    /**
     * Reads the rally point of a producer.
     *
     * @param producer the producing unit
     * @param point    receives the rally point
     * @return {@code true} if the producer has a rally point and it was written, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean getRallyPoint(@NotNull Unit producer, @NotNull MutablePoint point) {
        int index = producer.getIndex();

        if (!requireAttached(this.rallyPointsSet).get(index)) {
            return false;
        }

        point.set(requireAttached(this.rallyPointsX).get(index), requireAttached(this.rallyPointsY).get(index));

        return true;
    }

    /**
     * Creates a completed unit of a type for a player at the free position nearest to a point: the hit points of the type after the
     * {@link Stat#HIT_POINTS} modifiers, every damage layer of the type full, the motion profile and speed of the type, standing at its
     * position; the owner's unit counts, the supply the type provides and the supply it requires grow, the unit enters the spatial index, gets
     * its idle order and is announced as completed ({@link org.fogofwarlabs.sc4j.engine.lifecycle.UnitLifecycle#notifyCompleted}). The free
     * position comes from the collision system, if the game has one.
     *
     * @param owner            the owner
     * @param type             the unit type
     * @param x                the x coordinate of the point in pixels
     * @param y                the y coordinate of the point in pixels
     * @param instructionLimit the instruction limit of each animation program run the creation starts
     * @return the created unit, or {@code null} if the unit pool is full or no free position was found
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable Unit createUnit(@NotNull Player owner, @NotNull UnitType type, int x, int y, int instructionLimit) {
        Unit unit = this.spawnUnit(owner, type, x, y, true, instructionLimit);

        if (unit != null) {
            this.addSupplyUsed(owner, type, 1);
        }

        return unit;
    }

    /**
     * Returns the width of a building's footprint: its placement width, or the width of its collision bounds when it has none.
     *
     * @param type the unit type
     * @return the width in pixels
     * @throws IllegalStateException if the system is not attached
     */
    public int getFootprintWidth(@NotNull UnitType type) {
        UnitTypeTable unitTypes = requireAttached(this.content).getUnitTypes();
        int width = unitTypes.getPlacementWidth(type);

        return width > 0 ? width : unitTypes.getCollisionLeft(type) + unitTypes.getCollisionRight(type) + 1;
    }

    /**
     * Returns the height of a building's footprint: its placement height, or the height of its collision bounds when it has none.
     *
     * @param type the unit type
     * @return the height in pixels
     * @throws IllegalStateException if the system is not attached
     */
    public int getFootprintHeight(@NotNull UnitType type) {
        UnitTypeTable unitTypes = requireAttached(this.content).getUnitTypes();
        int height = unitTypes.getPlacementHeight(type);

        return height > 0 ? height : unitTypes.getCollisionUp(type) + unitTypes.getCollisionDown(type) + 1;
    }

    /**
     * Checks whether a building can be placed with its footprint centered on a point: every tile under the footprint is buildable on the terrain
     * and no unit that cannot be pushed aside overlaps the footprint (with a spatial index, by its bounds; without one, by its position).
     *
     * @param type the building type
     * @param x    the x coordinate of the center in pixels
     * @param y    the y coordinate of the center in pixels
     * @return {@code true} if the building can be placed there, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean canPlace(@NotNull UnitType type, int x, int y) {
        int left = x - this.getFootprintWidth(type) / HALF;
        int top = y - this.getFootprintHeight(type) / HALF;
        int right = left + this.getFootprintWidth(type) - 1;
        int bottom = top + this.getFootprintHeight(type) - 1;
        TerrainGrid attachedTerrain = this.terrain;

        if (attachedTerrain != null) {
            GridTopology tiles = attachedTerrain.getTileGrid();

            for (int tileY = tiles.findRow(left, top); tileY <= tiles.findRow(right, bottom); tileY++) {
                for (int tileX = tiles.findColumn(left, top); tileX <= tiles.findColumn(right, bottom); tileX++) {
                    if (!attachedTerrain.isBuildable(tileX, tileY)) {
                        return false;
                    }
                }
            }
        }

        Game attachedGame = requireAttached(this.game);

        if (attachedGame.hasSystem(SpatialIndex.KEY)) {
            attachedGame.getSpatialIndex().findInRectangle(left, top, right, bottom, this.placementFilter, this.placementBlockers);

            return this.placementBlockers.isEmpty();
        }

        EntityPool<Unit> pool = requireAttached(this.units);
        SlotList used = pool.getUsedSlots();

        for (int index = used.getFirst(); index != SlotList.NONE; index = used.getNext(index)) {
            Unit unit = pool.get(index);

            if (unit.getPositionX() >= left && unit.getPositionX() <= right && unit.getPositionY() >= top && unit.getPositionY() <= bottom &&
                this.isPlacementBlocker(unit)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks whether a unit keeps a building from being placed over it ({@link #canPlace}): a building, a resource container or an immovable
     * unit, which cannot be pushed aside.
     *
     * @param unit the unit
     * @return {@code true} if the unit blocks a building's placement, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean isPlacementBlocker(@NotNull Unit unit) {
        UnitType type = unit.getType();

        if (type == null) {
            return false;
        }

        UnitTypeTable unitTypes = requireAttached(this.content).getUnitTypes();

        return unitTypes.hasTrait(type, UnitTrait.BUILDING) || unitTypes.hasTrait(type, UnitTrait.RESOURCE_CONTAINER) || unit.isImmovable();
    }

    /**
     * Checks whether a worker is close enough to a building site to place the building: the worker's position is at most the reach away from the
     * footprint on both axes.
     *
     * @param worker the worker
     * @param type   the building type
     * @param x      the x coordinate of the site's center in pixels
     * @param y      the y coordinate of the site's center in pixels
     * @param reach  the largest distance in pixels
     * @return {@code true} if the worker is within the reach of the footprint, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean isNearSite(@NotNull Unit worker, @NotNull UnitType type, int x, int y, int reach) {
        int left = x - this.getFootprintWidth(type) / HALF;
        int top = y - this.getFootprintHeight(type) / HALF;
        int right = left + this.getFootprintWidth(type) - 1;
        int bottom = top + this.getFootprintHeight(type) - 1;
        int distanceX = Math.max(0, Math.max(left - worker.getPositionX(), worker.getPositionX() - right));
        int distanceY = Math.max(0, Math.max(top - worker.getPositionY(), worker.getPositionY() - bottom));

        return distanceX <= reach && distanceY <= reach;
    }

    /**
     * Orders a worker to construct a building at a site: the worker goes there and places the building. The default has no construction.
     * <p>
     * The standard system uses the build order of the settings ({@link Builder#setBuildOrder}): the type becomes the worker's construction type
     * and the worker gets the build order on the site, whose behavior ({@link BuildBehavior}) places it.
     *
     * @param worker           the worker
     * @param type             the building type
     * @param x                the x coordinate of the site's center in pixels
     * @param y                the y coordinate of the site's center in pixels
     * @param instructionLimit the instruction limit of each animation program run the order starts
     * @return {@code true} if the worker got the order, {@code false} if the settings have no build order, the game has no order system or the worker has no owner
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean orderConstruction(@NotNull Unit worker, @NotNull UnitType type, int x, int y, int instructionLimit) {
        OrderType order = this.buildOrder;
        Game attachedGame = requireAttached(this.game);

        if (order == null || worker.getOwner() == null || !attachedGame.hasSystem(OrderSystem.KEY)) {
            return false;
        }

        attachedGame.getOrders().issue(worker, order, x, y, null, instructionLimit);
        requireAttached(this.constructionTypes).set(worker.getIndex(), type.getIndex() + 1);
        requireAttached(this.constructedBuildings).set(worker.getIndex(), NONE);

        return true;
    }

    /**
     * Returns the building type a worker is ordered to construct.
     *
     * @param worker the worker
     * @return the building type, or {@code null} for none
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable UnitType getConstructionType(@NotNull Unit worker) {
        int value = requireAttached(this.constructionTypes).get(worker.getIndex());

        return value != NONE ? this.getUnitTypes().get(value - 1) : null;
    }

    /**
     * Returns the building a worker constructs.
     *
     * @param worker the worker
     * @return the building, or {@code null} if the worker has placed none or it left the game
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable Unit getConstructedBuilding(@NotNull Unit worker) {
        int handle = requireAttached(this.constructedBuildings).get(worker.getIndex());

        return handle != NONE ? requireAttached(this.units).resolveHandle(handle) : null;
    }

    /**
     * Sets the building a worker constructs.
     *
     * @param worker   the worker
     * @param building the building
     * @throws IllegalStateException if the system is not attached
     */
    public void setConstructedBuilding(@NotNull Unit worker, @NotNull Unit building) {
        requireAttached(this.constructedBuildings).set(worker.getIndex(), requireAttached(this.units).createHandle(building));
    }

    /**
     * Clears the construction type and the constructed building of a worker.
     *
     * @param worker the worker
     * @throws IllegalStateException if the system is not attached
     */
    public void clearConstruction(@NotNull Unit worker) {
        requireAttached(this.constructionTypes).set(worker.getIndex(), NONE);
        requireAttached(this.constructedBuildings).set(worker.getIndex(), NONE);
    }

    /**
     * Places a building under construction for a worker's owner, centered on a point, as the class description says. No building is placed
     * when the worker has no owner, the requirement system does not allow it, the site is not free, the owner lacks the supply or the cost, or
     * the unit pool is full.
     *
     * @param worker           the worker, the actor of the requirement check
     * @param type             the building type
     * @param x                the x coordinate of the center in pixels
     * @param y                the y coordinate of the center in pixels
     * @param instructionLimit the instruction limit of each animation program run the placement starts
     * @return the building, or {@code null} if no building is placed
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable Unit placeBuilding(@NotNull Unit worker, @NotNull UnitType type, int x, int y, int instructionLimit) {
        Player owner = worker.getOwner();

        if (owner == null || !this.isAllowed(RequirementAction.BUILD_UNIT, type, owner, worker) || !this.canPlace(type, x, y) ||
            !this.hasSupply(owner, type)) {
            return null;
        }

        this.fillUnitCost(type);

        if (!this.spendCost(owner)) {
            return null;
        }

        Unit building = this.spawnUnit(owner, type, x, y, false, instructionLimit);

        if (building == null) {
            this.refundCost(owner, PERCENT);

            return null;
        }

        this.addSupplyUsed(owner, type, 1);

        return building;
    }

    /**
     * Cancels the construction of a building under construction: the owner gets a share of its cost back and the building leaves the game. The
     * default has no construction.
     * <p>
     * The standard system refunds the construction refund share of the cost ({@link Builder#setConstructionRefundPercent}) and takes the
     * building out of the game, not as a death ({@link UnitAccountingUtils#removeUnit}).
     *
     * @param building         the building
     * @param instructionLimit the instruction limit of each animation program run the cancellation starts
     * @return {@code true} if the construction was cancelled, {@code false} if the unit is not under construction
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean cancelConstruction(@NotNull Unit building, int instructionLimit) {
        Player owner = building.getOwner();
        UnitType type = building.getType();

        if (!this.isUnderConstruction(building) || type == null) {
            return false;
        }

        if (owner != null) {
            this.fillUnitCost(type);
            this.refundCost(owner, this.constructionRefundPercent);
        }

        UnitAccountingUtils.removeUnit(requireAttached(this.game), building, false);

        return true;
    }

    /**
     * Checks whether a unit is a building under construction: it exists but is not complete, so it neither produces nor counts as completed.
     * The default has no construction.
     * <p>
     * The standard production system has a building under construction from its placement until its build time has passed.
     *
     * @param unit the unit
     * @return {@code true} if the unit is under construction, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean isUnderConstruction(@NotNull Unit unit) {
        return requireAttached(this.constructionRemainingTimes).get(unit.getIndex()) > 0;
    }

    /**
     * Returns the frames a building under construction still needs.
     *
     * @param building the building
     * @return the remaining frames, {@code 0} for a complete unit
     * @throws IllegalStateException if the system is not attached
     */
    public int getConstructionRemainingTime(@NotNull Unit building) {
        return requireAttached(this.constructionRemainingTimes).get(building.getIndex());
    }

    /**
     * Creates a unit, complete or under construction; a complete unit gets the supply it provides, its idle order and the completed
     * announcement, a building under construction its construction time and the start share of its hit points. A unit under construction is
     * placed where it is asked, a complete one at the free position the collision system finds.
     */
    private @Nullable Unit spawnUnit(@NotNull Player owner, @NotNull UnitType type, int x, int y, boolean completed, int instructionLimit) {
        Game attachedGame = requireAttached(this.game);
        GameContent attachedContent = requireAttached(this.content);
        EntityPool<Unit> pool = requireAttached(this.units);
        UnitTypeTable unitTypes = attachedContent.getUnitTypes();
        Unit unit = pool.allocate();

        if (unit == null) {
            return null;
        }

        unit.setOwner(owner);
        unit.setType(type);
        unit.resetMotion();
        unit.resetOrders();
        unit.setMotionProfile(unitTypes.getMotionProfile(type));
        unit.setPosition(x, y);

        if (completed && attachedGame.hasSystem(CollisionSystem.KEY)) {
            if (!attachedGame.getCollision().findFreePosition(unit, x, y, this.freePosition)) {
                unit.setOwner(null);
                pool.free(unit);

                return null;
            }

            unit.setPosition(this.freePosition.getX(), this.freePosition.getY());
        }

        int positionX = unit.getPositionX();
        int positionY = unit.getPositionY();
        int hitPoints = attachedContent.applyModifiers(Stat.HIT_POINTS, owner, type, null, unitTypes.getHitPoints(type));
        List<DamageLayer> layers = attachedContent.getDamageLayers(type);

        unit.setFixedPositionX(positionX << MotionModel.FRACTION_BITS);
        unit.setFixedPositionY(positionY << MotionModel.FRACTION_BITS);
        unit.setMovementDestinationX(positionX);
        unit.setMovementDestinationY(positionY);
        unit.setMovementWaypointX(positionX);
        unit.setMovementWaypointY(positionY);
        unit.setDirectionTargetX(positionX);
        unit.setDirectionTargetY(positionY);
        unit.setHitPoints((completed ? hitPoints : hitPoints * this.constructionStartPercent / PERCENT) << StandardCombatSystem.FRACTION_BITS);

        for (int index = 0; index < layers.size(); index++) {
            layers.get(index).setPoints(unit, layers.get(index).getMaximumPoints(unit));
        }

        PlayerProgressTable progress = attachedContent.getProgress();

        progress.setUnitCount(owner, type, progress.getUnitCount(owner, type) + 1);

        if (!completed) {
            requireAttached(this.constructionRemainingTimes).set(unit.getIndex(), Math.max(MINIMUM_TIME, unitTypes.getBuildTime(type)));
        }

        if (attachedGame.hasSystem(MovementSystem.KEY)) {
            attachedGame.getMovement().recalculateSpeed(unit, instructionLimit);
        }

        if (attachedGame.hasSystem(SpatialIndex.KEY)) {
            attachedGame.getSpatialIndex().insert(unit);
        }

        if (completed) {
            this.complete(unit, instructionLimit);
        }

        return unit;
    }

    /**
     * Completes a unit: its completed count and the supply it provides grow, it gets its idle order and is announced as completed.
     */
    private void complete(@NotNull Unit unit, int instructionLimit) {
        Game attachedGame = requireAttached(this.game);
        GameContent attachedContent = requireAttached(this.content);
        UnitTypeTable unitTypes = attachedContent.getUnitTypes();
        PlayerProgressTable progress = attachedContent.getProgress();
        Player owner = unit.getOwner();
        UnitType type = unit.getType();

        if (owner == null || type == null) {
            return;
        }

        Faction faction = unitTypes.getFaction(type);

        progress.setCompletedUnitCount(owner, type, progress.getCompletedUnitCount(owner, type) + 1);

        if (faction != null && attachedGame.hasSystem(EconomySystem.KEY)) {
            attachedGame.getEconomy().addSupplyProvided(owner, faction, unitTypes.getSupplyProvided(type));
        }

        if (attachedGame.hasSystem(OrderSystem.KEY)) {
            attachedGame.getOrders().finishOrder(unit, instructionLimit);
        }

        attachedGame.getUnitLifecycle().notifyCompleted(unit);
    }

    /**
     * Advances the construction of a building under construction by one frame: its hit points grow by an equal share of what the start share
     * left out, at least one fixed-point unit, up to its full hit points, and it is complete when the time has passed.
     */
    private void updateConstruction(@NotNull Unit building) {
        IntColumn remainingTimes = requireAttached(this.constructionRemainingTimes);
        int index = building.getIndex();
        int remaining = remainingTimes.get(index);
        Player owner = building.getOwner();
        UnitType type = building.getType();

        if (remaining <= 0 || owner == null || type == null) {
            return;
        }

        GameContent attachedContent = requireAttached(this.content);
        UnitTypeTable unitTypes = attachedContent.getUnitTypes();
        int fullHitPoints = attachedContent.applyModifiers(Stat.HIT_POINTS, owner, type, null, unitTypes.getHitPoints(type)) <<
                            StandardCombatSystem.FRACTION_BITS;
        int buildTime = Math.max(MINIMUM_TIME, unitTypes.getBuildTime(type));
        int step = Math.max(1, (fullHitPoints - fullHitPoints * this.constructionStartPercent / PERCENT) / buildTime);

        building.setHitPoints(Math.min(fullHitPoints, building.getHitPoints() + step));
        remainingTimes.set(index, remaining - 1);

        if (remaining == 1) {
            this.complete(building, this.phaseInstructionLimit);
        }
    }

    /**
     * Runs this phase of the current frame.
     * <p>
     * Advances the construction, the queue and the research of every unit in unit slot order.
     *
     * @param game the game
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void run(@NotNull Game game) {
        EntityPool<Unit> pool = requireAttached(this.units);
        SlotList used = pool.getUsedSlots();
        int index = used.getFirst();

        while (index != SlotList.NONE) {
            int next = used.getNext(index);
            Unit producer = pool.get(index);

            if (this.isUnderConstruction(producer)) {
                this.updateConstruction(producer);
            } else {
                this.updateQueue(producer);
                this.updateResearch(producer);
            }

            index = next;
        }
    }

    private void updateQueue(@NotNull Unit producer) {
        int index = producer.getIndex();
        Player owner = producer.getOwner();

        if (owner == null || requireAttached(this.lengths).get(index) == 0) {
            return;
        }

        BooleanColumn startedEntries = requireAttached(this.started);
        IntColumn remaining = requireAttached(this.remainingTimes);
        UnitType type = this.getQueuedType(producer, 0);

        if (!startedEntries.get(index)) {
            if (!this.supplyHeldWhenQueued) {
                if (!this.hasSupply(owner, type)) {
                    return;
                }

                this.addSupplyUsed(owner, type, 1);
            }

            startedEntries.set(index, true);
            remaining.set(index, Math.max(MINIMUM_TIME, requireAttached(this.content).getUnitTypes().getBuildTime(type)));
        }

        if (remaining.get(index) > 0) {
            remaining.set(index, remaining.get(index) - 1);
        }

        if (remaining.get(index) != 0) {
            return;
        }

        Unit unit = this.spawnUnit(owner, type, producer.getPositionX(), producer.getPositionY(), true, this.phaseInstructionLimit);

        if (unit == null) {
            return;
        }

        HookList<QueueSlotClearedHook> hooks = requireAttached(this.slotClearedHooks);

        startedEntries.set(index, false);

        for (int hook = 0; hook < hooks.size(); hook++) {
            hooks.get(hook).onQueueSlotCleared(producer, 0);
        }

        this.removeEntry(producer, 0);

        OrderType order = this.rallyOrder;
        Game attachedGame = requireAttached(this.game);

        if (order != null && requireAttached(this.rallyPointsSet).get(index) && attachedGame.hasSystem(OrderSystem.KEY)) {
            attachedGame.getOrders().issue(unit, order,
                                           requireAttached(this.rallyPointsX).get(index), requireAttached(this.rallyPointsY).get(index), null,
                                           this.phaseInstructionLimit);
        }
    }

    private void updateResearch(@NotNull Unit producer) {
        int index = producer.getIndex();
        Player owner = producer.getOwner();
        IntColumn remaining = requireAttached(this.researchRemainingTimes);

        if (owner == null || !this.isResearching(producer)) {
            return;
        }

        remaining.set(index, remaining.get(index) - 1);

        if (remaining.get(index) > 0) {
            return;
        }

        GameContent attachedContent = requireAttached(this.content);
        PlayerProgressTable progress = attachedContent.getProgress();
        IntColumn techs = requireAttached(this.researchTechs);
        IntColumn upgrades = requireAttached(this.researchUpgrades);

        if (techs.get(index) != NONE) {
            progress.setTechResearched(owner, attachedContent.getDefinition().getTechTypes().getTypes().get(techs.get(index) - 1), true);
        } else {
            UpgradeType upgrade = attachedContent.getDefinition().getUpgradeTypes().getTypes().get(upgrades.get(index) - 1);

            progress.setUpgradeLevel(owner, upgrade, progress.getUpgradeLevel(owner, upgrade) + 1);
        }

        techs.set(index, NONE);
        upgrades.set(index, NONE);
        remaining.set(index, 0);
    }

    private void removeEntry(@NotNull Unit producer, int slot) {
        IntColumn queueLengths = requireAttached(this.lengths);
        HookList<QueueSlotMovedHook> hooks = requireAttached(this.slotMovedHooks);
        int index = producer.getIndex();
        int length = queueLengths.get(index);

        for (int moved = slot + 1; moved < length; moved++) {
            this.entries[moved - 1].set(index, this.entries[moved].get(index));

            for (int hook = 0; hook < hooks.size(); hook++) {
                hooks.get(hook).onQueueSlotMoved(producer, moved - 1, moved);
            }
        }

        this.entries[length - 1].set(index, NONE);
        queueLengths.set(index, length - 1);
    }

    private boolean isAllowed(@NotNull RequirementAction action, @NotNull ContentType type, @NotNull Player owner, @NotNull Unit producer) {
        Game attachedGame = requireAttached(this.game);

        return !attachedGame.hasSystem(RequirementSystem.KEY) || attachedGame.getRequirements().check(action, type, owner, producer).isMet();
    }

    /**
     * Checks whether a queue entry holds the supply of its unit type: every entry when the supply is held when queued, else a started front entry.
     */
    private boolean holdsSupply(@NotNull Unit producer, int slot) {
        return this.supplyHeldWhenQueued || slot == 0 && requireAttached(this.started).get(producer.getIndex());
    }

    private boolean hasSupply(@NotNull Player owner, @NotNull UnitType type) {
        Game attachedGame = requireAttached(this.game);
        UnitTypeTable unitTypes = requireAttached(this.content).getUnitTypes();
        Faction faction = unitTypes.getFaction(type);
        int required = unitTypes.getSupplyRequired(type);

        if (faction == null || required <= 0 || !attachedGame.hasSystem(EconomySystem.KEY)) {
            return true;
        }

        EconomySystem economy = attachedGame.getEconomy();

        return economy.getSupplyUsed(owner, faction) + required <= economy.getSupplyProvided(owner, faction);
    }

    private void addSupplyUsed(@NotNull Player owner, @NotNull UnitType type, int sign) {
        Game attachedGame = requireAttached(this.game);
        UnitTypeTable unitTypes = requireAttached(this.content).getUnitTypes();
        Faction faction = unitTypes.getFaction(type);

        if (faction != null && attachedGame.hasSystem(EconomySystem.KEY)) {
            attachedGame.getEconomy().addSupplyUsed(owner, faction, sign * unitTypes.getSupplyRequired(type));
        }
    }

    private void fillUnitCost(@NotNull UnitType type) {
        UnitTypeTable unitTypes = requireAttached(this.content).getUnitTypes();
        List<ResourceKind> kinds = requireAttached(this.resourceKinds);

        for (int kind = 0; kind < kinds.size(); kind++) {
            this.cost[kind] = unitTypes.getCost(type, kinds.get(kind));
        }
    }

    /**
     * Takes the filled cost from the owner if the owner holds every amount of it; a game without economy system costs nothing.
     */
    private boolean spendCost(@NotNull Player owner) {
        Game attachedGame = requireAttached(this.game);

        if (!attachedGame.hasSystem(EconomySystem.KEY)) {
            return true;
        }

        EconomySystem economy = attachedGame.getEconomy();
        List<ResourceKind> kinds = requireAttached(this.resourceKinds);

        for (int kind = 0; kind < kinds.size(); kind++) {
            if (economy.getAmount(owner, kinds.get(kind)) < this.cost[kind]) {
                return false;
            }
        }

        for (int kind = 0; kind < kinds.size(); kind++) {
            economy.setAmount(owner, kinds.get(kind), economy.getAmount(owner, kinds.get(kind)) - this.cost[kind]);
        }

        return true;
    }

    private void refundCost(@NotNull Player owner, int percent) {
        Game attachedGame = requireAttached(this.game);

        if (!attachedGame.hasSystem(EconomySystem.KEY)) {
            return;
        }

        EconomySystem economy = attachedGame.getEconomy();
        List<ResourceKind> kinds = requireAttached(this.resourceKinds);

        for (int kind = 0; kind < kinds.size(); kind++) {
            economy.setAmount(owner, kinds.get(kind), economy.getAmount(owner, kinds.get(kind)) + this.cost[kind] * percent / PERCENT);
        }
    }

    private @NotNull TypeRegistry<UnitType> getUnitTypes() {
        return requireAttached(this.content).getDefinition().getUnitTypes().getTypes();
    }
}
