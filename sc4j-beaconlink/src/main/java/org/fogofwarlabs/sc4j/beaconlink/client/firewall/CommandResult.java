package org.fogofwarlabs.sc4j.beaconlink.client.firewall;

import org.jetbrains.annotations.NotNull;

/**
 * Holds what a command that ended gave: its exit code and its output, standard error included.
 */
final class CommandResult {
    private final int exitCode;
    private final @NotNull String output;

    /**
     * Creates a {@link CommandResult} instance.
     *
     * @param exitCode the exit code
     * @param output   the output, standard error included
     */
    CommandResult(int exitCode, @NotNull String output) {
        this.exitCode = exitCode;
        this.output   = output;
    }

    /**
     * @return the exit code
     */
    int getExitCode() {
        return this.exitCode;
    }

    /**
     * @return the output, standard error included
     */
    @NotNull String getOutput() {
        return this.output;
    }
}
