package org.fogofwarlabs.sc4j.engine.client.command;

import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementAction;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementResult;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementSystem;
import org.fogofwarlabs.sc4j.engine.content.requirement.StandardRequirementResult;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.SetRallyPointCommandType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Sets the rally point of the selected producers to the pointed position through the engine rally command
 * ({@link SetRallyPointCommandType}), one command per producer of the local player in selection order. A producer is a building that makes
 * units: a unit type of its faction that is no building names it as a possible actor ({@link #isProducer}). A pointed unit sets the rally point
 * at the unit's position.
 */
public final class RallyAction implements CommandAction {
    private final @NotNull Game game;
    private final @NotNull SetRallyPointCommandType commandType;

    /**
     * Creates a {@link RallyAction} instance.
     *
     * @param game        the game whose units the commands name
     * @param commandType the rally command type of the game's command stream
     */
    public RallyAction(@NotNull Game game, @NotNull SetRallyPointCommandType commandType) {
        this.game        = game;
        this.commandType = commandType;
    }

    /**
     * @return {@code true} if the action needs a target the player points at, {@code false} if it runs at once: the rally point is the position the player points at
     */
    @Override
    public boolean isTargeted() {
        return true;
    }


    /**
     * Checks whether a unit makes units, so that a rally point sends them on: a building whose faction has a unit type that is no building whose
     * requirement names the unit as a possible actor (without a requirement system, any building of a faction).
     *
     * @param unit the unit
     * @return {@code true} if the unit is a producer, {@code false} otherwise
     */
    public boolean isProducer(@NotNull Unit unit) {
        UnitTypeTable table = this.game.getContent().getUnitTypes();
        UnitType unitType = unit.getType();
        Player owner = unit.getOwner();

        if (unitType == null || owner == null || !table.hasTrait(unitType, UnitTrait.BUILDING)) {
            return false;
        }

        Faction faction = table.getFaction(unitType);

        if (faction == null) {
            return false;
        }

        for (UnitType type : this.game.getContent().getDefinition().getUnitTypes().getTypes().getTypes()) {
            if (table.getFaction(type) != faction || table.hasTrait(type, UnitTrait.BUILDING)) {
                continue;
            }

            if (!this.game.hasSystem(RequirementSystem.KEY)) {
                return true;
            }

            RequirementResult result = this.game.getRequirements().check(RequirementAction.BUILD_UNIT, type, owner, unit);

            if (result.isMet() || result != StandardRequirementResult.WRONG_ACTOR && result != StandardRequirementResult.UNAVAILABLE) {
                return true;
            }
        }

        return false;
    }

    /**
     * Submits the action's commands. A targeted action is still the waiting action of the translator's {@link TargetingMode} while it is
     * issued; it may refuse the target (for example a place a building cannot stand on), and then it keeps waiting for another.
     * <p>
     * Submits one rally command for each selected producer of the local player, at the pointed unit's position or the pointed position.
     *
     * @param translator the translator: the local player, the selection and the command sink
     * @param target     the target the player pointed at, or {@code null} for an action without a target
     * @param queued     whether the commands queue after the units' current orders (Shift held)
     * @return {@code true} if the action took the target, {@code false} without a target or without a selected producer of the player
     */
    @Override
    public boolean issue(@NotNull CommandTranslator translator, @Nullable CommandTarget target, boolean queued) {
        if (target == null) {
            return false;
        }

        Player player = translator.getPlayer();
        Unit targetUnit = target.getUnit();
        int x = targetUnit != null ? targetUnit.getPositionX() : target.getX();
        int y = targetUnit != null ? targetUnit.getPositionY() : target.getY();
        boolean issued = false;

        for (Unit unit : translator.getSelection().getUnits()) {
            if (unit.getOwner() == player && this.isProducer(unit)) {
                translator.getSink().submit(player, this.commandType, this.commandType.create(this.game, unit, x, y));
                issued = true;
            }
        }

        return issued;
    }
}
