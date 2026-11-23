package org.fogofwarlabs.sc4j.engine.lifecycle;

import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.jetbrains.annotations.NotNull;

/**
 * Releases what a dying unit provides to the terrain around it (for example the ground cover a building spreads), at the point of its death where it
 * stops changing the terrain ({@link UnitLifecycle#TERRAIN_RELEASE}).
 */
@FunctionalInterface
public interface TerrainReleaseHook {
    /**
     * Called while a unit dies, where it stops changing the terrain around it.
     *
     * @param unit     the dying unit
     * @param released {@code true} if the hooks before this one finished their release, {@code false} otherwise; the first hook receives {@code true}
     * @return {@code true} if every release so far is finished, {@code false} if one must be retried before the unit is released
     */
    boolean releaseTerrain(@NotNull Unit unit, boolean released);
}
