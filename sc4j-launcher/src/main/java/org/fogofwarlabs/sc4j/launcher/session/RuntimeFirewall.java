package org.fogofwarlabs.sc4j.launcher.session;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.client.firewall.FirewallState;
import org.fogofwarlabs.sc4j.beaconlink.client.firewall.InboundFirewall;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks what the computer's firewall does with connections that other computers start to the Java program the launcher starts games with,
 * and asks the user to allow them ({@link InboundFirewall}). The launcher's own executable is checked by its Beacon.Link session; a Java
 * program that is the launcher's own executable, as in a development install, is not checked again here.
 * <p>
 * The checks and the allow request run on threads of their own; {@link #getState()} tells the last result.
 */
public final class RuntimeFirewall {
    private static final @NotNull Logger LOGGER = Logger.of(RuntimeFirewall.class);
    private static final @NotNull String THREAD_NAME = "sc4j-launcher-firewall";

    /**
     * A {@link RuntimeFirewall} that checks nothing: its state is {@link FirewallState#NOT_NEEDED}.
     */
    public static final @NotNull RuntimeFirewall UNCHECKED = new RuntimeFirewall(null, null);

    /**
     * Creates the {@link RuntimeFirewall} of a Java program with the firewall of the operating system ({@link InboundFirewall#createDefault()}).
     *
     * @param program the Java program the launcher starts games with, an absolute path
     * @return the created {@link RuntimeFirewall} instance, or {@link #UNCHECKED} when the program is the launcher's own executable
     */
    public static @NotNull RuntimeFirewall create(@NotNull Path program) {
        return create(InboundFirewall.createDefault(), program, InboundFirewall.findCurrentProgram());
    }

    /**
     * Creates the {@link RuntimeFirewall} of a Java program.
     *
     * @param firewall       the firewall
     * @param program        the Java program the launcher starts games with, an absolute path
     * @param currentProgram the launcher's own executable, or {@code null} if it is not known
     * @return the created {@link RuntimeFirewall} instance, or {@link #UNCHECKED} when the program is the launcher's own executable
     */
    static @NotNull RuntimeFirewall create(@NotNull InboundFirewall firewall, @NotNull Path program, @Nullable Path currentProgram) {
        Path normalized = program.toAbsolutePath().normalize();

        if (currentProgram != null && normalized.equals(currentProgram.toAbsolutePath().normalize())) {
            return UNCHECKED;
        }

        return new RuntimeFirewall(firewall, normalized);
    }

    /**
     * Gives the state that stands for two states, the worse one: {@link FirewallState#BLOCKED}, then {@link FirewallState#NO_RULE},
     * {@link FirewallState#UNKNOWN}, {@link FirewallState#CHECKING}, {@link FirewallState#ALLOWED} and {@link FirewallState#NOT_NEEDED}.
     *
     * @param first  a state
     * @param second another state
     * @return the worse state
     */
    public static @NotNull FirewallState combine(@NotNull FirewallState first, @NotNull FirewallState second) {
        return getRank(first) >= getRank(second) ? first : second;
    }

    private static int getRank(@NotNull FirewallState state) {
        return switch (state) {
            case NOT_NEEDED -> 0;
            case ALLOWED -> 1;
            case CHECKING -> 2;
            case UNKNOWN -> 3;
            case NO_RULE -> 4;
            case BLOCKED -> 5;
        };
    }


    private final @Nullable InboundFirewall firewall;
    private final @Nullable Path program;
    private final @NotNull AtomicReference<@NotNull FirewallState> state;

    private RuntimeFirewall(@Nullable InboundFirewall firewall, @Nullable Path program) {
        this.firewall = firewall;
        this.program  = program;
        this.state    = new AtomicReference<>(program == null ? FirewallState.NOT_NEEDED : FirewallState.CHECKING);
    }

    /**
     * @return the Java program that is checked, or {@code null} for {@link #UNCHECKED}
     */
    public @Nullable Path getProgram() {
        return this.program;
    }

    /**
     * @return the last result: {@link FirewallState#CHECKING} until the first check ended and while an allow request runs
     */
    public @NotNull FirewallState getState() {
        return this.state.get();
    }

    /**
     * Checks the firewall on a thread of its own.
     */
    public void check() {
        InboundFirewall checked = this.firewall;
        Path checkedProgram = this.program;

        if (checked != null && checkedProgram != null) {
            Thread.ofVirtual().name(THREAD_NAME).start(() -> this.readState(checked, checkedProgram));
        }
    }

    /**
     * Asks the user, on a thread of its own, to allow connections that other computers start to the Java program
     * ({@link InboundFirewall#requestAllow}), then checks the firewall again. Does nothing unless the firewall has no rule for the program or
     * blocks it.
     */
    public void requestAllow() {
        InboundFirewall checked = this.firewall;
        Path checkedProgram = this.program;
        FirewallState current = this.state.get();

        if (checked == null || checkedProgram == null || current != FirewallState.NO_RULE && current != FirewallState.BLOCKED ||
            !this.state.compareAndSet(current, FirewallState.CHECKING)) {
            return;
        }

        LOGGER.info("LAUNCHER: firewall allow requested for {0}", checkedProgram);
        Thread.ofVirtual().name(THREAD_NAME).start(() -> {
            checked.requestAllow(List.of(checkedProgram));
            this.readState(checked, checkedProgram);
        });
    }

    private void readState(@NotNull InboundFirewall checked, @NotNull Path checkedProgram) {
        FirewallState read = checked.check(checkedProgram);

        switch (read) {
            case ALLOWED -> LOGGER.info("LAUNCHER: firewall allows {0}", checkedProgram);
            case NO_RULE -> LOGGER.info("LAUNCHER: firewall has no inbound rule for {0}", checkedProgram);
            case BLOCKED -> LOGGER.info("LAUNCHER: firewall blocks {0}", checkedProgram);
            case UNKNOWN -> LOGGER.info("LAUNCHER: firewall rules for {0} not read", checkedProgram);
            default -> LOGGER.debug("LAUNCHER: firewall needs no check for {0}", checkedProgram);
        }

        this.state.set(read);
    }
}
