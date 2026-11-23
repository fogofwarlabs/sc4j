package org.fogofwarlabs.sc4j.engine.client.command;

import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceCost;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.ConstructBuildingCommandType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Constructs a building with a selected worker: a targeted action whose target is the site's center, which submits one
 * {@link ConstructBuildingCommandType} command for the first selected worker the requirements allow to build the type. While it waits for its
 * target, the game screen shows the building's footprint ({@link #getFootprintWidth()}, {@link #getFootprintHeight()}) under the pointer and
 * points at the snapped site; the game checks the command again when it runs. The action is refused ({@link #findRefusal}) while no selected
 * worker meets the requirement or the player lacks a resource of the cost or the building's supply.
 */
public final class ConstructAction implements CommandAction {
    private final @NotNull Game game;
    private final @NotNull ConstructBuildingCommandType commandType;
    private final @NotNull ProductionCheck check;

    private final int footprintWidth;
    private final int footprintHeight;

    /**
     * Creates a {@link ConstructAction} instance.
     *
     * @param game            the game the selected units belong to
     * @param commandType     the construction command of the mod's command stream
     * @param buildingType    the building type
     * @param footprintWidth  the width of the building's footprint in world units
     * @param footprintHeight the height of the building's footprint in world units
     */
    public ConstructAction(@NotNull Game game, @NotNull ConstructBuildingCommandType commandType, @NotNull UnitType buildingType,
                           int footprintWidth, int footprintHeight) {
        this.game        = game;
        this.commandType = commandType;
        this.check       = new ProductionCheck(game, buildingType);

        this.footprintWidth  = footprintWidth;
        this.footprintHeight = footprintHeight;
    }

    /**
     * @return the building type
     */
    public @NotNull UnitType getBuildingType() {
        return this.check.getUnitType();
    }

    /**
     * @return the width of the building's footprint in world units
     */
    public int getFootprintWidth() {
        return this.footprintWidth;
    }

    /**
     * @return the height of the building's footprint in world units
     */
    public int getFootprintHeight() {
        return this.footprintHeight;
    }

    /**
     * @return {@code true} if the action needs a target the player points at, {@code false} if it runs at once: construction needs its site
     */
    @Override
    public boolean isTargeted() {
        return true;
    }

    /**
     * Returns the resources the action spends.
     *
     * @return the cost of the building type
     */
    @Override
    public @NotNull ResourceCost getCost() {
        return this.check.getCost();
    }

    /**
     * Returns the supply the action's unit takes.
     *
     * @return the supply of the building type
     */
    @Override
    public int getSupplyCost() {
        return this.check.getSupplyCost();
    }


    /**
     * Submits the action's commands. A targeted action is still the waiting action of the translator's {@link TargetingMode} while it is
     * issued; it may refuse the target (for example a place a building cannot stand on), and then it keeps waiting for another.
     * <p>
     * Submits one construction command at the target for the first selected worker of the local player that may build the type.
     *
     * @param translator the translator: the local player, the selection and the command sink
     * @param target     the target the player pointed at, or {@code null} for an action without a target
     * @param queued     whether the commands queue after the units' current orders (Shift held)
     * @return {@code true} if the action took the target, {@code false} if it refused the target: without a target or if no selected worker may build the type
     */
    @Override
    public boolean issue(@NotNull CommandTranslator translator, @Nullable CommandTarget target, boolean queued) {
        Unit worker = this.findWorker(translator);

        if (target == null || worker == null) {
            return false;
        }

        translator.getSink().submit(translator.getPlayer(), this.commandType,
                                    this.commandType.create(this.game, worker, this.check.getUnitType(), target.getX(), target.getY()));

        return true;
    }

    /**
     * Finds why the action cannot run now for the translator's player and selection, for example a missing requirement or resource.
     * <p>
     * Checks the first selected worker of the local player that may build the type: each resource of the cost and the building's supply;
     * without such a worker the requirement of the first selected worker of the player.
     *
     * @param translator the translator: the local player and the selection
     * @return the refusal, or {@code null} if the action may run
     */
    @Override
    public @Nullable CommandRefusal findRefusal(@NotNull CommandTranslator translator) {
        Player player = translator.getPlayer();
        Unit worker = this.findWorker(translator);

        if (worker != null) {
            return this.check.findRefusal(player, worker, true);
        }

        for (Unit unit : translator.getSelection().getUnits()) {
            if (unit.getOwner() == player && this.isWorker(unit)) {
                return this.check.findRefusal(player, unit, true);
            }
        }

        return null;
    }

    private @Nullable Unit findWorker(@NotNull CommandTranslator translator) {
        Player player = translator.getPlayer();

        for (Unit unit : translator.getSelection().getUnits()) {
            if (unit.getOwner() == player && this.isWorker(unit) && this.check.isAllowed(player, unit)) {
                return unit;
            }
        }

        return null;
    }

    private boolean isWorker(@NotNull Unit unit) {
        UnitType type = unit.getType();

        return type != null && this.game.getContent().getUnitTypes().hasTrait(type, UnitTrait.WORKER);
    }
}
