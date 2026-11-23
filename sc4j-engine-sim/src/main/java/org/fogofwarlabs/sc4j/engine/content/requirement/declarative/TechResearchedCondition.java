package org.fogofwarlabs.sc4j.engine.content.requirement.declarative;

import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementResult;
import org.fogofwarlabs.sc4j.engine.content.requirement.StandardRequirementResult;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.content.type.TechType;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Requires the player to have researched a tech.
 */
public final class TechResearchedCondition implements RequirementCondition {
    private final @NotNull TechType tech;

    /**
     * Creates a {@link TechResearchedCondition} instance.
     *
     * @param tech the tech
     */
    public TechResearchedCondition(@NotNull TechType tech) {
        this.tech = tech;
    }

    /**
     * Tests this condition.
     *
     * @param content   the content of the game
     * @param player    the player
     * @param actorType the unit type of the acting unit, or {@code null} without one
     * @return {@link StandardRequirementResult#MET} if the player has researched the tech, otherwise {@link StandardRequirementResult#MISSING_TECH}
     */
    @Override
    public @NotNull RequirementResult test(@NotNull GameContent content, @NotNull Player player, @Nullable UnitType actorType) {
        return content.getProgress().isTechResearched(player, this.tech)
               ? StandardRequirementResult.MET
               : StandardRequirementResult.MISSING_TECH;
    }
}
