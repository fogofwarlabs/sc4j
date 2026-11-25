package org.fogofwarlabs.sc4j.beaconlink.client.firewall;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

/**
 * Runs a command of the operating system and reads its output.
 */
interface CommandRunner {
    /**
     * Runs a command and waits for it to end.
     *
     * @param command       the program and its arguments
     * @param timeoutMillis the longest time the command may run, in milliseconds
     * @return the command's exit code and output
     * @throws IOException if the command cannot be started or runs longer than the timeout
     */
    @NotNull CommandResult run(@NotNull List<@NotNull String> command, long timeoutMillis) throws IOException;
}
