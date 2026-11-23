package org.fogofwarlabs.sc4j.engine.trigger;

import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.entity.pool.SlotList;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.StandardProductionSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Carries what the standard trigger conditions and actions read besides their records: the game, the player whose triggers run, and the timers
 * and outcomes of the {@link StandardTriggerSystem}. The system keeps one context per game and sets the current player while it walks the
 * players.
 */
public final class StandardTriggerContext implements TriggerContext {
    private final @NotNull Game game;
    private final @NotNull StandardTriggerSystem system;
    private final @Nullable StandardProductionSystem production;

    private @Nullable Player currentPlayer;

    /**
     * Creates a {@link StandardTriggerContext} instance outside the triggers of a player.
     *
     * @param game       the game
     * @param system     the trigger system whose timers and outcomes the context reads and changes
     * @param production the production system the unit actions create units with, or {@code null} if the game has no standard one
     */
    StandardTriggerContext(@NotNull Game game, @NotNull StandardTriggerSystem system, @Nullable StandardProductionSystem production) {
        this.game       = game;
        this.system     = system;
        this.production = production;

        this.currentPlayer = null;
    }

    /**
     * @return the game whose triggers run
     */
    @Override
    public @NotNull Game getGame() {
        return this.game;
    }

    /**
     * @return the player whose triggers run, or {@code null} outside the triggers of a player
     */
    @Override
    public @Nullable Player getCurrentPlayer() {
        return this.currentPlayer;
    }

    /**
     * Sets the player whose triggers run.
     *
     * @param currentPlayer the player, or {@code null} outside the triggers of a player
     */
    void setCurrentPlayer(@Nullable Player currentPlayer) {
        this.currentPlayer = currentPlayer;
    }

    /**
     * @return the standard production system of the game, which the unit actions create units with, or {@code null} if the game has none
     */
    public @Nullable StandardProductionSystem getProduction() {
        return this.production;
    }

    /**
     * Returns the frames a timer still counts down.
     *
     * @param timer the timer index, {@code 0} up to {@link StandardTriggerSystem#TIMER_COUNT} minus one
     * @return the remaining frames, {@code 0} for a timer that ran out or was never set
     * @throws IndexOutOfBoundsException if the timer index is out of range
     */
    public int getTimer(int timer) {
        return this.system.getTimer(timer);
    }

    /**
     * Sets a timer.
     *
     * @param timer  the timer index, {@code 0} up to {@link StandardTriggerSystem#TIMER_COUNT} minus one
     * @param frames the frames the timer counts down from, at least {@code 0}
     * @throws IndexOutOfBoundsException if the timer index is out of range
     */
    public void setTimer(int timer, int frames) {
        this.system.setTimer(timer, frames);
    }

    /**
     * Returns where a player stands.
     *
     * @param player the player
     * @return the outcome
     */
    public @NotNull PlayerOutcome getOutcome(@NotNull Player player) {
        return this.system.getOutcome(player);
    }

    /**
     * Decides about a player; a decided player keeps its outcome.
     *
     * @param player  the player
     * @param outcome the outcome, {@link PlayerOutcome#VICTORY} or {@link PlayerOutcome#DEFEAT}
     */
    public void decide(@NotNull Player player, @NotNull PlayerOutcome outcome) {
        this.system.decide(player, outcome);
    }

    /**
     * Checks whether every opponent of a player lost: every player of another team ({@link StandardTriggerSystem#isOpponent}).
     *
     * @param player the player
     * @return {@code true} if the player has opponents and every one of them has the outcome {@link PlayerOutcome#DEFEAT}, {@code false} otherwise
     */
    public boolean isEveryOpponentDefeated(@NotNull Player player) {
        boolean found = false;

        for (int index = 0; index < this.game.getPlayerCount(); index++) {
            Player other = this.game.getPlayer(index);

            if (!this.system.isOpponent(player, other)) {
                continue;
            }

            if (this.system.getOutcome(other) != PlayerOutcome.DEFEAT) {
                return false;
            }

            found = true;
        }

        return found;
    }

    /**
     * Displays a message to a player: the {@link StandardTriggerSystem#MESSAGE} hooks receive it in registration order.
     *
     * @param player the player
     * @param text   the text
     */
    public void displayMessage(@NotNull Player player, @NotNull String text) {
        this.system.displayMessage(player, text);
    }

    /**
     * Counts the units of a player, in slot order.
     *
     * @param player    the owner
     * @param unitType  the unit type, or {@code null} for any
     * @param unitTrait the trait of the unit type, or {@code null} for any
     * @param area      the area the unit position lies in, or {@code null} for the whole map
     * @return the number of units
     */
    public int countUnits(@NotNull Player player, @Nullable UnitType unitType, @Nullable UnitTrait unitTrait, @Nullable TriggerArea area) {
        EntityPool<Unit> units = this.game.getUnits();
        SlotList used = units.getUsedSlots();
        int count = 0;

        for (int index = used.getFirst(); index != SlotList.NONE; index = used.getNext(index)) {
            if (this.matches(units.get(index), player, unitType, unitTrait, area)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Checks whether a unit is one a unit condition or action counts or acts on.
     *
     * @param unit      the unit
     * @param player    the owner
     * @param unitType  the unit type, or {@code null} for any
     * @param unitTrait the trait of the unit type, or {@code null} for any
     * @param area      the area the unit position lies in, or {@code null} for the whole map
     * @return {@code true} if the unit has a type, is owned by the player and matches the type, trait and area, {@code false} otherwise
     */
    public boolean matches(@NotNull Unit unit, @NotNull Player player,
                           @Nullable UnitType unitType, @Nullable UnitTrait unitTrait, @Nullable TriggerArea area) {
        UnitType type = unit.getType();
        UnitTypeTable unitTypes = this.game.getContent().getUnitTypes();

        return type != null && unit.getOwner() == player && (unitType == null || type == unitType) &&
               (unitTrait == null || unitTypes.hasTrait(type, unitTrait)) &&
               (area == null || area.contains(unit.getPositionX(), unit.getPositionY()));
    }
}
