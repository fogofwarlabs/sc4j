package org.fogofwarlabs.sc4j.engine.animation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;

/**
 * Counts down the instructions a bounded run may still execute. The caller owns the budget and may share it with the
 * nested runs an instruction starts (an image that creates an image and runs its first animation at once), so that the
 * whole run, nested runs included, stays within one limit; an instruction that hands a limit to another part of the game
 * reads the remaining count ({@link AnimationContext#getInstructionBudget()}).
 * <p>
 * A budget is a mutable counter and allocates nothing while it runs; a caller that runs many bounded runs keeps budgets
 * and resets them ({@link #reset}).
 */
public final class InstructionBudget {
    private int remaining;

    /**
     * Creates an {@link InstructionBudget} instance.
     *
     * @param limit the number of instructions, at least {@code 0}
     * @throws IllegalArgumentException if the limit is negative
     */
    public InstructionBudget(int limit) {
        if (limit < 0) {
            throw Exceptions.newIllegalArgumentException("An instruction budget must not be negative: {0}", limit);
        }

        this.remaining = limit;
    }

    /**
     * @return the number of instructions the budget still allows
     */
    public int getRemaining() {
        return this.remaining;
    }


    /**
     * Sets the number of instructions the budget allows, for a new run.
     *
     * @param limit the number of instructions, at least {@code 0}
     * @throws IllegalArgumentException if the limit is negative
     */
    public void reset(int limit) {
        if (limit < 0) {
            throw Exceptions.newIllegalArgumentException("An instruction budget must not be negative: {0}", limit);
        }

        this.remaining = limit;
    }

    /**
     * Takes one instruction from the budget.
     *
     * @return {@code true} if the budget allowed the instruction and now allows one fewer, {@code false} if it is used up and unchanged
     */
    public boolean consume() {
        if (this.remaining == 0) {
            return false;
        }

        this.remaining--;

        return true;
    }
}
