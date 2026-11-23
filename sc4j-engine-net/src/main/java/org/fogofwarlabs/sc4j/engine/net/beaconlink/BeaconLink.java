package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Opens Beacon.Link sessions for the run's mod identity and the account the player chose for the run. Nothing is read and no socket is
 * opened before {@link #open} is called, so a mod that never asks for Beacon.Link opens no network. Every session shows the account's name,
 * which is fixed when the account is made: the network refuses any other name for the account's key. A run without an account plays as a
 * guest named {@value #GUEST_NAME}: its key lives only as long as the run, and nothing of it is kept.
 */
public interface BeaconLink {
    /**
     * The name of a run without an account.
     */
    @NotNull String GUEST_NAME = "Guest";
    /**
     * The mod id of the launcher's presence: a mod shows a player whose presence names it as in the launcher.
     */
    @NotNull String LAUNCHER_MOD_ID = "sc4j-launcher";

    /**
     * @return the run's account, whose name the mod shows as the player's name and the sessions show, or {@code null} for a run without an account
     */
    @Nullable LinkAccount getAccount();


    /**
     * Opens a session: reads the account's key, opens one UDP socket and starts registering at the nodes the run's command line names. Other
     * participants see the account's name ({@value #GUEST_NAME} for a run without an account). Every session is a connection of the account of
     * its own ({@link BeaconLinkSession#getParticipant()}), beside the account's other connections. The session belongs to the caller, who
     * polls and closes it.
     *
     * @return the opened session
     * @throws IOException              if the key cannot be read or the socket cannot be opened
     * @throws IllegalArgumentException if the mod identity does not fit a room fingerprint
     */
    @NotNull BeaconLinkSession open() throws IOException;
}
