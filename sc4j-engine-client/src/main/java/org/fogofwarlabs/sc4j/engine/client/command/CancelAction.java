package org.fogofwarlabs.sc4j.engine.client.command;

import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.CancelProductionCommandType;
import org.fogofwarlabs.sc4j.engine.production.ProductionSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cancels production at a selected unit: an action without a target that submits one {@link CancelProductionCommandType} command for the first
 * selected unit of the local player that has something to cancel. A building under construction cancels its construction, a producer the last
 * entry of its queue. {@link #cancelSlot} cancels one queue slot, as a click on the queue panel does. The game checks the command again when it
 * runs.
 */
public final class CancelAction implements CommandAction {
    private final @NotNull Game game;
    private final @NotNull CancelProductionCommandType commandType;

    /**
     * Creates a {@link CancelAction} instance.
     *
     * @param game        the game the selected units belong to
     * @param commandType the cancel command of the mod's command stream
     */
    public CancelAction(@NotNull Game game, @NotNull CancelProductionCommandType commandType) {
        this.game        = game;
        this.commandType = commandType;
    }

    /**
     * @return {@code true} if the action needs a target the player points at, {@code false} if it runs at once: cancelling runs at once
     */
    @Override
    public boolean isTargeted() {
        return false;
    }


    /**
     * Checks whether a unit has something a player can cancel: the player owns it and it is under construction or has a queue entry.
     *
     * @param player the player
     * @param unit   the unit
     * @return {@code true} if the unit has something to cancel, {@code false} otherwise
     */
    public boolean canCancel(@NotNull Player player, @NotNull Unit unit) {
        if (unit.getOwner() != player || !this.game.hasSystem(ProductionSystem.KEY)) {
            return false;
        }

        ProductionSystem production = this.game.getProduction();

        return production.isUnderConstruction(unit) || production.getQueueLength(unit) > 0;
    }

    /**
     * Submits the action's commands. A targeted action is still the waiting action of the translator's {@link TargetingMode} while it is
     * issued; it may refuse the target (for example a place a building cannot stand on), and then it keeps waiting for another.
     * <p>
     * Submits one cancel command for the first selected unit of the local player that has something to cancel: its construction, or the last
     * entry of its queue.
     *
     * @param translator the translator: the local player, the selection and the command sink
     * @param target     the target the player pointed at, or {@code null} for an action without a target
     * @param queued     whether the commands queue after the units' current orders (Shift held)
     * @return {@code true} if the action took the target (or ran without one), {@code false} if it refused the target: here if no selected unit has something to cancel
     */
    @Override
    public boolean issue(@NotNull CommandTranslator translator, @Nullable CommandTarget target, boolean queued) {
        Player player = translator.getPlayer();

        for (Unit unit : translator.getSelection().getUnits()) {
            if (this.canCancel(player, unit)) {
                ProductionSystem production = this.game.getProduction();
                int slot = production.isUnderConstruction(unit) ? 0 : production.getQueueLength(unit) - 1;

                this.cancelSlot(translator, unit, slot);

                return true;
            }
        }

        return false;
    }

    /**
     * Submits one cancel command for a queue slot of a producer of the local player, or for the construction of a building under construction.
     *
     * @param translator the translator: the local player and the command sink
     * @param producer   the producing unit, or the building under construction
     * @param slot       the queue slot, {@code 0} for a building under construction
     * @throws IllegalArgumentException if the slot is outside {@code 0} to {@link CancelProductionCommandType#MAXIMUM_SLOT}
     */
    public void cancelSlot(@NotNull CommandTranslator translator, @NotNull Unit producer, int slot) {
        translator.getSink().submit(translator.getPlayer(), this.commandType, this.commandType.create(this.game, producer, slot));
    }
}
