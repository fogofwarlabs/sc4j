package org.fogofwarlabs.sc4j.engine.content.requirement.declarative;

import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementResult;
import org.fogofwarlabs.sc4j.engine.content.requirement.StandardRequirementResult;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.content.type.UpgradeType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Requires the player to have at least a level of an upgrade.
 */
public final class UpgradeLevelCondition implements RequirementCondition {
    private final @NotNull UpgradeType upgrade;
    private final int level;

    /**
     * Creates an {@link UpgradeLevelCondition} instance.
     *
     * @param upgrade the upgrade
     * @param level   the lowest accepted level
     */
    public UpgradeLevelCondition(@NotNull UpgradeType upgrade, int level) {
        this.upgrade = upgrade;
        this.level   = level;
    }

    /**
     * Tests this condition.
     *
     * @param content   the content of the game
     * @param player    the player
     * @param actorType the unit type of the acting unit, or {@code null} without one
     * @return {@link StandardRequirementResult#MET} if the player has the level, otherwise {@link StandardRequirementResult#MISSING_UPGRADE_LEVEL}
     */
    @Override
    public @NotNull RequirementResult test(@NotNull GameContent content, @NotNull Player player, @Nullable UnitType actorType) {
        return content.getProgress().getUpgradeLevel(player, this.upgrade) >= this.level
               ? StandardRequirementResult.MET
               : StandardRequirementResult.MISSING_UPGRADE_LEVEL;
    }
}
