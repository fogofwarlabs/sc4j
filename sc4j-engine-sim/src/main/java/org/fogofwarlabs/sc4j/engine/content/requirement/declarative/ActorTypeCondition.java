package org.fogofwarlabs.sc4j.engine.content.requirement.declarative;

import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementResult;
import org.fogofwarlabs.sc4j.engine.content.requirement.StandardRequirementResult;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Requires the acting unit to be of one of the listed unit types: the producers of a unit, the researchers of an
 * upgrade.
 */
public final class ActorTypeCondition implements RequirementCondition {
    private final @NotNull UnitType @NotNull [] actorTypes;

    /**
     * Creates an {@link ActorTypeCondition} instance.
     *
     * @param actorTypes the unit types that may act, copied
     */
    public ActorTypeCondition(@NotNull UnitType @NotNull ... actorTypes) {
        this.actorTypes = actorTypes.clone();
    }

    /**
     * Tests this condition.
     *
     * @param content   the content of the game
     * @param player    the player
     * @param actorType the unit type of the acting unit, or {@code null} without one
     * @return {@link StandardRequirementResult#MET} if the actor is of a listed type, otherwise {@link StandardRequirementResult#WRONG_ACTOR}
     */
    @Override
    public @NotNull RequirementResult test(@NotNull GameContent content, @NotNull Player player, @Nullable UnitType actorType) {
        for (UnitType allowed : this.actorTypes) {
            if (allowed == actorType) {
                return StandardRequirementResult.MET;
            }
        }

        return StandardRequirementResult.WRONG_ACTOR;
    }
}
