package org.fogofwarlabs.sc4j.engine.trigger;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Names the player a standard trigger condition or action refers to: the player whose triggers run, or the player of one map slot. The slot is
 * the map's data; it becomes the game's {@link Player} when the trigger runs ({@link #resolve}).
 */
public final class PlayerSelector {
    /**
     * The slot index of the selector of the current player.
     */
    private static final int CURRENT_SLOT = -1;
    /**
     * The selector of the player whose triggers run.
     */
    public static final @NotNull PlayerSelector CURRENT = new PlayerSelector(CURRENT_SLOT);

    /**
     * Creates a {@link PlayerSelector} instance that names the player of a map slot.
     *
     * @param slotIndex the player slot index, at least {@code 0}
     * @return the created {@link PlayerSelector} instance
     * @throws IllegalArgumentException if the slot index is negative
     */
    public static @NotNull PlayerSelector ofSlot(int slotIndex) {
        if (slotIndex < 0) {
            throw Exceptions.newIllegalArgumentException("Invalid player slot of a trigger: {0}", slotIndex);
        }

        return new PlayerSelector(slotIndex);
    }

    private final int slotIndex;

    private PlayerSelector(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    /**
     * @return {@code true} if the selector names the player whose triggers run, {@code false} if it names a slot
     */
    public boolean isCurrent() {
        return this.slotIndex == CURRENT_SLOT;
    }

    /**
     * Returns the player the selector names in a running trigger.
     *
     * @param context the context of the evaluation
     * @return the player, or {@code null} if the selector names the current player outside the triggers of a player, or a slot the game does not have
     */
    public @Nullable Player resolve(@NotNull TriggerContext context) {
        if (this.isCurrent()) {
            return context.getCurrentPlayer();
        }

        return this.slotIndex < context.getGame().getPlayerCount() ? context.getGame().getPlayer(this.slotIndex) : null;
    }
}
