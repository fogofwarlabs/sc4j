package org.fogofwarlabs.sc4j.engine.animation;

import org.fogofwarlabs.sc4j.engine.entity.Image;
import org.fogofwarlabs.sc4j.engine.random.DeterministicRandom;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gives an instruction the running animation of one object while it executes. The animation system reuses one context
 * for every instruction, so an instruction never keeps it.
 * <p>
 * The running state of an object is its program, its position, its wait timer and its call stack: up to
 * {@link #getCallStackCapacity()} return positions, entry {@code 0} the oldest. The engine instructions use
 * {@link #jump}, {@link #setWait}, {@link #pushCall} and {@link #popCall}; a mod instruction with other call semantics may
 * read and write the call stack directly ({@link #getCallDepth}, {@link #setCallDepth}, {@link #getCallEntry},
 * {@link #setCallEntry}). A jump and a wait take effect when the instruction returns; a call stack change takes effect at
 * once.
 */
public interface AnimationContext {
    /**
     * @return the animated image; the context holds it for the whole update, so an instruction reads no pool
     */
    @NotNull Image getImage();

    /**
     * @return the running program
     */
    @NotNull AnimationProgram getProgram();

    /**
     * @return the position of the executing instruction in the program
     */
    int getPosition();

    /**
     * Sets the position the program continues at after this instruction, instead of the next position.
     *
     * @param position the position, {@code 0} up to the program length minus one
     * @throws IndexOutOfBoundsException if the position is outside the program
     */
    void jump(int position);

    /**
     * Sets how long a {@link AnimationStep#WAIT} pauses the program: it goes on in the update {@code ticks} updates after
     * this one, and the updates in between only count the wait timer down.
     *
     * @param ticks the number of updates, at least {@code 1}
     * @throws IllegalArgumentException if the ticks are below {@code 1}
     */
    void setWait(int ticks);

    /**
     * Pushes a return position on the call stack.
     *
     * @param returnPosition the position a later {@link #popCall} returns
     * @throws IllegalStateException if the call stack is full
     */
    void pushCall(int returnPosition);

    /**
     * Pops the newest return position from the call stack.
     *
     * @return the return position
     * @throws IllegalStateException if the call stack is empty
     */
    int popCall();

    /**
     * @return the number of return positions the call stack holds at most
     */
    int getCallStackCapacity();

    /**
     * @return the number of return positions on the call stack
     */
    int getCallDepth();

    /**
     * Sets the number of return positions on the call stack; the entries keep their values.
     *
     * @param depth the depth, {@code 0} up to {@link #getCallStackCapacity()}
     * @throws IndexOutOfBoundsException if the depth is out of range
     */
    void setCallDepth(int depth);

    /**
     * Returns one entry of the call stack, in use or not.
     *
     * @param level the entry, {@code 0} (the oldest) up to {@link #getCallStackCapacity()} minus one
     * @return the return position of the entry
     * @throws IndexOutOfBoundsException if the level is out of range
     */
    int getCallEntry(int level);

    /**
     * Sets one entry of the call stack, in use or not.
     *
     * @param level    the entry, {@code 0} (the oldest) up to {@link #getCallStackCapacity()} minus one
     * @param position the return position
     * @throws IndexOutOfBoundsException if the level is out of range
     */
    void setCallEntry(int level, int position);

    /**
     * @return the budget of a bounded update, shared with the nested runs the instruction starts, or {@code null} for an update without a budget
     */
    @Nullable InstructionBudget getInstructionBudget();

    /**
     * @return the random generator of the game
     */
    @NotNull DeterministicRandom getRandom();

    /**
     * @return the call source every animation draw uses
     */
    int getRandomSource();

    /**
     * @return the owner of the animated objects
     */
    @NotNull AnimationTarget getTarget();

    /**
     * @return the effects of the game
     */
    @NotNull AnimationEffects getEffects();
}
