package org.fogofwarlabs.sc4j.engine.animation.system;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.animation.*;
import org.fogofwarlabs.sc4j.engine.entity.Image;
import org.fogofwarlabs.sc4j.engine.random.DeterministicRandom;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Runs the animation programs of an {@link AnimationState}: starts animations and updates images one at a time, with
 * reused {@link AnimationContext}s, so running allocates nothing. The state is addressed by the image index
 * ({@link Image#getIndex()}); the context of an update holds the image object, so no instruction looks it up.
 * <p>
 * An update of an object that runs its program does exactly one of these:
 * <ul>
 *     <li>with a wait timer above {@code 0}, counts it down by one and runs no instruction;</li>
 *     <li>otherwise runs instructions from the stored position, each going on at the program's next position
 *     ({@link AnimationProgram#getNextPosition}) unless it jumps, until one returns {@link AnimationStep#WAIT} (the timer
 *     becomes the instruction's ticks minus one and the position the next one) or {@link AnimationStep#END} (the object
 *     stops running, the position is the one after the instruction).</li>
 * </ul>
 * So a {@code Wait} of {@code n} ticks lets the program go on in the {@code n}-th update after the one that ran it.
 * <p>
 * An instruction may start a nested update of another object, or of the same one, on the same runner, for example when it
 * creates an image that runs its first animation at once: each nesting level has its own context, and the outer update
 * goes on from its own position when the nested one returns.
 * <p>
 * A bounded update ({@link #update(Image, InstructionBudget)}) takes one instruction from its budget before each
 * instruction and stores the position of each instruction before running it, so that nested updates see where the object
 * is and a failure leaves the position at the failing instruction; a used-up budget fails with an
 * {@link InstructionBudgetExhaustedException} before the instruction runs.
 * <p>
 * Determinism contract: instructions run in program order, each draws from the game generator with the runner's one
 * random source, and the runner itself draws nothing. An update that runs more instructions than the instruction limit
 * without pausing, or runs past the end of its program, fails with an {@link IllegalStateException}; the stored wait
 * timer is then the one before the update and, for an update without a budget, the stored position too, while call stack
 * changes and what the instructions did to the game remain.
 */
public final class AnimationRunner {
    /**
     * The default number of instructions one update may run before it fails.
     */
    public static final int DEFAULT_INSTRUCTION_LIMIT = 65_536;

    /**
     * The number of contexts a runner creates at first, one per nesting level of updates.
     */
    private static final int INITIAL_CONTEXT_COUNT = 4;

    /**
     * The context of one nesting level of a runner: the running animation of the object being updated at that level.
     */
    private static final class RunnerContext implements AnimationContext {
        private final @NotNull AnimationRunner runner;

        private @Nullable Image image;
        private int index;
        private @Nullable AnimationProgram program;
        private int position;
        private int nextPosition;
        private int waitTicks;
        private @Nullable InstructionBudget budget;

        private RunnerContext(@NotNull AnimationRunner runner) {
            this.runner = runner;

            this.image        = null;
            this.index        = 0;
            this.program      = null;
            this.position     = 0;
            this.nextPosition = 0;
            this.waitTicks    = 1;
            this.budget       = null;
        }

        private void enter(@NotNull Image image, @NotNull AnimationProgram program, @Nullable InstructionBudget budget) {
            this.image   = image;
            this.index   = image.getIndex();
            this.program = program;
            this.budget  = budget;
        }

        private void begin(@NotNull AnimationProgram program, int position) {
            this.position     = position;
            this.nextPosition = program.getNextPosition(position);
            this.waitTicks    = 1;
        }

        /**
         * @return the animated image; the context holds it for the whole update, so an instruction reads no pool
         */
        @Override
        public @NotNull Image getImage() {
            Image running = this.image;

            if (running == null) {
                throw Exceptions.newIllegalStateException("No animation program runs.");
            }

            return running;
        }

        /**
         * @return the running program
         */
        @Override
        public @NotNull AnimationProgram getProgram() {
            AnimationProgram running = this.program;

            if (running == null) {
                throw Exceptions.newIllegalStateException("No animation program runs.");
            }

            return running;
        }

        /**
         * @return the position of the executing instruction in the program
         */
        @Override
        public int getPosition() {
            return this.position;
        }

        /**
         * Sets the position the program continues at after this instruction, instead of the next position.
         *
         * @param position the position, {@code 0} up to the program length minus one
         * @throws IndexOutOfBoundsException if the position is outside the program
         */
        @Override
        public void jump(int position) {
            AnimationProgram running = this.getProgram();

            if (!running.contains(position)) {
                throw Exceptions.newIndexOutOfBoundsException("The jump target {0} lies outside the program {1}.", position, running);
            }

            this.nextPosition = position;
        }

        /**
         * Sets how long a {@link AnimationStep#WAIT} pauses the program: it goes on in the update {@code ticks} updates after
         * this one, and the updates in between only count the wait timer down.
         *
         * @param ticks the number of updates, at least {@code 1}
         * @throws IllegalArgumentException if the ticks are below {@code 1}
         */
        @Override
        public void setWait(int ticks) {
            if (ticks < 1) {
                throw Exceptions.newIllegalArgumentException("A wait lasts at least 1 tick: {0}", ticks);
            }

            this.waitTicks = ticks;
        }

        /**
         * Pushes a return position on the call stack.
         *
         * @param returnPosition the position a later {@link #popCall} returns
         * @throws IllegalStateException if the call stack is full
         */
        @Override
        public void pushCall(int returnPosition) {
            AnimationState state = this.runner.state;
            int depth = state.getCallDepth(this.index);

            if (depth == state.getCallStackCapacity()) {
                throw Exceptions.newIllegalStateException("The call stack of object {0} is full at position {1} of {2}.",
                                                          this.index, this.position, this.program);
            }

            state.setCallEntry(this.index, depth, returnPosition);
            state.setCallDepth(this.index, depth + 1);
        }

        /**
         * Pops the newest return position from the call stack.
         *
         * @return the return position
         * @throws IllegalStateException if the call stack is empty
         */
        @Override
        public int popCall() {
            AnimationState state = this.runner.state;
            int depth = state.getCallDepth(this.index);

            if (depth == 0) {
                throw Exceptions.newIllegalStateException("The call stack of object {0} is empty at position {1} of {2}.",
                                                          this.index, this.position, this.program);
            }

            state.setCallDepth(this.index, depth - 1);

            return state.getCallEntry(this.index, depth - 1);
        }

        /**
         * @return the number of return positions the call stack holds at most
         */
        @Override
        public int getCallStackCapacity() {
            return this.runner.state.getCallStackCapacity();
        }

        /**
         * @return the number of return positions on the call stack
         */
        @Override
        public int getCallDepth() {
            return this.runner.state.getCallDepth(this.index);
        }

        /**
         * Sets the number of return positions on the call stack; the entries keep their values.
         *
         * @param depth the depth, {@code 0} up to {@link #getCallStackCapacity()}
         * @throws IndexOutOfBoundsException if the depth is out of range
         */
        @Override
        public void setCallDepth(int depth) {
            this.runner.state.setCallDepth(this.index, depth);
        }

        /**
         * Returns one entry of the call stack, in use or not.
         *
         * @param level the entry, {@code 0} (the oldest) up to {@link #getCallStackCapacity()} minus one
         * @return the return position of the entry
         * @throws IndexOutOfBoundsException if the level is out of range
         */
        @Override
        public int getCallEntry(int level) {
            return this.runner.state.getCallEntry(this.index, level);
        }

        /**
         * Sets one entry of the call stack, in use or not.
         *
         * @param level    the entry, {@code 0} (the oldest) up to {@link #getCallStackCapacity()} minus one
         * @param position the return position
         * @throws IndexOutOfBoundsException if the level is out of range
         */
        @Override
        public void setCallEntry(int level, int position) {
            this.runner.state.setCallEntry(this.index, level, position);
        }

        /**
         * @return the budget of a bounded update, shared with the nested runs the instruction starts, or {@code null} for an update without a budget
         */
        @Override
        public @Nullable InstructionBudget getInstructionBudget() {
            return this.budget;
        }

        /**
         * @return the random generator of the game
         */
        @Override
        public @NotNull DeterministicRandom getRandom() {
            return this.runner.random;
        }

        /**
         * @return the call source every animation draw uses
         */
        @Override
        public int getRandomSource() {
            return this.runner.randomSource;
        }

        /**
         * @return the owner of the animated objects
         */
        @Override
        public @NotNull AnimationTarget getTarget() {
            return this.runner.target;
        }

        /**
         * @return the effects of the game
         */
        @Override
        public @NotNull AnimationEffects getEffects() {
            return this.runner.effects;
        }
    }

    private final @NotNull AnimationState state;
    private final @NotNull AnimationTarget target;
    private final @NotNull AnimationEffects effects;

    private final @NotNull DeterministicRandom random;
    private final int randomSource;
    private final int instructionLimit;

    private @Nullable RunnerContext @NotNull [] contexts;
    private int depth;

    /**
     * Creates an {@link AnimationRunner} instance.
     *
     * @param state            the state the runner runs
     * @param target           the owner of the animated objects
     * @param effects          the effects of the game
     * @param random           the random generator of the game
     * @param randomSource     the call source of every animation draw
     * @param instructionLimit the number of instructions one update may run, at least {@code 1}
     * @throws IllegalArgumentException if the instruction limit is below {@code 1} or the random source is out of the generator's range
     */
    public AnimationRunner(@NotNull AnimationState state, @NotNull AnimationTarget target, @NotNull AnimationEffects effects,
                           @NotNull DeterministicRandom random, int randomSource, int instructionLimit) {
        if (instructionLimit < 1) {
            throw Exceptions.newIllegalArgumentException("The instruction limit must be positive: {0}", instructionLimit);
        }

        if (randomSource < 0 || randomSource >= random.getSourceCount()) {
            throw Exceptions.newIllegalArgumentException("The random source {0} is outside the generator's 0..{1}.",
                                                         randomSource, random.getSourceCount() - 1);
        }

        this.state   = state;
        this.target  = target;
        this.effects = effects;

        this.random           = random;
        this.randomSource     = randomSource;
        this.instructionLimit = instructionLimit;

        this.contexts = new RunnerContext[INITIAL_CONTEXT_COUNT];
        this.depth    = 0;
    }

    /**
     * @return the state the runner runs
     */
    public @NotNull AnimationState getState() {
        return this.state;
    }


    /**
     * Starts an animation of a set on an image ({@link AnimationState#start}); no instruction runs until the next update.
     *
     * @param image the image
     * @param set   the animation set of the image
     * @param kind  the kind
     * @return {@code true} if the set has the animation and it was started, {@code false} if the set has none and the state is unchanged
     */
    public boolean start(@NotNull Image image, @NotNull AnimationSet set, @NotNull AnimationKind kind) {
        AnimationProgram program = set.getProgram(kind);

        if (program == null) {
            return false;
        }

        this.state.start(image.getIndex(), set, kind.getIndex(), program, set.getStartPosition(kind));

        return true;
    }

    /**
     * Counts the wait timer of an image down if it runs its program and waits: the part of an update that runs no
     * instruction. A caller that prepares something for the instructions of an update (the state they act on) calls this
     * first and skips the preparation for a waiting image.
     *
     * @param image the image
     * @return {@code true} if the image waited and its timer was counted down, {@code false} if an update would run instructions or the image does not run its program
     */
    public boolean countDown(@NotNull Image image) {
        AnimationState animations = this.state;
        int index = image.getIndex();
        int wait = animations.getWait(index);

        if (wait == 0 || !animations.isRunning(index)) {
            return false;
        }

        animations.setWait(index, wait - 1);

        return true;
    }

    /**
     * Updates one image as the class description says, without a budget; an image that does not run its program is left
     * as it is.
     *
     * @param image the image
     * @return {@code false} if an instruction of this update ended the program, {@code true} otherwise
     * @throws IllegalStateException if the update reaches the instruction limit or the end of the program, or an instruction fails
     */
    public boolean update(@NotNull Image image) {
        return this.run(image, null);
    }

    /**
     * Updates one image as the class description says, taking each instruction from a budget; an image that does not
     * run its program is left as it is and takes nothing from the budget.
     *
     * @param image  the image
     * @param budget the budget of this update and of the nested runs its instructions start
     * @return {@code false} if an instruction of this update ended the program, {@code true} otherwise
     * @throws InstructionBudgetExhaustedException if the budget is used up before an instruction
     * @throws IllegalStateException               if the update reaches the instruction limit or the end of the program, or an instruction fails
     */
    public boolean update(@NotNull Image image, @NotNull InstructionBudget budget) {
        return this.run(image, budget);
    }

    private boolean run(@NotNull Image image, @Nullable InstructionBudget budget) {
        AnimationState animations = this.state;
        int index = image.getIndex();

        if (!animations.isRunning(index)) {
            return true;
        }

        int wait = animations.getWait(index);

        if (wait > 0) {
            animations.setWait(index, wait - 1);

            return true;
        }

        AnimationProgram program = animations.getProgram(index);

        if (program == null) {
            throw Exceptions.newIllegalStateException("The running object {0} has no animation program.", index);
        }

        RunnerContext running = this.enterContext();

        running.enter(image, program, budget);

        try {
            int position = animations.getPosition(index);

            for (int count = 0; count < this.instructionLimit; count++) {
                if (position >= program.getLength()) {
                    throw Exceptions.newIllegalStateException("The object {0} ran past the end of the program {1}.", index, program);
                }

                if (budget != null) {
                    animations.storePosition(index, position);

                    if (!budget.consume()) {
                        throw InstructionBudgetExhaustedException.create(index, position);
                    }
                }

                running.begin(program, position);

                AnimationStep step = program.get(position).execute(running);

                position = running.nextPosition;

                if (step == AnimationStep.WAIT) {
                    animations.storePosition(index, position);
                    animations.setWait(index, running.waitTicks - 1);

                    return true;
                }

                if (step == AnimationStep.END) {
                    animations.storeEnd(index, position);

                    return false;
                }
            }

            throw Exceptions.newIllegalStateException("The object {0} ran {1} instructions without a pause in the program {2}.",
                                                      index, this.instructionLimit, program);
        } finally {
            this.depth--;
        }
    }

    /**
     * Takes the context of the next nesting level, creating it the first time the level is reached.
     *
     * @return the context
     */
    private @NotNull RunnerContext enterContext() {
        int level = this.depth;
        RunnerContext[] stack = this.contexts;

        if (level == stack.length) {
            stack         = Arrays.copyOf(stack, level * 2);
            this.contexts = stack;
        }

        RunnerContext context = stack[level];

        if (context == null) {
            context      = new RunnerContext(this);
            stack[level] = context;
        }

        this.depth = level + 1;

        return context;
    }
}
