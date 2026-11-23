package org.fogofwarlabs.sc4j.engine.client.command;

import org.fogofwarlabs.sc4j.engine.content.economy.ResourceCost;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.ProductionSystem;
import org.fogofwarlabs.sc4j.engine.production.StandardProductionSystem;
import org.fogofwarlabs.sc4j.engine.production.TrainUnitCommandType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Trains a unit type at a selected producer: an action without a target that submits one {@link TrainUnitCommandType} command for the first
 * selected unit the requirements allow to train the type. The game checks the command again when it runs. The action is refused
 * ({@link #findRefusal}) while no selected producer meets the requirement, the player lacks a resource of the cost, the producer's queue of
 * the {@link StandardProductionSystem} is full, or that system holds the supply from the enqueue and the player lacks it.
 */
public final class TrainAction implements CommandAction {
    private final @NotNull Game game;
    private final @NotNull TrainUnitCommandType commandType;
    private final @NotNull ProductionCheck check;

    /**
     * Creates a {@link TrainAction} instance.
     *
     * @param game        the game the selected units belong to
     * @param commandType the training command of the mod's command stream
     * @param unitType    the unit type to train
     */
    public TrainAction(@NotNull Game game, @NotNull TrainUnitCommandType commandType, @NotNull UnitType unitType) {
        this.game        = game;
        this.commandType = commandType;
        this.check       = new ProductionCheck(game, unitType);
    }

    /**
     * @return the unit type to train
     */
    public @NotNull UnitType getUnitType() {
        return this.check.getUnitType();
    }

    /**
     * @return {@code true} if the action needs a target the player points at, {@code false} if it runs at once: training runs at once
     */
    @Override
    public boolean isTargeted() {
        return false;
    }

    /**
     * Returns the resources the action spends.
     *
     * @return the cost of the unit type
     */
    @Override
    public @NotNull ResourceCost getCost() {
        return this.check.getCost();
    }

    /**
     * Returns the supply the action's unit takes.
     *
     * @return the supply of the unit type
     */
    @Override
    public int getSupplyCost() {
        return this.check.getSupplyCost();
    }


    /**
     * Submits the action's commands. A targeted action is still the waiting action of the translator's {@link TargetingMode} while it is
     * issued; it may refuse the target (for example a place a building cannot stand on), and then it keeps waiting for another.
     * <p>
     * Submits one training command for the first selected unit of the local player that may train the type; training always joins the
     * producer's queue.
     *
     * @param translator the translator: the local player, the selection and the command sink
     * @param target     the target the player pointed at, or {@code null} for an action without a target
     * @param queued     whether the commands queue after the units' current orders (Shift held)
     * @return {@code true} if the action took the target (or ran without one), {@code false} if it refused the target: here if no selected unit may train the type
     */
    @Override
    public boolean issue(@NotNull CommandTranslator translator, @Nullable CommandTarget target, boolean queued) {
        Unit producer = this.findProducer(translator);

        if (producer == null) {
            return false;
        }

        translator.getSink().submit(translator.getPlayer(), this.commandType, this.commandType.create(this.game, producer, this.check.getUnitType()));

        return true;
    }

    /**
     * Finds why the action cannot run now for the translator's player and selection, for example a missing requirement or resource.
     * <p>
     * Checks the first selected unit of the local player that may train the type: a resource of the cost, a full queue of the
     * {@link StandardProductionSystem}, and the supply when that system holds it from the enqueue; without such a unit the requirement of the
     * first selected unit of the player.
     *
     * @param translator the translator: the local player and the selection
     * @return the refusal, or {@code null} if the action may run
     */
    @Override
    public @Nullable CommandRefusal findRefusal(@NotNull CommandTranslator translator) {
        Player player = translator.getPlayer();
        Unit producer = this.findProducer(translator);

        if (producer == null) {
            for (Unit unit : translator.getSelection().getUnits()) {
                if (unit.getOwner() == player && unit.getType() != null) {
                    return this.check.findRefusal(player, unit, false);
                }
            }

            return null;
        }

        StandardProductionSystem production = this.game.hasSystem(ProductionSystem.KEY) &&
                                              this.game.getProduction() instanceof StandardProductionSystem standard ? standard : null;

        if (production != null && production.getQueueLength(producer) >= production.getQueueCapacity()) {
            return CommandRefusal.QUEUE_FULL;
        }

        return this.check.findRefusal(player, producer, production != null && production.isSupplyHeldWhenQueued());
    }

    /**
     * Finds why the result of the action, which may run now, will wait, for example the missing supply of a unit that a production queues
     * without holding the supply first. The command card shows it in the button's tooltip.
     * <p>
     * Warns of missing supply while the action may run and the {@link StandardProductionSystem} takes the supply only when the unit starts.
     *
     * @param translator the translator: the local player and the selection
     * @return {@link CommandRefusal#MISSING_SUPPLY} if the queued unit will wait for supply, {@code null} otherwise
     */
    @Override
    public @Nullable CommandRefusal findWarning(@NotNull CommandTranslator translator) {
        Unit producer = this.findProducer(translator);

        if (producer == null || !this.game.hasSystem(ProductionSystem.KEY) ||
            !(this.game.getProduction() instanceof StandardProductionSystem production) || production.isSupplyHeldWhenQueued() ||
            this.findRefusal(translator) != null) {
            return null;
        }

        CommandRefusal refusal = this.check.findRefusal(translator.getPlayer(), producer, true);

        return refusal == CommandRefusal.MISSING_SUPPLY ? refusal : null;
    }

    private @Nullable Unit findProducer(@NotNull CommandTranslator translator) {
        Player player = translator.getPlayer();

        for (Unit unit : translator.getSelection().getUnits()) {
            if (unit.getOwner() == player && unit.getType() != null && this.check.isAllowed(player, unit)) {
                return unit;
            }
        }

        return null;
    }
}
