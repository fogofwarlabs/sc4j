package org.fogofwarlabs.sc4j.engine.order;

import org.fogofwarlabs.sc4j.engine.game.Game;
import org.jetbrains.annotations.NotNull;

/**
 * Carries what an {@link OrderBehavior} needs besides its unit while the order system runs it: the game, the instruction limit of the
 * dispatch, and in an order system's own context type, the further values of the current dispatch (for example the owner's controller for
 * this frame).
 * <p>
 * An order system keeps one context per dispatch and fills it before it calls a behavior; a behavior reads it only during
 * the call and does not keep it.
 */
public interface OrderContext {
    /**
     * @return the game whose order system runs the behavior
     */
    @NotNull Game getGame();

    /**
     * @return the instruction limit of each animation program run the behavior starts, which it also passes to the order system when it changes an order
     */
    int getInstructionLimit();
}
