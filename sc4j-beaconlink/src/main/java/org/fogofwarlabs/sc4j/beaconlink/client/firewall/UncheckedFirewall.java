package org.fogofwarlabs.sc4j.beaconlink.client.firewall;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Answers for an operating system whose firewall is not read: every program needs no check, and nothing is requested.
 */
final class UncheckedFirewall implements InboundFirewall {
    /**
     * Reads what the firewall does with inbound UDP connections to a program on the active network profiles. Blocks while the rules are read.
     *
     * @param program the program's executable, an absolute path
     * @return {@link FirewallState#NOT_NEEDED}
     */
    @Override
    public @NotNull FirewallState check(@NotNull Path program) {
        return FirewallState.NOT_NEEDED;
    }

    /**
     * Asks the user to allow inbound connections to programs; nothing is asked here.
     *
     * @param programs the programs' executables, absolute paths
     * @return {@code false}: no request is needed
     */
    @Override
    public boolean requestAllow(@NotNull List<@NotNull Path> programs) {
        return false;
    }
}
