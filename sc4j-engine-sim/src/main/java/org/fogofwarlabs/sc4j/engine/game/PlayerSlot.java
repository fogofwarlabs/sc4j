package org.fogofwarlabs.sc4j.engine.game;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes one player slot of a game setup: its index, its controller, its team, its color and its faction.
 */
public final class PlayerSlot {
    /**
     * The multiplier of the hash code.
     */
    private static final int HASH_MULTIPLIER = 31;

    private final int slotIndex;
    private final @NotNull ControllerKind controllerKind;
    private final int team;
    private final int colorIndex;
    private final @Nullable Faction faction;

    /**
     * Creates a {@link PlayerSlot} instance.
     *
     * @param slotIndex      the slot index, at least {@code 0}; units name their owner by it
     * @param controllerKind who controls the slot
     * @param team           the team, at least {@code 0}; slots of one team are allies
     * @param colorIndex     the index of the player color, at least {@code 0}
     * @param faction        the faction the player plays, registered in the game content, or {@code null} for none (a slot that picks its faction later, or a neutral one)
     * @throws IllegalArgumentException if an index or the team is negative
     */
    public PlayerSlot(int slotIndex, @NotNull ControllerKind controllerKind, int team, int colorIndex, @Nullable Faction faction) {
        if (slotIndex < 0 || team < 0 || colorIndex < 0) {
            throw Exceptions.newIllegalArgumentException("Invalid player slot: slot {0}, team {1}, color {2}", slotIndex, team, colorIndex);
        }

        this.slotIndex      = slotIndex;
        this.controllerKind = controllerKind;
        this.team           = team;
        this.colorIndex     = colorIndex;
        this.faction        = faction;
    }

    /**
     * @return the slot index; units name their owner by it
     */
    public int getSlotIndex() {
        return this.slotIndex;
    }

    /**
     * @return who controls the slot
     */
    public @NotNull ControllerKind getControllerKind() {
        return this.controllerKind;
    }

    /**
     * @return the team; slots of one team are allies
     */
    public int getTeam() {
        return this.team;
    }

    /**
     * @return the index of the player color
     */
    public int getColorIndex() {
        return this.colorIndex;
    }

    /**
     * @return the faction the player plays, or {@code null} for none
     */
    public @Nullable Faction getFaction() {
        return this.faction;
    }


    /**
     * Compares two slots.
     *
     * @param other the other object
     * @return {@code true} if the other object is a {@link PlayerSlot} with the same values, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof PlayerSlot slot && this.slotIndex == slot.slotIndex && this.controllerKind == slot.controllerKind &&
               this.team == slot.team && this.colorIndex == slot.colorIndex && this.faction == slot.faction;
    }

    /**
     * @return the hash code of the values
     */
    @Override
    public int hashCode() {
        int hash = this.slotIndex;

        hash = HASH_MULTIPLIER * hash + this.controllerKind.ordinal();
        hash = HASH_MULTIPLIER * hash + this.team;
        hash = HASH_MULTIPLIER * hash + this.colorIndex;
        hash = HASH_MULTIPLIER * hash + ContentType.indexOf(this.faction);

        return hash;
    }

    /**
     * @return the values, for diagnostics
     */
    @Override
    public @NotNull String toString() {
        return "PlayerSlot[" + this.slotIndex + ", " + this.controllerKind + ", team " + this.team + ", color " + this.colorIndex + ", faction " +
               this.faction + "]";
    }
}
