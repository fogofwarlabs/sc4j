package org.fogofwarlabs.sc4j.engine.trigger;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.game.ControllerKind;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.PlayerSlot;
import org.fogofwarlabs.sc4j.engine.game.frame.PhaseSchedule;
import org.fogofwarlabs.sc4j.engine.hook.HookComposition;
import org.fogofwarlabs.sc4j.engine.hook.HookList;
import org.fogofwarlabs.sc4j.engine.hook.HookPoint;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.ProductionSystem;
import org.fogofwarlabs.sc4j.engine.production.StandardProductionSystem;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.StateHashContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stores the triggers of a game as {@link TriggerDefinition}s and runs them with the generic conditions and actions of
 * {@link StandardTriggerConditionUtils} and {@link StandardTriggerActionUtils}, and the mod's own: the engine default {@link TriggerSystem}. A mod
 * registers it under {@link TriggerSystem#KEY} with its triggers (for example the map's, or {@link StandardTriggerUtils#createAnnihilation})
 * and lists it as a frame phase.
 * {@snippet :
 * builder.useSystem(TriggerSystem.KEY, context -> new StandardTriggerSystem.Builder().addTriggers(StandardTriggerUtils.createAnnihilation()).build())
 *        .addFramePhase(TriggerSystem.KEY);
 *}
 * <p>
 * When the system is attached, every trigger gets one {@link RunningTrigger} per player it runs for: in ascending player slot order, the triggers
 * of a slot in definition order; a trigger for all players runs for every setup slot that is not {@link ControllerKind#NEUTRAL}. The phase runs
 * in the running frames whose number is a multiple of the interval: it counts the timers ({@link #TIMER_COUNT}) and the waits of waiting
 * triggers down by the interval, then runs every running trigger in that order whose player still plays ({@link PlayerOutcome#PLAYING}) and that
 * is not done. A trigger that does not resume at an action tests its conditions in list order up to the first that does not hold (a condition
 * type without condition does not hold); then it runs its actions from the action it resumes at, in list order, up to the first that does not
 * finish (an action type without action is skipped), where the next run resumes. A trigger whose actions all finished starts over with its
 * conditions if it repeats, and is done otherwise.
 * <p>
 * Two players are opponents when both have a setup slot that is not neutral and the slots' teams differ. A player's outcome changes once, from
 * {@link PlayerOutcome#PLAYING}; the {@link #OUTCOME} hooks hear it and the {@link #MESSAGE} hooks receive the messages, in registration order.
 * <p>
 * Determinism contract: the phase walks the running triggers in the order above, draws no random value itself and allocates nothing. The
 * timers, the outcomes in player order and the running triggers in run order are mixed into the state hash.
 */
public final class StandardTriggerSystem implements TriggerSystem, StateHashContributor, SnapshotContributor {
    /**
     * The number of timers the standard conditions and actions read and set.
     */
    public static final int TIMER_COUNT = 8;
    /**
     * The point the messages of triggers go to ({@link HookComposition#EVERY_IN_ORDER}).
     */
    public static final @NotNull HookPoint<TriggerMessageHook> MESSAGE = HookPoint.of("trigger.message", TriggerMessageHook.class,
                                                                                      HookComposition.EVERY_IN_ORDER);
    /**
     * The point a player's outcome change is announced at ({@link HookComposition#EVERY_IN_ORDER}).
     */
    public static final @NotNull HookPoint<PlayerOutcomeHook> OUTCOME = HookPoint.of("trigger.outcome", PlayerOutcomeHook.class,
                                                                                     HookComposition.EVERY_IN_ORDER);

    /**
     * The team of a player without a slot in the setup, or with a neutral slot.
     */
    private static final int NO_TEAM = -1;

    /**
     * Builds {@link StandardTriggerSystem} instances: the triggers, the interval and the mod's own conditions and actions.
     */
    public static final class Builder {
        private final @NotNull List<@NotNull TriggerDefinition> triggers;
        private final @NotNull List<@NotNull Integer> conditionTypes;
        private final @NotNull List<@NotNull TriggerCondition<StandardTriggerContext, ConditionRecord>> conditions;
        private final @NotNull List<@NotNull Integer> actionTypes;
        private final @NotNull List<@NotNull TriggerAction<StandardTriggerContext, RunningTrigger, ActionRecord>> actions;

        private int interval;

        /**
         * Creates a {@link Builder} instance without triggers that runs every frame.
         */
        public Builder() {
            this.triggers       = new ArrayList<>();
            this.conditionTypes = new ArrayList<>();
            this.conditions     = new ArrayList<>();
            this.actionTypes    = new ArrayList<>();
            this.actions        = new ArrayList<>();

            this.interval = 1;
        }

        /**
         * Appends a trigger.
         *
         * @param trigger the trigger
         * @return this instance for chaining
         */
        public @NotNull Builder addTrigger(@NotNull TriggerDefinition trigger) {
            this.triggers.add(trigger);

            return this;
        }

        /**
         * Appends triggers in list order.
         *
         * @param triggers the triggers
         * @return this instance for chaining
         */
        public @NotNull Builder addTriggers(@NotNull List<@NotNull TriggerDefinition> triggers) {
            this.triggers.addAll(triggers);

            return this;
        }

        /**
         * Sets the number of frames between two runs of the triggers.
         *
         * @param interval the interval, at least {@code 1}
         * @return this instance for chaining
         * @throws IllegalArgumentException if the interval is below {@code 1}
         */
        public @NotNull Builder setInterval(int interval) {
            if (interval < 1) {
                throw Exceptions.newIllegalArgumentException("The trigger interval {0} is below 1.", interval);
            }

            this.interval = interval;

            return this;
        }

        /**
         * Registers a condition of the mod, or replaces a standard one.
         *
         * @param type      the condition type id, at least {@code 0}
         * @param condition the condition
         * @return this instance for chaining
         */
        public @NotNull Builder addCondition(int type, @NotNull TriggerCondition<StandardTriggerContext, ConditionRecord> condition) {
            this.conditionTypes.add(type);
            this.conditions.add(condition);

            return this;
        }

        /**
         * Registers an action of the mod, or replaces a standard one.
         *
         * @param type   the action type id, at least {@code 0}
         * @param action the action
         * @return this instance for chaining
         */
        public @NotNull Builder addAction(int type, @NotNull TriggerAction<StandardTriggerContext, RunningTrigger, ActionRecord> action) {
            this.actionTypes.add(type);
            this.actions.add(action);

            return this;
        }

        /**
         * Builds a {@link StandardTriggerSystem} instance with these triggers and settings.
         *
         * @return the built {@link StandardTriggerSystem} instance
         */
        public @NotNull StandardTriggerSystem build() {
            return new StandardTriggerSystem(this);
        }
    }

    private static int getTypeCount(int standardCount, @NotNull List<@NotNull Integer> types) {
        int count = standardCount;

        for (int type : types) {
            count = Math.max(count, type + 1);
        }

        return count;
    }

    private static int checkTimer(int timer) {
        if (timer < 0 || timer >= TIMER_COUNT) {
            throw Exceptions.newIndexOutOfBoundsException("No timer {0}; the trigger system has {1}.", timer, TIMER_COUNT);
        }

        return timer;
    }

    private static <T> @NotNull T requireAttached(@Nullable T value) {
        if (value == null) {
            throw Exceptions.newIllegalStateException("The standard trigger system is not attached.");
        }

        return value;
    }

    private final @NotNull List<@NotNull TriggerDefinition> definitions;
    private final int interval;
    private final @NotNull TriggerRegistry<TriggerCondition<StandardTriggerContext, ConditionRecord>> conditions;
    private final @NotNull TriggerRegistry<TriggerAction<StandardTriggerContext, RunningTrigger, ActionRecord>> actions;
    private final int @NotNull [] timers;

    private @Nullable StandardTriggerContext context;
    private @Nullable HookList<TriggerMessageHook> messageHooks;
    private @Nullable HookList<PlayerOutcomeHook> outcomeHooks;
    private @NotNull RunningTrigger @NotNull [] running;
    private @NotNull PlayerOutcome @NotNull [] outcomes;
    private int @NotNull [] teams;

    private StandardTriggerSystem(@NotNull Builder builder) {
        this.definitions = List.copyOf(builder.triggers);
        this.interval    = builder.interval;
        this.conditions  = new TriggerRegistry<>(getTypeCount(StandardTriggerConditionUtils.TYPE_COUNT, builder.conditionTypes));
        this.actions     = new TriggerRegistry<>(getTypeCount(StandardTriggerActionUtils.TYPE_COUNT, builder.actionTypes));
        this.timers      = new int[TIMER_COUNT];

        StandardTriggerConditionUtils.register(this.conditions);
        StandardTriggerActionUtils.register(this.actions);

        for (int index = 0; index < builder.conditionTypes.size(); index++) {
            this.conditions.register(builder.conditionTypes.get(index), builder.conditions.get(index));
        }

        for (int index = 0; index < builder.actionTypes.size(); index++) {
            this.actions.register(builder.actionTypes.get(index), builder.actions.get(index));
        }

        this.conditions.freeze();
        this.actions.freeze();

        this.context      = null;
        this.messageHooks = null;
        this.outcomeHooks = null;
        this.running      = new RunningTrigger[0];
        this.outcomes     = new PlayerOutcome[0];
        this.teams        = new int[0];
    }

    /**
     * @return the frames the triggers run in: the running frames only
     */
    @Override
    public @NotNull PhaseSchedule getSchedule() {
        return PhaseSchedule.RUNNING;
    }

    /**
     * @return the running triggers in run order
     */
    public @NotNull List<@NotNull RunningTrigger> getRunningTriggers() {
        return List.of(this.running);
    }

    /**
     * Returns the frames a timer still counts down.
     *
     * @param timer the timer index, {@code 0} up to {@link #TIMER_COUNT} minus one
     * @return the remaining frames
     * @throws IndexOutOfBoundsException if the timer index is out of range
     */
    public int getTimer(int timer) {
        return this.timers[checkTimer(timer)];
    }

    /**
     * Sets a timer.
     *
     * @param timer  the timer index, {@code 0} up to {@link #TIMER_COUNT} minus one
     * @param frames the frames the timer counts down from; a negative value counts as {@code 0}
     * @throws IndexOutOfBoundsException if the timer index is out of range
     */
    public void setTimer(int timer, int frames) {
        this.timers[checkTimer(timer)] = Math.max(0, frames);
    }

    /**
     * Returns where a player stands.
     *
     * @param player the player
     * @return the outcome, {@link PlayerOutcome#PLAYING} for a player the system was not attached with
     */
    public @NotNull PlayerOutcome getOutcome(@NotNull Player player) {
        int index = player.getIndex();

        return index < this.outcomes.length ? this.outcomes[index] : PlayerOutcome.PLAYING;
    }


    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Creates the context, binds the hooks and the standard production system if the game has one, reads the team of each player slot that is not
     * neutral and creates the running triggers in run order.
     *
     * @param game the game
     */
    @Override
    public void attach(@NotNull Game game) {
        ProductionSystem production = game.hasSystem(ProductionSystem.KEY) ? game.getProduction() : null;
        List<RunningTrigger> triggers = new ArrayList<>();

        this.context      = new StandardTriggerContext(game, this, production instanceof StandardProductionSystem standard ? standard : null);
        this.messageHooks = game.getHooks(MESSAGE);
        this.outcomeHooks = game.getHooks(OUTCOME);
        this.outcomes     = new PlayerOutcome[game.getPlayerCount()];
        this.teams        = new int[game.getPlayerCount()];

        Arrays.fill(this.outcomes, PlayerOutcome.PLAYING);
        Arrays.fill(this.teams, NO_TEAM);

        for (PlayerSlot slot : game.getPlayers()) {
            int slotIndex = slot.getSlotIndex();

            if (slotIndex >= this.teams.length || slot.getControllerKind() == ControllerKind.NEUTRAL) {
                continue;
            }

            this.teams[slotIndex] = slot.getTeam();

            for (TriggerDefinition definition : this.definitions) {
                if (definition.isForAllPlayers() || definition.getPlayerSlots().contains(slotIndex)) {
                    triggers.add(new RunningTrigger(definition, game.getPlayer(slotIndex)));
                }
            }
        }

        this.running = triggers.toArray(new RunningTrigger[0]);
    }

    /**
     * Evaluates the triggers of the current frame.
     * <p>
     * Counts the timers and waits down and runs the triggers, as the class description says, in the frames of the interval.
     *
     * @param game the game
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void run(@NotNull Game game) {
        StandardTriggerContext attachedContext = requireAttached(this.context);

        if (game.getClock().getFrame() % this.interval != 0) {
            return;
        }

        for (int timer = 0; timer < this.timers.length; timer++) {
            this.timers[timer] = Math.max(0, this.timers[timer] - this.interval);
        }

        for (RunningTrigger trigger : this.running) {
            if (trigger.isWaiting()) {
                trigger.setWait(true, Math.max(0, trigger.getWaitRemaining() - this.interval));
            }
        }

        for (RunningTrigger trigger : this.running) {
            if (trigger.isDone() || this.getOutcome(trigger.getPlayer()) != PlayerOutcome.PLAYING) {
                continue;
            }

            attachedContext.setCurrentPlayer(trigger.getPlayer());
            this.runTrigger(attachedContext, trigger);
        }

        attachedContext.setCurrentPlayer(null);
    }

    /**
     * Decides about a player and announces it to the {@link #OUTCOME} hooks; a decided player keeps its outcome.
     *
     * @param player  the player
     * @param outcome the outcome, {@link PlayerOutcome#VICTORY} or {@link PlayerOutcome#DEFEAT}
     * @throws IllegalArgumentException if the outcome is {@link PlayerOutcome#PLAYING}
     * @throws IllegalStateException    if the system is not attached
     */
    public void decide(@NotNull Player player, @NotNull PlayerOutcome outcome) {
        HookList<PlayerOutcomeHook> hooks = requireAttached(this.outcomeHooks);

        if (outcome == PlayerOutcome.PLAYING) {
            throw Exceptions.newIllegalArgumentException("A decision cannot make the player {0} play again.", player);
        }

        if (this.getOutcome(player) != PlayerOutcome.PLAYING || player.getIndex() >= this.outcomes.length) {
            return;
        }

        this.outcomes[player.getIndex()] = outcome;

        for (int index = 0; index < hooks.size(); index++) {
            hooks.get(index).onOutcome(player, outcome);
        }
    }

    /**
     * @return {@code true} if every player of a setup slot that is not neutral has an outcome other than {@link PlayerOutcome#PLAYING}, {@code false} otherwise
     */
    public boolean isGameOver() {
        for (int index = 0; index < this.outcomes.length; index++) {
            if (this.teams[index] != NO_TEAM && this.outcomes[index] == PlayerOutcome.PLAYING) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks whether two players are opponents.
     *
     * @param player      the player
     * @param otherPlayer the other player
     * @return {@code true} if both have a setup slot that is not neutral and the teams of their slots differ, {@code false} otherwise
     */
    public boolean isOpponent(@NotNull Player player, @NotNull Player otherPlayer) {
        int team = player.getIndex() < this.teams.length ? this.teams[player.getIndex()] : NO_TEAM;
        int otherTeam = otherPlayer.getIndex() < this.teams.length ? this.teams[otherPlayer.getIndex()] : NO_TEAM;

        return team != NO_TEAM && otherTeam != NO_TEAM && team != otherTeam;
    }

    /**
     * Displays a message to a player: the {@link #MESSAGE} hooks receive it in registration order.
     *
     * @param player the player
     * @param text   the text
     * @throws IllegalStateException if the system is not attached
     */
    public void displayMessage(@NotNull Player player, @NotNull String text) {
        HookList<TriggerMessageHook> hooks = requireAttached(this.messageHooks);

        for (int index = 0; index < hooks.size(); index++) {
            hooks.get(index).onMessage(player, text);
        }
    }

    /**
     * Mixes this part of the simulation state into the hash: the timers, the outcome of every player in player order and the execution state of
     * every running trigger in run order.
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        hash.mixArray(this.timers);

        for (PlayerOutcome outcome : this.outcomes) {
            hash.mix(outcome.ordinal());
        }

        for (RunningTrigger trigger : this.running) {
            trigger.contributeStateHash(hash);
        }
    }

    /**
     * Writes this part of the simulation state: the timers, the outcome of every player and the progress of every running trigger.
     *
     * @param writer the writer of the snapshot
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        writer.writeInts(this.timers);
        writer.writeInt(this.outcomes.length);

        for (PlayerOutcome outcome : this.outcomes) {
            writer.writeInt(outcome.ordinal());
        }

        writer.writeInt(this.running.length);

        for (RunningTrigger trigger : this.running) {
            trigger.writeSnapshot(writer);
        }
    }

    /**
     * Reads this part of the simulation state back, replacing the current values.
     *
     * @param reader the reader of the snapshot
     * @throws IOException if the snapshot ends early, holds another number of values, players or triggers or names no outcome
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        PlayerOutcome[] values = PlayerOutcome.values();

        reader.readInts(this.timers);
        reader.readLength(this.outcomes.length);

        for (int index = 0; index < this.outcomes.length; index++) {
            int ordinal = reader.readInt();

            if (ordinal < 0 || ordinal >= values.length) {
                throw Exceptions.newIOException("The state snapshot names the player outcome {0}.", ordinal);
            }

            this.outcomes[index] = values[ordinal];
        }

        reader.readLength(this.running.length);

        for (RunningTrigger trigger : this.running) {
            trigger.readSnapshot(reader);
        }
    }

    private void runTrigger(@NotNull StandardTriggerContext attachedContext, @NotNull RunningTrigger trigger) {
        TriggerDefinition definition = trigger.getDefinition();
        List<ConditionRecord> conditionRecords = definition.getConditions();
        List<ActionRecord> actionRecords = definition.getActions();

        if (trigger.getActionIndex() == 0 && !trigger.isWaiting()) {
            for (int index = 0; index < conditionRecords.size(); index++) {
                ConditionRecord record = conditionRecords.get(index);
                TriggerCondition<StandardTriggerContext, ConditionRecord> condition = this.conditions.find(record.getType());

                if (condition == null || !condition.test(attachedContext, record)) {
                    return;
                }
            }
        }

        for (int index = trigger.getActionIndex(); index < actionRecords.size(); index++) {
            ActionRecord record = actionRecords.get(index);
            TriggerAction<StandardTriggerContext, RunningTrigger, ActionRecord> action = this.actions.find(record.getType());

            if (action != null && !action.execute(attachedContext, trigger, record)) {
                trigger.setActionIndex(index);

                return;
            }
        }

        trigger.setActionIndex(0);
        trigger.setDone(!trigger.isRepeating());
    }
}
