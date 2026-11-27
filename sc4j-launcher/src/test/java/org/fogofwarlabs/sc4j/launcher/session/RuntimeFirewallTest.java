package org.fogofwarlabs.sc4j.launcher.session;

import org.fogofwarlabs.sc4j.beaconlink.client.firewall.FirewallState;
import org.fogofwarlabs.sc4j.beaconlink.client.firewall.InboundFirewall;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tests the firewall check of the Java program the launcher starts games with through a stand-in firewall: the launcher's own executable is
 * not checked again, a program without a rule is allowed on request and checked again, an allowed program is not requested, and two states
 * stand for the worse one.
 */
final class RuntimeFirewallTest {
    private static final @NotNull Path LAUNCHER = Path.of("SC4J", "SC4J.exe").toAbsolutePath();
    private static final @NotNull Path JAVA = Path.of("SC4J", "java", "bin", "java.exe").toAbsolutePath();
    private static final long WAIT_MILLIS = 5_000L;
    private static final long POLL_MILLIS = 10L;

    /**
     * Answers each check with its state, which an allow request turns into {@link FirewallState#ALLOWED}, and records the requests.
     */
    private static final class StandInFirewall implements InboundFirewall {
        private final @NotNull List<@NotNull List<@NotNull Path>> requests;
        private volatile @NotNull FirewallState state;

        private StandInFirewall(@NotNull FirewallState state) {
            this.requests = new CopyOnWriteArrayList<>();
            this.state    = state;
        }

        @Override
        public @NotNull FirewallState check(@NotNull Path program) {
            return this.state;
        }

        @Override
        public boolean requestAllow(@NotNull List<@NotNull Path> programs) {
            this.requests.add(List.copyOf(programs));
            this.state = FirewallState.ALLOWED;

            return true;
        }
    }

    private static void awaitState(@NotNull RuntimeFirewall firewall, @NotNull FirewallState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;

        while (firewall.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS);
        }

        Assertions.assertEquals(expected, firewall.getState());
    }

    @Test
    void program_that_is_the_launcher_itself_is_not_checked_again() {
        RuntimeFirewall firewall = RuntimeFirewall.create(new StandInFirewall(FirewallState.NO_RULE), LAUNCHER, LAUNCHER);

        Assertions.assertSame(RuntimeFirewall.UNCHECKED, firewall);
        Assertions.assertNull(firewall.getProgram());
        Assertions.assertEquals(FirewallState.NOT_NEEDED, firewall.getState());
    }

    @Test
    void program_without_a_rule_is_allowed_on_request_and_checked_again() throws InterruptedException {
        StandInFirewall standIn = new StandInFirewall(FirewallState.NO_RULE);
        RuntimeFirewall firewall = RuntimeFirewall.create(standIn, JAVA, LAUNCHER);

        Assertions.assertEquals(FirewallState.CHECKING, firewall.getState(), "checking until the first check ended");

        firewall.check();
        awaitState(firewall, FirewallState.NO_RULE);

        firewall.requestAllow();
        awaitState(firewall, FirewallState.ALLOWED);

        Assertions.assertEquals(List.of(List.of(JAVA.normalize())), standIn.requests);
    }

    @Test
    void allowed_program_is_not_requested() throws InterruptedException {
        StandInFirewall standIn = new StandInFirewall(FirewallState.ALLOWED);
        RuntimeFirewall firewall = RuntimeFirewall.create(standIn, JAVA, LAUNCHER);

        firewall.check();
        awaitState(firewall, FirewallState.ALLOWED);
        firewall.requestAllow();

        Assertions.assertEquals(List.of(), standIn.requests);
    }

    @Test
    void two_states_stand_for_the_worse_one() {
        Assertions.assertEquals(FirewallState.BLOCKED, RuntimeFirewall.combine(FirewallState.NO_RULE, FirewallState.BLOCKED));
        Assertions.assertEquals(FirewallState.NO_RULE, RuntimeFirewall.combine(FirewallState.ALLOWED, FirewallState.NO_RULE));
        Assertions.assertEquals(FirewallState.UNKNOWN, RuntimeFirewall.combine(FirewallState.UNKNOWN, FirewallState.CHECKING));
        Assertions.assertEquals(FirewallState.CHECKING, RuntimeFirewall.combine(FirewallState.ALLOWED, FirewallState.CHECKING));
        Assertions.assertEquals(FirewallState.ALLOWED, RuntimeFirewall.combine(FirewallState.NOT_NEEDED, FirewallState.ALLOWED));
        Assertions.assertEquals(FirewallState.NOT_NEEDED, RuntimeFirewall.combine(FirewallState.NOT_NEEDED, FirewallState.NOT_NEEDED));
    }
}
