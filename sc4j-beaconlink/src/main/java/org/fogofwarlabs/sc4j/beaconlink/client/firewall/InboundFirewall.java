package org.fogofwarlabs.sc4j.beaconlink.client.firewall;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads whether the computer's firewall lets other computers start connections to a program, and asks the user to allow them. A process
 * checks and asks for its own executable ({@link #findCurrentProgram()}), whichever launcher or Java runtime started it.
 * <p>
 * On Windows the inbound rules of Windows Firewall are read without elevation; the allow request runs one elevated process that the user
 * approves. Other operating systems report {@link FirewallState#NOT_NEEDED}.
 */
public interface InboundFirewall {
    /**
     * Creates the {@link InboundFirewall} instance of the operating system the process runs on.
     *
     * @return the created {@link InboundFirewall} instance
     */
    static @NotNull InboundFirewall createDefault() {
        return WindowsFirewall.isSupported() ? new WindowsFirewall(new ProcessCommandRunner()) : new UncheckedFirewall();
    }

    /**
     * Finds the executable of the current process.
     *
     * @return the executable's absolute path, or {@code null} if the operating system does not tell it
     */
    static @Nullable Path findCurrentProgram() {
        String command = ProcessHandle.current().info().command().orElse(null);

        try {
            return command == null ? null : Path.of(command).toAbsolutePath();
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    /**
     * Reads what the firewall does with inbound UDP connections to a program on the active network profiles. Blocks while the rules are read.
     *
     * @param program the program's executable, an absolute path
     * @return the state, never {@link FirewallState#CHECKING}
     */
    @NotNull FirewallState check(@NotNull Path program);

    /**
     * Asks the user to allow inbound connections to programs: one elevated process, which the user approves, replaces the rules named
     * {@code SC4J (<program>)} and the inbound block rules of each program with inbound UDP and TCP allow rules of that name for every network
     * profile. Blocks until the process ends.
     *
     * @param programs the programs' executables, absolute paths
     * @return {@code true} if the rules were written, {@code false} if the user declined, the request failed or no request is needed
     */
    boolean requestAllow(@NotNull List<@NotNull Path> programs);
}
