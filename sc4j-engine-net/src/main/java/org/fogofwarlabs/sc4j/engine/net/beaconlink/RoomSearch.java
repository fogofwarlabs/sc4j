package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Holds one search for the rooms of the run's mod: the rooms of the mod's topic, of every version of the mod. It lists the topic's rooms at
 * every registered node, page by page, keeps the newest signed advertisement of each room and drops withdrawn and expired ones. The rooms of
 * the run's own mod identity are the joinable ones ({@link #getRooms()}); the rooms of another version or content of the mod are listed
 * apart with the refusal their hosts give this run ({@link #getOtherRooms()}). It is complete when every node has answered, failed, or
 * settled without a registration.
 */
public interface RoomSearch {
    /**
     * @return {@code true} if every node has answered or failed, {@code false} otherwise
     */
    boolean isComplete();

    /**
     * Returns the rooms of the run's own mod identity found so far, which this run can join. The list is complete when {@link #isComplete()} is
     * {@code true}.
     *
     * @return the rooms, ordered by host and room number
     */
    @NotNull List<@NotNull FoundRoom> getRooms();

    /**
     * Returns the rooms of another version or content of the run's mod found so far, each with the refusal its host gives this run
     * ({@link FoundRoom#getJoinRefusal()}). The list is complete when {@link #isComplete()} is {@code true}.
     *
     * @return the rooms, ordered by host and room number
     */
    @NotNull List<@NotNull FoundRoom> getOtherRooms();

    /**
     * @return why no node could be searched, or {@code null} while the search runs or if at least one node answered
     */
    @Nullable LinkFailure getFailure();
}
