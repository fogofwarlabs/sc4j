package org.fogofwarlabs.sc4j.engine.combat;

import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.math.IntegerMathUtils;
import org.fogofwarlabs.sc4j.engine.movement.MovementSystem;
import org.fogofwarlabs.sc4j.engine.order.GroupArrival;
import org.fogofwarlabs.sc4j.engine.order.OrderBehavior;
import org.fogofwarlabs.sc4j.engine.order.OrderContext;
import org.fogofwarlabs.sc4j.engine.path.PathfindingSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Moves a unit to the target point of the current order and fights every enemy it meets on the way with the standard combat system: the
 * engine default behavior of an attack-move order. A mod registers it for its attack-move order type with the arrival distance and, for
 * groups, a {@link GroupArrival}.
 * {@snippet :
 * behaviors.register(attackMove, new AttackMoveBehavior((StandardCombatSystem) game.getCombat(), 16));
 *}
 * <p>
 * When the order starts, the target point becomes the unit's destination and the order has no target unit. Each update keeps the engaged enemy
 * in the order's target unit: an engaged enemy the unit can no longer attack ({@link CombatSystem#canAttack}) is dropped and the unit heads for
 * the target point again; without an engaged enemy the unit acquires one ({@link StandardCombatSystem#findTarget}). An engaged enemy is pursued
 * ({@link CombatSystem#pursueTarget}) and fired at when the weapon is ready ({@link StandardCombatSystem#fireWeapon}); when the pursuit ends,
 * the enemy is dropped. Without an enemy the unit moves on; the order ends when the unit is within the arrival distance of the target point
 * (the unit stops there), arrived with its group ({@link GroupArrival#hasArrived}), cannot reach it, or stands still while it has no enemy: at
 * its destination, or where the movement stopped it short of the point (at the nearest point it can reach). A unit without weapons acquires
 * nothing and just moves.
 * <p>
 * Determinism contract: the behavior draws no random value and allocates nothing.
 */
public final class AttackMoveBehavior implements OrderBehavior<OrderContext, Unit> {
    private final @NotNull StandardCombatSystem combat;
    private final int arrivalDistance;
    private final @Nullable GroupArrival groupArrival;

    /**
     * Creates an {@link AttackMoveBehavior} instance without group arrival.
     *
     * @param combat          the combat system of the game
     * @param arrivalDistance the distance in pixels from the target point within which the order ends, at least {@code 0}
     */
    public AttackMoveBehavior(@NotNull StandardCombatSystem combat, int arrivalDistance) {
        this(combat, arrivalDistance, null);
    }

    /**
     * Creates an {@link AttackMoveBehavior} instance.
     *
     * @param combat          the combat system of the game
     * @param arrivalDistance the distance in pixels from the target point within which the order ends, at least {@code 0}
     * @param groupArrival    the rule of a unit that arrives with its group, or {@code null} for none
     */
    public AttackMoveBehavior(@NotNull StandardCombatSystem combat, int arrivalDistance, @Nullable GroupArrival groupArrival) {
        this.combat          = combat;
        this.arrivalDistance = arrivalDistance;
        this.groupArrival    = groupArrival;
    }

    /**
     * Starts the order of a unit when it becomes the unit's current order: the engine
     * {@link org.fogofwarlabs.sc4j.engine.order.StandardOrderSystem} calls it right after an
     * {@link org.fogofwarlabs.sc4j.engine.order.OrderSystem#issue} or {@link org.fogofwarlabs.sc4j.engine.order.OrderSystem#finishOrder} made the
     * order current, with the order's target already on the unit. An order system of a mod may run its own start rules instead.
     * <p>
     * The target point becomes the unit's destination and the order engages no enemy yet.
     *
     * @param context the context of the dispatch
     * @param unit    the unit whose order starts
     */
    @Override
    public void start(@NotNull OrderContext context, @NotNull Unit unit) {
        unit.setOrderTargetUnit(null);
        context.getGame().getMovement().setDestination(unit, unit.getOrderTargetX(), unit.getOrderTargetY());
    }

    /**
     * Runs the order of one unit for one frame.
     *
     * @param context the context of the dispatch
     * @param unit    the unit whose order runs
     */
    @Override
    public void update(@NotNull OrderContext context, @NotNull Unit unit) {
        if (this.engage(context, unit)) {
            return;
        }

        if (this.hasArrived(context, unit)) {
            context.getGame().getMovement().stop(unit);
            context.getGame().getOrders().finishOrder(unit, context.getInstructionLimit());
        }
    }

    /**
     * Fights the engaged or a newly acquired enemy, or heads the unit for the target point again when it fights none.
     *
     * @param context the context of the dispatch
     * @param unit    the unit whose order runs
     * @return {@code true} if the unit fights an enemy in this frame, {@code false} if it moves to the target point
     */
    boolean engage(@NotNull OrderContext context, @NotNull Unit unit) {
        MovementSystem movement = context.getGame().getMovement();
        Unit target = unit.getOrderTargetUnit();

        if (target != null && !this.combat.canAttack(unit, target)) {
            target = null;

            this.resume(movement, unit);
        }

        if (target == null) {
            target = this.combat.findTarget(unit);

            if (target == null) {
                return false;
            }

            unit.setOrderTargetUnit(target);
        }

        if (!this.combat.pursueTarget(unit, target, context.getInstructionLimit())) {
            this.resume(movement, unit);

            return false;
        }

        this.combat.fireWeapon(unit, target, context.getInstructionLimit());

        return true;
    }

    /**
     * Checks whether a unit that fights no enemy has reached the target point, and heads it there again when a fight moved it off its way. The
     * unit has arrived when it is within the arrival distance of the target point, arrived with its group, cannot reach it
     * ({@link PathfindingSystem#isReachable}, with a path finding system) or stands still at its destination or where the movement stopped it.
     *
     * @param context the context of the dispatch
     * @param unit    the unit whose order runs
     * @return {@code true} if the unit has arrived, {@code false} otherwise
     */
    boolean hasArrived(@NotNull OrderContext context, @NotNull Unit unit) {
        MovementSystem movement = context.getGame().getMovement();
        int targetX = unit.getOrderTargetX();
        int targetY = unit.getOrderTargetY();

        if (IntegerMathUtils.isWithinDistance(unit.getPositionX(), unit.getPositionY(), targetX, targetY, this.arrivalDistance)) {
            return true;
        }

        Game game = context.getGame();
        GroupArrival group = this.groupArrival;

        if (group != null && group.hasArrived(game, unit, targetX, targetY)) {
            return true;
        }

        if (game.hasSystem(PathfindingSystem.KEY) && !game.getPathfinding().isReachable(unit, targetX, targetY)) {
            return true;
        }

        if (unit.getMovementDestinationX() != targetX || unit.getMovementDestinationY() != targetY) {
            // A fight ends with the unit heading for the point again, so a unit that stands still here was stopped short of the point.
            if (movement.isAtDestination(unit)) {
                return true;
            }

            movement.setDestination(unit, targetX, targetY);

            return false;
        }

        return movement.isAtDestination(unit);
    }

    private void resume(@NotNull MovementSystem movement, @NotNull Unit unit) {
        unit.setOrderTargetUnit(null);
        movement.setDestination(unit, unit.getOrderTargetX(), unit.getOrderTargetY());
    }
}
