package org.fogofwarlabs.sc4j.engine.content.requirement.declarative;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.requirement.MissingUnitResult;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementResult;
import org.fogofwarlabs.sc4j.engine.content.requirement.StandardRequirementResult;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Requires the player to own a number of completed units of a unit type, as the progress table counts them. A failed test returns the
 * condition's own {@link MissingUnitResult}, which names the unit type.
 */
public final class CompletedUnitCondition implements RequirementCondition {
    private final @NotNull UnitType unitType;
    private final int count;
    private final @NotNull MissingUnitResult missing;

    /**
     * Creates a {@link CompletedUnitCondition} instance that requires one completed unit.
     *
     * @param unitType the unit type
     */
    public CompletedUnitCondition(@NotNull UnitType unitType) {
        this(unitType, 1);
    }

    /**
     * Creates a {@link CompletedUnitCondition} instance.
     *
     * @param unitType the unit type
     * @param count    the number of completed units required, at least {@code 1}
     * @throws IllegalArgumentException if the count is below {@code 1}
     */
    public CompletedUnitCondition(@NotNull UnitType unitType, int count) {
        if (count < 1) {
            throw Exceptions.newIllegalArgumentException("Invalid required unit count: {0}", count);
        }

        this.unitType = unitType;
        this.count    = count;
        this.missing  = new MissingUnitResult(unitType, count);
    }

    /**
     * Tests this condition.
     *
     * @param content   the content of the game
     * @param player    the player
     * @param actorType the unit type of the acting unit, or {@code null} without one
     * @return {@link StandardRequirementResult#MET} if the player owns enough completed units, otherwise the condition's {@link MissingUnitResult}
     */
    @Override
    public @NotNull RequirementResult test(@NotNull GameContent content, @NotNull Player player, @Nullable UnitType actorType) {
        return content.getProgress().getCompletedUnitCount(player, this.unitType) >= this.count ? StandardRequirementResult.MET : this.missing;
    }
}
