package org.fogofwarlabs.sc4j.beaconlink.client.firewall;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Tests the Windows Firewall check and allow request through a recording command runner: the state the rules of the active profiles give,
 * the elevated request that replaces the program's rules, and its outcome. No command reaches the operating system.
 */
final class WindowsFirewallTest {
    private static final @NotNull Path PROGRAM = Path.of("C:\\Games\\SC4J's place\\SC4J.exe");
    private static final @NotNull Path OTHER_PROGRAM = Path.of("C:\\Games\\SC4J\\java\\bin\\java.exe");
    private static final @NotNull String ACTIVE_PROFILES = "profile 2 1 0\nprofile 4 1 0\n";
    private static final @NotNull String COMPLETE = "current 6\n";
    private static final @NotNull String ENCODED_COMMAND = "-EncodedCommand";
    private static final int DECLINED = 1223;

    /**
     * Records the commands it runs and answers each with the next queued result.
     */
    private static final class RecordingRunner implements CommandRunner {
        private final @NotNull List<@NotNull List<@NotNull String>> commands;
        private final @NotNull List<@NotNull CommandResult> results;

        private RecordingRunner(@NotNull CommandResult @NotNull ... results) {
            this.commands = new ArrayList<>();
            this.results  = new ArrayList<>(List.of(results));
        }

        @Override
        public @NotNull CommandResult run(@NotNull List<@NotNull String> command, long timeoutMillis) {
            this.commands.add(command);

            return this.results.size() > 1 ? this.results.removeFirst() : this.results.getFirst();
        }
    }

    private static @NotNull FirewallState check(@NotNull String output) {
        return new WindowsFirewall(new RecordingRunner(new CommandResult(0, output))).check(PROGRAM);
    }

    /**
     * Reads the script of a Windows PowerShell command line.
     */
    private static @NotNull String decodeScript(@NotNull List<@NotNull String> command) {
        return new String(Base64.getDecoder().decode(command.get(command.indexOf(ENCODED_COMMAND) + 1)), StandardCharsets.UTF_16LE);
    }

    @Test
    void check_should_read_the_state_of_the_active_profiles() {
        Assertions.assertEquals(FirewallState.NO_RULE, check(ACTIVE_PROFILES + COMPLETE));
        Assertions.assertEquals(FirewallState.ALLOWED, check(ACTIVE_PROFILES + "rule 1 6 17\n" + COMPLETE));
        Assertions.assertEquals(FirewallState.ALLOWED, check(ACTIVE_PROFILES + "rule 1 2147483647 256\n" + COMPLETE), "a rule of every protocol");
        Assertions.assertEquals(FirewallState.NO_RULE, check(ACTIVE_PROFILES + "rule 1 2 17\n" + COMPLETE), "one active profile has no rule");
        Assertions.assertEquals(FirewallState.NO_RULE, check(ACTIVE_PROFILES + "rule 1 6 6\n" + COMPLETE), "a TCP rule does not cover UDP");
        Assertions.assertEquals(FirewallState.ALLOWED, check("profile 2 1 1\n" + COMPLETE), "the profile allows by default");
        Assertions.assertEquals(FirewallState.ALLOWED, check("profile 2 1 0\nprofile 4 0 0\nrule 1 2 17\n" + COMPLETE),
                                "the firewall is off on the other profile");
        Assertions.assertEquals(FirewallState.NOT_NEEDED, check("profile 2 0 0\n" + COMPLETE), "the firewall is off");
    }

    @Test
    void check_should_report_a_block_rule_over_an_allow_rule() {
        Assertions.assertEquals(FirewallState.BLOCKED, check(ACTIVE_PROFILES + "rule 1 2147483647 17\nrule 0 4 17\n" + COMPLETE));
        Assertions.assertEquals(FirewallState.BLOCKED, check(ACTIVE_PROFILES + "rule 0 6 17\nrule 0 6 6\n" + COMPLETE),
                                "the rules of a cancelled first-run prompt");
        Assertions.assertEquals(FirewallState.ALLOWED, check(ACTIVE_PROFILES + "rule 1 6 17\nrule 0 6 6\n" + COMPLETE),
                                "a TCP block rule does not block UDP");
        Assertions.assertEquals(FirewallState.ALLOWED, check("profile 2 1 0\nrule 1 2 17\nrule 0 1 17\n" + COMPLETE),
                                "a block rule of an inactive profile");
    }

