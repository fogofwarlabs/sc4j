package org.fogofwarlabs.sc4j.engine.animation;

import org.jetbrains.annotations.NotNull;

/**
 * Represents one instruction of an animation program.
 * <p>
 * Contract of every implementation:
 * <ul>
 *     <li>An instruction is immutable and holds no game state: it is created once, when the resources are loaded, and
 *     every animated object of every game runs the same object.</li>
 *     <li>{@link #execute} reads and writes the running animation state only through the context, and the game only
 *     through the target and effects of the context. It allocates nothing.</li>
 *     <li>It runs inside the simulation frame: it draws random values only from {@link AnimationContext#getRandom()} with
 *     {@link AnimationContext#getRandomSource()}, a fixed number of draws in a fixed order for the same state, and uses
 *     no clock and no floating point.</li>
 * </ul>
 */
public abstract class AnimationInstruction {
    /**
     * Creates an {@link AnimationInstruction} instance.
     */
    protected AnimationInstruction() {
    }


    /**
     * Runs this instruction for the animated object of the context.
     *
     * @param context the running animation of one object
     * @return how the program continues
     */
    public abstract @NotNull AnimationStep execute(@NotNull AnimationContext context);
}
