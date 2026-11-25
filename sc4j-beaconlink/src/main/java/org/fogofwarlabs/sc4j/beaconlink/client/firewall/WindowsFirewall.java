package org.fogofwarlabs.sc4j.beaconlink.client.firewall;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads and writes the inbound rules of Windows Firewall through Windows PowerShell.
 * <p>
 * The check reads the firewall policy without elevation: the active network profiles with their firewall switch and default inbound action,
 * and the enabled inbound rules of the program (paths compared without case, environment variables expanded) that cover UDP. A profile with
 * the firewall on blocks the program when a block rule covers it, allows it when an allow rule covers it or its default inbound action
 * allows, and has no rule otherwise; so the rules a cancelled first-run Windows prompt adds block the program. A check is kept for
 * {@value #CHECK_KEEP_MILLIS} ms.
 * <p>
 * The allow request starts one elevated Windows PowerShell through the user's approval. For each program it deletes the program's inbound
 * rules named {@code SC4J (<program>)} and its inbound block rules, then adds inbound UDP and TCP allow rules named {@code SC4J (<program>)}
 * for every network profile with {@code netsh advfirewall}.
 */
final class WindowsFirewall implements InboundFirewall {
    private static final @NotNull Logger LOGGER = Logger.of(WindowsFirewall.class);
    private static final @NotNull String OPERATING_SYSTEM_PROPERTY = "os.name";
    private static final @NotNull String WINDOWS_PREFIX = "windows";
    private static final @NotNull String SYSTEM_ROOT_VARIABLE = "SystemRoot";
    private static final @NotNull String POWERSHELL_PATH = "System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    private static final @NotNull String POWERSHELL_NAME = "powershell.exe";
    private static final @NotNull String CURRENT_LINE = "current";
    private static final @NotNull String PROFILE_LINE = "profile";
    private static final @NotNull String RULE_LINE = "rule";

    private static final long CHECK_TIMEOUT_MILLIS = 30_000L;
    /**
     * The longest time the allow request waits, the user's approval included.
     */
    private static final long REQUEST_TIMEOUT_MILLIS = 600_000L;
    private static final long CHECK_KEEP_MILLIS = 60_000L;
    /**
     * The exit code of an allow request the user declined.
     */
    private static final int EXIT_DECLINED = 1223;
    private static final int ACTION_ALLOW = 1;
    private static final int ENABLED = 1;
    private static final int PROTOCOL_UDP = 17;
    private static final int PROTOCOL_ANY = 256;
    private static final int PROFILE_FIELDS = 4;
    private static final int RULE_FIELDS = 4;

    /**
     * Holds an active network profile as the check read it.
     */
    private static final class NetworkProfile {
        private final int type;
        private final boolean firewallEnabled;
        private final boolean allowsByDefault;

        private NetworkProfile(int type, boolean firewallEnabled, boolean allowsByDefault) {
            this.type            = type;
            this.firewallEnabled = firewallEnabled;
            this.allowsByDefault = allowsByDefault;
        }
    }

    /**
     * Holds an enabled inbound rule of the program as the check read it.
     */
    private static final class ProgramRule {
        private final boolean allows;
        private final int profiles;
        private final int protocol;

        private ProgramRule(boolean allows, int profiles, int protocol) {
            this.allows   = allows;
            this.profiles = profiles;
            this.protocol = protocol;
        }

        private boolean covers(@NotNull NetworkProfile profile) {
            return (this.profiles & profile.type) != 0 && (this.protocol == PROTOCOL_UDP || this.protocol == PROTOCOL_ANY);
        }
    }

    /**
     * Holds a check result and when it was read.
     */
    private static final class KeptCheck {
        private final @NotNull FirewallState state;
        private final long checkedNanos;

        private KeptCheck(@NotNull FirewallState state, long checkedNanos) {
            this.state        = state;
            this.checkedNanos = checkedNanos;
        }
    }

    /**
     * @return {@code true} if the process runs on Windows, {@code false} otherwise
     */
    static boolean isSupported() {
        return System.getProperty(OPERATING_SYSTEM_PROPERTY, "").toLowerCase(Locale.ROOT).startsWith(WINDOWS_PREFIX);
    }

    /**
     * Reads the check's output into a state.
     *
     * @return the state, or {@link FirewallState#UNKNOWN} if the output is incomplete or malformed
     */
    private static @NotNull FirewallState readCheck(@NotNull String output) {
        List<NetworkProfile> profiles = new ArrayList<>();
        List<ProgramRule> rules = new ArrayList<>();
        boolean complete = false;

        try {
            for (String line : output.lines().map(String::strip).toList()) {
                String[] fields = line.split(" ");

                if (fields[0].equals(CURRENT_LINE)) {
                    complete = true;
                } else if (fields[0].equals(PROFILE_LINE) && fields.length == PROFILE_FIELDS) {
                    profiles.add(new NetworkProfile(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]) == ENABLED,
                                                    Integer.parseInt(fields[3]) == ACTION_ALLOW));
                } else if (fields[0].equals(RULE_LINE) && fields.length == RULE_FIELDS) {
                    rules.add(new ProgramRule(Integer.parseInt(fields[1]) == ACTION_ALLOW, Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
                }
            }
        } catch (NumberFormatException exception) {
            return FirewallState.UNKNOWN;
        }

        if (!complete) {
            return FirewallState.UNKNOWN;
        }

        boolean checked = false;
        boolean missing = false;

        for (NetworkProfile profile : profiles) {
            if (!profile.firewallEnabled) {
                continue;
            }

            checked = true;

            if (rules.stream().anyMatch(rule -> !rule.allows && rule.covers(profile))) {
                return FirewallState.BLOCKED;
            }

            missing |= !profile.allowsByDefault && rules.stream().noneMatch(rule -> rule.allows && rule.covers(profile));
        }

        if (!checked) {
            return FirewallState.NOT_NEEDED;
        }

        return missing ? FirewallState.NO_RULE : FirewallState.ALLOWED;
    }

    /**
     * Writes a text as a Windows PowerShell string literal.
     */
    private static @NotNull String quote(@NotNull String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * Encodes a script for the {@code -EncodedCommand} argument of Windows PowerShell: Base64 of its UTF-16LE text.
     */
    private static @NotNull String encode(@NotNull String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static @NotNull String findPowerShell() {
        String systemRoot = System.getenv(SYSTEM_ROOT_VARIABLE);

        return systemRoot == null ? POWERSHELL_NAME : Path.of(systemRoot, POWERSHELL_PATH).toString();
    }


    private final @NotNull CommandRunner runner;
    private final @NotNull String powerShell;
    private final @NotNull Map<@NotNull Path, @NotNull KeptCheck> keptChecks;

    /**
     * Creates a {@link WindowsFirewall} instance.
     *
     * @param runner runs Windows PowerShell
     */
    WindowsFirewall(@NotNull CommandRunner runner) {
        this.runner     = runner;
        this.powerShell = findPowerShell();
        this.keptChecks = new ConcurrentHashMap<>();
    }

    /**
     * Reads what the firewall does with inbound UDP connections to a program on the active network profiles. Blocks while the rules are read.
     *
     * @param program the program's executable, an absolute path
     * @return the state, never {@link FirewallState#CHECKING}
     */
    @Override
    public @NotNull FirewallState check(@NotNull Path program) {
        KeptCheck kept = this.keptChecks.get(program);
        long nowNanos = System.nanoTime();

        if (kept != null && nowNanos - kept.checkedNanos < TimeUnit.MILLISECONDS.toNanos(CHECK_KEEP_MILLIS)) {
            return kept.state;
        }

        String script = "$ErrorActionPreference = 'Stop'\n" +
                        "$program = " + quote(program.toString()) + "\n" +
                        "$policy = New-Object -ComObject HNetCfg.FwPolicy2\n" +
                        "$current = $policy.CurrentProfileTypes\n" +
                        "foreach ($type in 1, 2, 4) {\n" +
                        "    if ($current -band $type) {\n" +
                        "        'profile ' + $type + ' ' + [int]$policy.FirewallEnabled($type) + ' ' + [int]$policy.DefaultInboundAction($type)\n" +
                        "    }\n" +
                        "}\n" +
                        "foreach ($rule in $policy.Rules) {\n" +
                        "    if ($rule.Direction -eq 1 -and $rule.Enabled -and $rule.ApplicationName -and\n" +
                        "        [Environment]::ExpandEnvironmentVariables($rule.ApplicationName) -ieq $program) {\n" +
                        "        'rule ' + $rule.Action + ' ' + $rule.Profiles + ' ' + $rule.Protocol\n" +
                        "    }\n" +
                        "}\n" +
                        "'current ' + $current\n";
        CommandResult result;

        try {
            result = this.runner.run(this.createCommand(script), CHECK_TIMEOUT_MILLIS);
        } catch (IOException exception) {
            LOGGER.debug("BEACON.LINK: the firewall rules cannot be read: {0}", exception.getMessage());

            return FirewallState.UNKNOWN;
        }

        FirewallState state = result.getExitCode() == 0 ? readCheck(result.getOutput()) : FirewallState.UNKNOWN;

        if (state == FirewallState.UNKNOWN) {
            LOGGER.debug("BEACON.LINK: the firewall rules cannot be read (exit code {0}): {1}", result.getExitCode(), result.getOutput().strip());
        } else {
            this.keptChecks.put(program, new KeptCheck(state, nowNanos));
        }

        return state;
    }

    /**
     * Asks the user to allow inbound connections to programs: one elevated process, which the user approves, replaces the rules named
     * {@code SC4J (<program>)} and the inbound block rules of each program with inbound UDP and TCP allow rules of that name for every network
     * profile. Blocks until the process ends.
     *
     * @param programs the programs' executables, absolute paths
     * @return {@code true} if the rules were written, {@code false} if the user declined, the request failed or no request is needed
     */
    @Override
    public boolean requestAllow(@NotNull List<@NotNull Path> programs) {
        if (programs.isEmpty()) {
            return false;
        }

        StringJoiner quoted = new StringJoiner(", ", "@(", ")");

        for (Path program : programs) {
            quoted.add(quote(program.toString()));
        }

        String elevated = "$ErrorActionPreference = 'Stop'\n" +
                          "$policy = New-Object -ComObject HNetCfg.FwPolicy2\n" +
                          "foreach ($program in " + quoted + ") {\n" +
                          "    $name = 'SC4J (' + $program + ')'\n" +
                          "    $stale = @($policy.Rules | Where-Object { $_.Direction -eq 1 -and $_.ApplicationName -and\n" +
                          "        [Environment]::ExpandEnvironmentVariables($_.ApplicationName) -ieq $program -and\n" +
                          "        ($_.Name -eq $name -or $_.Action -eq 0) })\n" +
                          "    foreach ($rule in $stale) {\n" +
                          "        & netsh.exe advfirewall firewall delete rule ('name=' + $rule.Name) dir=in " +
                          "('program=' + $rule.ApplicationName) | Out-Null\n" +
                          "    }\n" +
                          "    foreach ($protocol in 'UDP', 'TCP') {\n" +
                          "        & netsh.exe advfirewall firewall add rule ('name=' + $name) dir=in action=allow ('program=' + $program) " +
                          "('protocol=' + $protocol) profile=any enable=yes | Out-Null\n" +
                          "        if ($LASTEXITCODE -ne 0) {\n" +
                          "            exit 1\n" +
                          "        }\n" +
                          "    }\n" +
                          "}\n" +
                          "exit 0\n";
        String script = "$ErrorActionPreference = 'Stop'\n" +
                        "try {\n" +
                        "    $process = Start-Process -FilePath " + quote(this.powerShell) + " -Verb RunAs -WindowStyle Hidden -Wait -PassThru " +
                        "-ArgumentList " + quote(String.join(" ", this.createArguments(elevated))) + "\n" +
                        "    exit $process.ExitCode\n" +
                        "} catch {\n" +
                        "    exit " + EXIT_DECLINED + "\n" +
                        "}\n";
        CommandResult result;

        try {
            result = this.runner.run(this.createCommand(script), REQUEST_TIMEOUT_MILLIS);
        } catch (IOException exception) {
            LOGGER.info("BEACON.LINK: firewall allow request failed: {0}", exception.getMessage());

            return false;
        }

        this.keptChecks.clear();

        if (result.getExitCode() == EXIT_DECLINED) {
            LOGGER.info("BEACON.LINK: firewall allow request declined");
        } else if (result.getExitCode() != 0) {
            LOGGER.info("BEACON.LINK: firewall allow request failed (exit code {0})", result.getExitCode());
        }

        return result.getExitCode() == 0;
    }

    private @NotNull List<@NotNull String> createCommand(@NotNull String script) {
        List<String> command = new ArrayList<>();

        command.add(this.powerShell);
        command.addAll(this.createArguments(script));

        return command;
    }

    private @NotNull List<@NotNull String> createArguments(@NotNull String script) {
        return List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encode(script));
    }
}