    @Test
    void check_should_report_unknown_when_the_rules_cannot_be_read() {
        Assertions.assertEquals(FirewallState.UNKNOWN, check(ACTIVE_PROFILES), "the output ends early");
        Assertions.assertEquals(FirewallState.UNKNOWN, check("profile 2 x 0\n" + COMPLETE), "a malformed line");
        Assertions.assertEquals(FirewallState.UNKNOWN,
                                new WindowsFirewall(new RecordingRunner(new CommandResult(1, ACTIVE_PROFILES + COMPLETE))).check(PROGRAM),
                                "the script failed");
    }

    @Test
    void check_should_read_the_program_rules_without_elevation_and_keep_the_result() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, ACTIVE_PROFILES + COMPLETE));
        WindowsFirewall firewall = new WindowsFirewall(runner);

        firewall.check(PROGRAM);
        firewall.check(PROGRAM);

        Assertions.assertEquals(1, runner.commands.size(), "a second check within the keep time reads nothing");

        String script = decodeScript(runner.commands.getFirst());

        Assertions.assertTrue(script.contains("'C:\\Games\\SC4J''s place\\SC4J.exe'"), script);
        Assertions.assertFalse(script.contains("RunAs"), script);
    }

    @Test
    void request_allow_should_run_one_elevated_process_that_replaces_the_rules_of_each_program() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, ""));
        WindowsFirewall firewall = new WindowsFirewall(runner);

        Assertions.assertTrue(firewall.requestAllow(List.of(PROGRAM, OTHER_PROGRAM)));
        Assertions.assertEquals(1, runner.commands.size());

        String request = decodeScript(runner.commands.getFirst());
        String elevatedArguments = request.substring(request.indexOf("-ArgumentList '") + "-ArgumentList '".length());
        String elevated = decodeScript(List.of(elevatedArguments.substring(0, elevatedArguments.indexOf('\'')).split(" ")));

        Assertions.assertTrue(request.contains("-Verb RunAs"), request);
        Assertions.assertTrue(elevated.contains("@('C:\\Games\\SC4J''s place\\SC4J.exe', 'C:\\Games\\SC4J\\java\\bin\\java.exe')"), elevated);
        Assertions.assertTrue(elevated.contains("$name = 'SC4J (' + $program + ')'"), elevated);
        Assertions.assertTrue(elevated.contains("($_.Name -eq $name -or $_.Action -eq 0)"), "same-named and block rules are deleted: " + elevated);
        Assertions.assertTrue(elevated.contains("delete rule"), elevated);
        Assertions.assertTrue(elevated.contains("foreach ($protocol in 'UDP', 'TCP')"), elevated);
        Assertions.assertTrue(elevated.contains("dir=in action=allow ('program=' + $program) ('protocol=' + $protocol) profile=any enable=yes"),
                              elevated);
    }

    @Test
    void request_allow_should_report_a_declined_request_and_read_the_rules_again() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, ACTIVE_PROFILES + COMPLETE), new CommandResult(DECLINED, ""),
                                                     new CommandResult(0, ACTIVE_PROFILES + "rule 1 6 17\n" + COMPLETE));
        WindowsFirewall firewall = new WindowsFirewall(runner);

        Assertions.assertEquals(FirewallState.NO_RULE, firewall.check(PROGRAM));
        Assertions.assertFalse(firewall.requestAllow(List.of(PROGRAM)));
        Assertions.assertEquals(FirewallState.ALLOWED, firewall.check(PROGRAM), "a request drops the kept check");
        Assertions.assertFalse(firewall.requestAllow(List.of()), "no program asks nothing");
        Assertions.assertEquals(3, runner.commands.size());
    }
}
