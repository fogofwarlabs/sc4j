package org.fogofwarlabs.sc4j.engine.collision;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.entity.pool.SlotList;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.frame.FramePhase;
import org.fogofwarlabs.sc4j.engine.hook.HookList;
import org.fogofwarlabs.sc4j.engine.map.grid.GridTopology;
import org.fogofwarlabs.sc4j.engine.math.IntegerMathUtils;
import org.fogofwarlabs.sc4j.engine.math.MutablePoint;
import org.fogofwarlabs.sc4j.engine.movement.MovementSystem;
import org.fogofwarlabs.sc4j.engine.spatial.SpatialIndex;
import org.fogofwarlabs.sc4j.engine.spatial.UnitBounds;
import org.fogofwarlabs.sc4j.engine.spatial.UnitFilter;
import org.fogofwarlabs.sc4j.engine.spatial.UnitList;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.StateHashContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.fogofwarlabs.sc4j.engine.terrain.TerrainGrid;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Keeps the units of the game apart over the game's spatial index and movement system: the engine default {@link CollisionSystem}. A mod
 * registers it under {@link CollisionSystem#KEY} with the bounds of its units, the largest distance a free position is searched at, the
 * terrain its ground units stand on and the distance overlapping units are pushed apart per frame, and lists it as a frame phase after the
 * movement.
 * {@snippet :
 * builder.useSystem(CollisionSystem.KEY, context -> new StandardCollisionSystem(bounds, searchDistance, terrain, pushDistance))
 *        .addFramePhase(CollisionSystem.KEY, collision -> (StandardCollisionSystem) collision);
 *}
 * <p>
 * The general blocking rule: a placed unit other than the moving unit collides with it; the {@link CollisionSystem#BLOCKING} hooks then decide
 * in registration order, and without an answer the other unit blocks. The free position search tries the requested position, then the
 * positions on square rings around it, one unit size apart, ring by ring up to the search distance; each ring top row left to right, bottom
 * row, then the left and right columns top to bottom. A position is free when no unit that blocks the unit overlaps its bounds there and, over
 * a terrain, every walk cell under its bounds is walkable.
 * <p>
 * The phase separates overlapping ground units, which is also how moving units avoid each other locally: they slide off what they run into. A
 * unit is pushable when it is placed, can move (top speed above {@code 0}), is not immovable and its type is neither a
 * {@link UnitTrait#BUILDING}, a {@link UnitTrait#RESOURCE_CONTAINER} nor a {@link UnitTrait#FLYER}. For every pushable unit in slot order, the
 * ground units that block it and overlap its bounds (in spatial index order, at most {@value #MAXIMUM_CONTACTS}) are pushed apart along the axis
 * of the smaller overlap, by at most the push distance per frame: a unit that cannot be pushed stays and the pushable one takes the whole push;
 * two idle units (ones that stand at their destinations) that overlap by at most {@value #IDLE_OVERLAP_TOLERANCE} pixels along the axis are
 * not pushed, so that a settled crowd stands still; of two pushable units an idle unit gives way to a moving one, of two moving units that go
 * the same way the one behind (whose velocity points at the other) gives way, so that a column queues through a narrow gap, and otherwise each
 * takes half, the unit of the lower slot the larger half. A pair of pushable units is resolved once, from its lower slot. The push goes away
 * from the other unit's position, for equal positions towards the lower coordinate for the lower slot.
 * <p>
 * A moving unit that runs head-on (its velocity along that axis points at the other unit) into a unit that cannot be pushed, into an idle unit
 * or into a moving unit that comes the other way also steers around it: it gives back its move towards the other unit along the axis in this
 * frame and moves by the push distance across the axis. Around a unit that cannot be pushed or an idle unit it goes the way it slid in the
 * previous frame if it slid across this axis then, otherwise the way its velocity goes across the axis, or for a velocity straight along it
 * away from the other unit's position by the same tie rule; it keeps that side while it keeps running into one across the same axis, so that it
 * does not turn back into the obstacle. Two moving units that meet each go to their right-hand side, so that they pass.
 * <p>
 * Every push is bounded by the pushed unit's speed: over the frame, a unit's position stays within its top speed (at least one pixel) of where
 * it stood when the previous phase ended, its own motion step included; a push that would carry it farther is shortened, and a push back
 * towards that point is never shortened. So no crowd, wall or structure speeds a unit up. A push does not happen that would put the unit's
 * position on an unwalkable walk cell of the terrain, or move it towards a unit that cannot be pushed whose bounds it would then overlap
 * (other than the one it is pushed away from). Before its pushes, a pushable unit whose position lies on an unwalkable walk cell (a straight
 * step cut a corner) is moved to the center of the nearest walkable cell. A pushed idle unit stops where it is pushed to
 * ({@link MovementSystem#stop}), so it does not walk back.
 * <p>
 * Determinism contract: the operations and the phase draw no random value and allocate nothing; candidates are visited in spatial index order,
 * pushed units in slot order. The remembered slides and the positions of the previous phase end, in slot order, are mixed into the state hash.
 */
public final class StandardCollisionSystem implements CollisionSystem, FramePhase, StateHashContributor, SnapshotContributor {
    /**
     * The distance in pixels overlapping units are pushed apart per frame when the mod names none.
     */
    public static final int DEFAULT_PUSH_DISTANCE = 4;
    /**
     * The overlap in pixels along the push axis that two idle units tolerate: an idle pair that overlaps by no more is not pushed apart, so that a
     * settled crowd stands still instead of pushing its last pixels to and fro.
     */
    public static final int IDLE_OVERLAP_TOLERANCE = 2;

    /**
     * The largest number of overlapping units one unit is pushed apart from in a frame.
     */
    private static final int MAXIMUM_CONTACTS = 16;
    /**
     * The divisor that splits a push between two pushable units.
     */
    private static final int PUSH_SHARE_DIVISOR = 2;
    /**
     * The largest ring of walk cells a unit on an unwalkable cell is moved out to.
     */
    private static final int MAXIMUM_ESCAPE_RING = 8;
    /**
     * The remembered slide of a unit that did not slide around a unit that cannot be pushed in the last frame.
     */
    private static final int NO_SLIDE = 0;
    /**
     * The magnitude of a remembered slide along the vertical axis.
     */
    private static final int SLIDE_VERTICAL = 1;
    /**
     * The magnitude of a remembered slide along the horizontal axis.
     */
    private static final int SLIDE_HORIZONTAL = 2;
    /**
     * The fraction bits of a speed.
     */
    private static final int SPEED_FRACTION_BITS = 8;
    /**
     * The smallest speed a push bound allows, one pixel per frame.
     */
    private static final int MINIMUM_PUSH_SPEED = 1 << SPEED_FRACTION_BITS;
    /**
     * The pixels a unit may stand away from its position at the previous phase end, beyond its top speed, before that position no longer bounds
     * its pushes (the unit was put elsewhere).
     */
    private static final int RELOCATION_MARGIN = 2;

    private static <T> @NotNull T requireAttached(@Nullable T value) {
        if (value == null) {
            throw Exceptions.newIllegalStateException("The standard collision system is not attached.");
        }

        return value;
    }


    private final @NotNull UnitBounds<Unit> bounds;
    private final int searchDistance;
    private final @Nullable TerrainGrid terrain;
    private final int pushDistance;

    private final @NotNull UnitFilter blockerFilter;
    private final @NotNull UnitFilter contactFilter;
    private final @NotNull UnitFilter structureFilter;
    private final @NotNull UnitList candidates;
    private final @NotNull UnitList contacts;
    private final @NotNull UnitList structures;

    private @Nullable HookList<BlockingHook> blockingHooks;
    private @Nullable SpatialIndex spatialIndex;
    private @Nullable MovementSystem movement;
    private @Nullable EntityPool<Unit> units;
    private @Nullable UnitTypeTable unitTypes;

    private @Nullable Unit searchedUnit;
    private @Nullable Unit pushSource;
    private int pushChangeX;
    private int pushChangeY;

    private int @NotNull [] slides;
    private int @NotNull [] originX;
    private int @NotNull [] originY;

    /**
     * Creates a {@link StandardCollisionSystem} instance without terrain; it serves nothing until it is attached.
     *
     * @param bounds         reads the bounds of a unit
     * @param searchDistance the largest distance in pixels from the requested position at which a free position is searched
     */
    public StandardCollisionSystem(@NotNull UnitBounds<Unit> bounds, int searchDistance) {
        this(bounds, searchDistance, null);
    }

    /**
     * Creates a {@link StandardCollisionSystem} instance that pushes units apart by {@link #DEFAULT_PUSH_DISTANCE}; it serves nothing until it is
     * attached.
     *
     * @param bounds         reads the bounds of a unit
     * @param searchDistance the largest distance in pixels from the requested position at which a free position is searched
     * @param terrain        the terrain a free position must be walkable on, or {@code null} for none
     */
    public StandardCollisionSystem(@NotNull UnitBounds<Unit> bounds, int searchDistance, @Nullable TerrainGrid terrain) {
        this(bounds, searchDistance, terrain, DEFAULT_PUSH_DISTANCE);
    }

    /**
     * Creates a {@link StandardCollisionSystem} instance; it serves nothing until it is attached.
     *
     * @param bounds         reads the bounds of a unit
     * @param searchDistance the largest distance in pixels from the requested position at which a free position is searched
     * @param terrain        the terrain a free position must be walkable on, or {@code null} for none
     * @param pushDistance   the largest distance in pixels overlapping units are pushed apart per frame, at least {@code 1}
     * @throws IllegalArgumentException if the push distance is below {@code 1}
     */
    public StandardCollisionSystem(@NotNull UnitBounds<Unit> bounds, int searchDistance, @Nullable TerrainGrid terrain, int pushDistance) {
        if (pushDistance < 1) {
            throw Exceptions.newIllegalArgumentException("The push distance {0} is below 1.", pushDistance);
        }

        this.bounds         = bounds;
        this.searchDistance = searchDistance;
        this.terrain        = terrain;
        this.pushDistance   = pushDistance;

        this.blockerFilter   = this::isSearchBlocker;
        this.contactFilter   = this::isContact;
        this.structureFilter = this::isStructureInPushWay;
        this.candidates      = new UnitList(1);
        this.contacts        = new UnitList(MAXIMUM_CONTACTS);
        this.structures      = new UnitList(1);

        this.blockingHooks = null;
        this.spatialIndex  = null;
        this.movement      = null;
        this.units         = null;
        this.unitTypes     = null;

        this.searchedUnit = null;
        this.pushSource   = null;
        this.pushChangeX  = 0;
        this.pushChangeY  = 0;

        this.slides  = new int[0];
        this.originX = new int[0];
        this.originY = new int[0];
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the {@link CollisionSystem#BLOCKING} hooks, the spatial index, the movement system, the unit pool and the unit type table, and holds
     * the remembered slide and the position at the previous phase end of each pool slot.
     *
     * @param game the game
     */
    @Override
    public void attach(@NotNull Game game) {
        int slotCount = game.getUnits().getCapacity() + 1;

        this.blockingHooks = game.getHooks(CollisionSystem.BLOCKING);
        this.spatialIndex  = game.getSpatialIndex();
        this.movement      = game.getMovement();
        this.units         = game.getUnits();
        this.unitTypes     = game.getContent().getUnitTypes();

        this.slides  = new int[slotCount];
        this.originX = new int[slotCount];
        this.originY = new int[slotCount];
    }

    /**
     * Checks whether one unit blocks the movement of another.
     *
     * @param unit      the moving unit
     * @param otherUnit the unit in the way
     * @return {@code true} if the other unit blocks the moving unit, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean isBlocking(@NotNull Unit unit, @NotNull Unit otherUnit) {
        HookList<BlockingHook> hooks = requireAttached(this.blockingHooks);

        if (otherUnit == unit || !this.bounds.isPlaced(otherUnit)) {
            return false;
        }

        for (int index = 0; index < hooks.size(); index++) {
            CollisionAnswer answer = hooks.get(index).answerBlocking(unit, otherUnit);

            if (answer != CollisionAnswer.NONE) {
                return answer == CollisionAnswer.BLOCKS;
            }
        }

        return true;
    }

    /**
     * Finds a free position for a unit near a position, within the search area of the implementation: the requested
     * position itself if the unit can stand there, else the nearest free one the search meets.
     *
     * @param unit     the unit to put down
     * @param x        the x coordinate to search around
     * @param y        the y coordinate to search around
     * @param position receives the found position
     * @return {@code true} if a free position was found and written, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean findFreePosition(@NotNull Unit unit, int x, int y, @NotNull MutablePoint position) {
        SpatialIndex index = requireAttached(this.spatialIndex);
        int stepX = Math.max(this.bounds.getWidth(unit), 1);
        int stepY = Math.max(this.bounds.getHeight(unit), 1);
        int ringCount = this.searchDistance / Math.min(stepX, stepY);

        this.searchedUnit = unit;

        try {
            for (int ring = 0; ring <= ringCount; ring++) {
                if (this.findOnRing(index, unit, x, y, ring, stepX, stepY, position)) {
                    return true;
                }
            }

            return false;
        } finally {
            this.searchedUnit = null;
        }
    }

    /**
     * Makes a unit immovable for other units: pushing does not move it until it moves again by itself.
     * <p>
     * The unit stops: its next speed becomes {@code 0}, the movement system stops it (its speed becomes {@code 0}) and sets its destination to
     * its position, then it is marked immovable ({@link Unit#isImmovable()}).
     *
     * @param unit the unit
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void makeImmovable(@NotNull Unit unit) {
        MovementSystem attachedMovement = requireAttached(this.movement);

        unit.setNextSpeed(0);
        attachedMovement.stop(unit);
        attachedMovement.setDestination(unit, unit.getPositionX(), unit.getPositionY());
        unit.setImmovable(true);
    }

    /**
     * Runs this phase of the current frame.
     * <p>
     * Pushes the overlapping ground units apart, as the class description says, then keeps every unit's position as the point that bounds its
     * pushes in the next frame. A unit that stands farther from its kept point than its top speed allows was put there (created, or moved by a
     * rule), so its current position bounds its pushes instead.
     *
     * @param game the game
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void run(@NotNull Game game) {
        EntityPool<Unit> pool = requireAttached(this.units);
        SpatialIndex index = requireAttached(this.spatialIndex);
        SlotList used = pool.getUsedSlots();

        for (int slot = used.getFirst(); slot != SlotList.NONE; slot = used.getNext(slot)) {
            Unit unit = pool.get(slot);
            int reach = (Math.max(unit.getTopSpeed(), MINIMUM_PUSH_SPEED) >> SPEED_FRACTION_BITS) + RELOCATION_MARGIN;

            if (Math.abs(unit.getPositionX() - this.originX[slot]) > reach || Math.abs(unit.getPositionY() - this.originY[slot]) > reach) {
                this.originX[slot] = unit.getPositionX();
                this.originY[slot] = unit.getPositionY();
            }
        }

        for (int slot = used.getFirst(); slot != SlotList.NONE; slot = used.getNext(slot)) {
            Unit unit = pool.get(slot);

            if (this.isPushable(unit)) {
                this.leaveUnwalkableCell(unit);
                this.separate(index, unit);
            }
        }

        for (int slot = used.getFirst(); slot != SlotList.NONE; slot = used.getNext(slot)) {
            Unit unit = pool.get(slot);

            this.originX[slot] = unit.getPositionX();
            this.originY[slot] = unit.getPositionY();
        }
    }

    /**
     * Mixes this part of the simulation state into the hash: the remembered slide of every pool slot in slot order, then the positions at the
     * previous phase end.
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        for (int slide : this.slides) {
            hash.mix(slide);
        }

        hash.mixArray(this.originX);
        hash.mixArray(this.originY);
    }

    /**
     * Writes this part of the simulation state: the slide direction and the position at the previous phase end of every unit slot.
     *
     * @param writer the writer of the snapshot
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        writer.writeInts(this.slides);
        writer.writeInts(this.originX);
        writer.writeInts(this.originY);
    }

    /**
     * Reads this part of the simulation state back, replacing the current values.
     *
     * @param reader the reader of the snapshot
     * @throws IOException if the snapshot ends early or holds another number of values
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        reader.readInts(this.slides);
        reader.readInts(this.originX);
        reader.readInts(this.originY);
    }

    /**
     * Checks whether the phase pushes a unit when it overlaps another.
     *
     * @param unit the unit
     * @return {@code true} if the unit is placed, can move, is not immovable and is no building, resource container or flyer, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean isPushable(@NotNull Unit unit) {
        if (!this.bounds.isPlaced(unit) || unit.isImmovable() || unit.getTopSpeed() == 0) {
            return false;
        }

        UnitType type = unit.getType();

        if (type == null) {
            return true;
        }

        UnitTypeTable table = requireAttached(this.unitTypes);

        return !table.hasTrait(type, UnitTrait.BUILDING) && !table.hasTrait(type, UnitTrait.RESOURCE_CONTAINER) &&
               !table.hasTrait(type, UnitTrait.FLYER);
    }

    private void separate(@NotNull SpatialIndex index, @NotNull Unit unit) {
        UnitList found = this.contacts;
        int slot = unit.getIndex();
        int previousSlide = this.slides[slot];

        this.slides[slot] = NO_SLIDE;

        this.searchedUnit = unit;

        try {
            index.findInRectangle(this.bounds.getLeft(unit), this.bounds.getTop(unit), this.bounds.getRight(unit), this.bounds.getBottom(unit),
                                  this.contactFilter, found);
        } finally {
            this.searchedUnit = null;
        }

        for (int position = 0; position < found.size(); position++) {
            Unit other = found.get(position);
            boolean otherPushable = this.isPushable(other);

            if (otherPushable && other.getIndex() < unit.getIndex()) {
                continue;
            }

            int overlapX = Math.min(this.bounds.getRight(unit), this.bounds.getRight(other)) -
                           Math.max(this.bounds.getLeft(unit), this.bounds.getLeft(other)) + 1;
            int overlapY = Math.min(this.bounds.getBottom(unit), this.bounds.getBottom(other)) -
                           Math.max(this.bounds.getTop(unit), this.bounds.getTop(other)) + 1;

            if (overlapX <= 0 || overlapY <= 0) {
                continue;
            }

            boolean horizontal = overlapX <= overlapY;
            int push = Math.min(horizontal ? overlapX : overlapY, this.pushDistance);
            int unitCenter = horizontal ? unit.getPositionX() : unit.getPositionY();
            int otherCenter = horizontal ? other.getPositionX() : other.getPositionY();
            int sign = unitCenter != otherCenter ? Integer.signum(unitCenter - otherCenter) : unit.getIndex() < other.getIndex() ? -1 : 1;

            boolean unitIdle = this.isIdle(unit);

            if (!otherPushable) {
                this.push(unit, horizontal, sign * push, other);

                int slide = unitIdle ? NO_SLIDE : this.slideAround(unit, other, horizontal, previousSlide);

                if (slide != NO_SLIDE) {
                    this.slides[slot] = slide;
                }

                continue;
            }

            boolean otherIdle = this.isIdle(other);

            if (unitIdle && otherIdle && (horizontal ? overlapX : overlapY) <= IDLE_OVERLAP_TOLERANCE) {
                continue;
            }

            if (unitIdle && !otherIdle) {
                this.push(unit, horizontal, sign * push, other);
                this.slideAround(other, unit, horizontal, NO_SLIDE);
            } else if (otherIdle && !unitIdle) {
                this.push(other, horizontal, -sign * push, unit);

                int slide = this.slideAround(unit, other, horizontal, previousSlide);

                if (slide != NO_SLIDE) {
                    this.slides[slot] = slide;
                }
            } else if (!unitIdle && this.isFollowing(unit, other)) {
                this.push(unit, horizontal, sign * push, other);
            } else if (!unitIdle && this.isFollowing(other, unit)) {
                this.push(other, horizontal, -sign * push, unit);
            } else {
                int otherShare = push / PUSH_SHARE_DIVISOR;

                this.push(unit, horizontal, sign * (push - otherShare), other);
                this.push(other, horizontal, -sign * otherShare, unit);

                if (!unitIdle) {
                    this.passRight(unit, other, horizontal);
                    this.passRight(other, unit, horizontal);
                }
            }
        }
    }

    private boolean isIdle(@NotNull Unit unit) {
        return requireAttached(this.movement).isAtDestination(unit);
    }

    /**
     * Checks whether a moving unit follows another moving unit: both go the same way and the follower's velocity points at the other unit.
     */
    private boolean isFollowing(@NotNull Unit follower, @NotNull Unit leader) {
        long sameWay = (long) follower.getVelocityX() * leader.getVelocityX() + (long) follower.getVelocityY() * leader.getVelocityY();
        long towardsLeader = (long) follower.getVelocityX() * (leader.getPositionX() - follower.getPositionX()) +
                             (long) follower.getVelocityY() * (leader.getPositionY() - follower.getPositionY());

        return sameWay > 0 && towardsLeader > 0;
    }

    /**
     * Steers a moving unit that runs head-on into another unit along the push axis around it, as the class description says: the same way as in
     * the previous frame if it slid across this axis then, otherwise the way its velocity goes across the axis, or for a velocity straight along
     * the axis away from the other unit's position (for equal positions towards the lower coordinate for the lower slot). A slide is its sign
     * times {@link #SLIDE_VERTICAL} or {@link #SLIDE_HORIZONTAL}.
     *
     * @return the slide of this frame, {@link #NO_SLIDE} if the unit does not run head-on into the other unit
     */
    private int slideAround(@NotNull Unit unit, @NotNull Unit other, boolean pushHorizontal, int previousSlide) {
        if (!this.isHeadOn(unit, other, pushHorizontal)) {
            return NO_SLIDE;
        }

        int axis = pushHorizontal ? SLIDE_VERTICAL : SLIDE_HORIZONTAL;
        int crossVelocity = pushHorizontal ? unit.getVelocityY() : unit.getVelocityX();
        int unitCenter = pushHorizontal ? unit.getPositionY() : unit.getPositionX();
        int otherCenter = pushHorizontal ? other.getPositionY() : other.getPositionX();
        int sign;

        if (Math.abs(previousSlide) == axis) {
            sign = Integer.signum(previousSlide);
        } else if (crossVelocity != 0) {
            sign = Integer.signum(crossVelocity);
        } else if (unitCenter != otherCenter) {
            sign = Integer.signum(unitCenter - otherCenter);
        } else {
            sign = unit.getIndex() < other.getIndex() ? -1 : 1;
        }

        this.slide(unit, other, pushHorizontal, sign);

        return sign * axis;
    }

    /**
     * Steers a moving unit that runs head-on into another moving unit to its right-hand side across the push axis, so that two units that meet
     * pass each other.
     */
    private void passRight(@NotNull Unit unit, @NotNull Unit other, boolean pushHorizontal) {
        if (!this.isHeadOn(unit, other, pushHorizontal)) {
            return;
        }

        // The right-hand side of a velocity (x, y) with y downward is (-y, x).
        int sign = pushHorizontal ? Integer.signum(unit.getVelocityX()) : -Integer.signum(unit.getVelocityY());

        this.slide(unit, other, pushHorizontal, sign);
    }

    private boolean isHeadOn(@NotNull Unit unit, @NotNull Unit other, boolean pushHorizontal) {
        int velocity = pushHorizontal ? unit.getVelocityX() : unit.getVelocityY();
        int towardsOther = pushHorizontal ? other.getPositionX() - unit.getPositionX() : other.getPositionY() - unit.getPositionY();

        return (long) velocity * towardsOther > 0;
    }

    /**
     * Moves a unit that runs head-on into another unit back along the push axis by its move towards the other unit in this frame, then across
     * the axis by the push distance the way of the sign.
     */
    private void slide(@NotNull Unit unit, @NotNull Unit other, boolean pushHorizontal, int sign) {
        int slot = unit.getIndex();
        int progress = pushHorizontal ? unit.getPositionX() - this.originX[slot] : unit.getPositionY() - this.originY[slot];
        int towardsOther = pushHorizontal ? other.getPositionX() - unit.getPositionX() : other.getPositionY() - unit.getPositionY();

        if ((long) progress * towardsOther > 0) {
            this.push(unit, pushHorizontal, -progress, other);
        }

        this.push(unit, !pushHorizontal, sign * this.pushDistance, other);
    }

    /**
     * Moves a pushable unit along one axis, shortened to the speed bound of the class description; a push onto unwalkable terrain or towards a
     * unit that cannot be pushed in its way, other than the source of the push, does not happen.
     *
     * @param unit       the pushed unit
     * @param horizontal whether the push goes along the horizontal axis
     * @param distance   the signed distance in pixels
     * @param source     the unit the push comes from
     */
    private void push(@NotNull Unit unit, boolean horizontal, int distance, @NotNull Unit source) {
        int bounded = this.limitToSpeed(unit, horizontal, distance);

        if (bounded == 0) {
            return;
        }

        int changeX = horizontal ? bounded : 0;
        int changeY = horizontal ? 0 : bounded;

        if (!this.isWalkable(unit.getPositionX() + changeX, unit.getPositionY() + changeY) ||
            this.isStructureInWay(unit, changeX, changeY, source)) {
            return;
        }

        boolean idle = this.isIdle(unit);

        unit.setPosition(unit.getPositionX() + changeX, unit.getPositionY() + changeY);
        requireAttached(this.spatialIndex).relocate(unit, changeX, changeY);

        if (idle) {
            requireAttached(this.movement).stop(unit);
        }
    }

    /**
     * Shortens a push so that the unit stays within its top speed (at least one pixel per frame) of its position at the previous phase end; a push
     * back towards that position is never shortened below it.
     *
     * @return the shortened signed distance
     */
    private int limitToSpeed(@NotNull Unit unit, boolean horizontal, int distance) {
        int slot = unit.getIndex();
        long speed = Math.max(unit.getTopSpeed(), MINIMUM_PUSH_SPEED);
        int along = horizontal ? unit.getPositionX() - this.originX[slot] : unit.getPositionY() - this.originY[slot];
        long across = (long) (horizontal ? unit.getPositionY() - this.originY[slot]
                                         : unit.getPositionX() - this.originX[slot]) << SPEED_FRACTION_BITS;
        long remaining = speed * speed - across * across;
        int limit = remaining > 0 ? (int) (IntegerMathUtils.squareRoot(remaining) >> SPEED_FRACTION_BITS) : 0;

        if (distance > 0) {
            return Math.clamp(limit - along, 0, distance);
        }

        return Math.clamp(-limit - along, distance, 0);
    }

    /**
     * Checks whether a unit moved by a change would move towards a unit that cannot be pushed whose bounds it would overlap, other than the
     * source of the push.
     */
    private boolean isStructureInWay(@NotNull Unit unit, int changeX, int changeY, @NotNull Unit source) {
        this.searchedUnit = unit;
        this.pushSource   = source;
        this.pushChangeX  = changeX;
        this.pushChangeY  = changeY;

        try {
            requireAttached(this.spatialIndex).findInRectangle(this.bounds.getLeft(unit) + changeX, this.bounds.getTop(unit) + changeY,
                                                               this.bounds.getRight(unit) + changeX, this.bounds.getBottom(unit) + changeY,
                                                               this.structureFilter, this.structures);
        } finally {
            this.searchedUnit = null;
            this.pushSource   = null;
        }

        return !this.structures.isEmpty();
    }

    private boolean isStructureInPushWay(@NotNull Unit otherUnit) {
        Unit unit = this.searchedUnit;

        if (unit == null || otherUnit == this.pushSource || this.isPushable(otherUnit) || !this.isBlocking(unit, otherUnit)) {
            return false;
        }

        long towardsOther = (long) this.pushChangeX * (otherUnit.getPositionX() - unit.getPositionX()) +
                            (long) this.pushChangeY * (otherUnit.getPositionY() - unit.getPositionY());

        return towardsOther > 0;
    }

    private boolean findOnRing(@NotNull SpatialIndex index, @NotNull Unit unit, int x, int y, int ring, int stepX, int stepY,
                               @NotNull MutablePoint position) {
        if (ring == 0) {
            return this.tryPosition(index, unit, x, y, position);
        }

        for (int column = -ring; column <= ring; column++) {
            if (this.tryPosition(index, unit, x + column * stepX, y - ring * stepY, position)) {
                return true;
            }
        }

        for (int column = -ring; column <= ring; column++) {
            if (this.tryPosition(index, unit, x + column * stepX, y + ring * stepY, position)) {
                return true;
            }
        }

        for (int row = 1 - ring; row < ring; row++) {
            if (this.tryPosition(index, unit, x - ring * stepX, y + row * stepY, position)) {
                return true;
            }

            if (this.tryPosition(index, unit, x + ring * stepX, y + row * stepY, position)) {
                return true;
            }
        }

        return false;
    }

    private boolean tryPosition(@NotNull SpatialIndex index, @NotNull Unit unit, int x, int y, @NotNull MutablePoint position) {
        int horizontalChange = x - this.bounds.getCenterX(unit);
        int verticalChange = y - this.bounds.getCenterY(unit);
        int left = this.bounds.getLeft(unit) + horizontalChange;
        int top = this.bounds.getTop(unit) + verticalChange;
        int right = this.bounds.getRight(unit) + horizontalChange;
        int bottom = this.bounds.getBottom(unit) + verticalChange;
        UnitList found = this.candidates;

        index.findInRectangle(left, top, right, bottom, this.blockerFilter, found);

        if (!found.isEmpty() || !this.isWalkable(left, top, right, bottom)) {
            return false;
        }

        position.set(x, y);

        return true;
    }

    /**
     * Moves a ground unit whose position lies on an unwalkable walk cell to the center of the nearest walkable cell: the cells on square rings
     * around the unit's cell, ring by ring up to {@value #MAXIMUM_ESCAPE_RING}, each ring top row left to right, bottom row, then the left and
     * right columns top to bottom.
     */
    private void leaveUnwalkableCell(@NotNull Unit unit) {
        TerrainGrid attachedTerrain = this.terrain;

        if (attachedTerrain == null || this.isWalkable(unit.getPositionX(), unit.getPositionY())) {
            return;
        }

        GridTopology walkCells = attachedTerrain.getWalkGrid();
        int cellX = walkCells.findColumn(unit.getPositionX(), unit.getPositionY());
        int cellY = walkCells.findRow(unit.getPositionX(), unit.getPositionY());

        for (int ring = 1; ring <= MAXIMUM_ESCAPE_RING; ring++) {
            for (int offset = -ring; offset <= ring; offset++) {
                if (this.escapeTo(unit, attachedTerrain, cellX + offset, cellY - ring) ||
                    this.escapeTo(unit, attachedTerrain, cellX + offset, cellY + ring)) {
                    return;
                }
            }

            for (int offset = 1 - ring; offset < ring; offset++) {
                if (this.escapeTo(unit, attachedTerrain, cellX - ring, cellY + offset) ||
                    this.escapeTo(unit, attachedTerrain, cellX + ring, cellY + offset)) {
                    return;
                }
            }
        }
    }

    private boolean escapeTo(@NotNull Unit unit, @NotNull TerrainGrid attachedTerrain, int cellX, int cellY) {
        if (!attachedTerrain.isWalkable(cellX, cellY)) {
            return false;
        }

        GridTopology walkCells = attachedTerrain.getWalkGrid();
        int changeX = walkCells.getCenterX(cellX, cellY) - unit.getPositionX();
        int changeY = walkCells.getCenterY(cellX, cellY) - unit.getPositionY();

        unit.setPosition(unit.getPositionX() + changeX, unit.getPositionY() + changeY);
        requireAttached(this.spatialIndex).relocate(unit, changeX, changeY);

        return true;
    }

    private boolean isWalkable(int x, int y) {
        TerrainGrid attachedTerrain = this.terrain;

        return attachedTerrain == null ||
               attachedTerrain.isWalkable(attachedTerrain.getWalkGrid().findColumn(x, y), attachedTerrain.getWalkGrid().findRow(x, y));
    }

    private boolean isWalkable(int left, int top, int right, int bottom) {
        TerrainGrid attachedTerrain = this.terrain;

        if (attachedTerrain == null) {
            return true;
        }

        GridTopology walkCells = attachedTerrain.getWalkGrid();

        for (int walkY = walkCells.findRow(left, top); walkY <= walkCells.findRow(right, bottom); walkY++) {
            for (int walkX = walkCells.findColumn(left, top); walkX <= walkCells.findColumn(right, bottom); walkX++) {
                if (!attachedTerrain.isWalkable(walkX, walkY)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isSearchBlocker(@NotNull Unit otherUnit) {
        Unit unit = this.searchedUnit;

        return unit != null && this.isBlocking(unit, otherUnit);
    }

    private boolean isContact(@NotNull Unit otherUnit) {
        Unit unit = this.searchedUnit;

        if (unit == null || !this.isBlocking(unit, otherUnit)) {
            return false;
        }

        UnitType type = otherUnit.getType();

        return type == null || !requireAttached(this.unitTypes).hasTrait(type, UnitTrait.FLYER);
    }
}
