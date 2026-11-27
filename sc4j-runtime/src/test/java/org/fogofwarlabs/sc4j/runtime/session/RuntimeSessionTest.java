package org.fogofwarlabs.sc4j.runtime.session;

import org.fogofwarlabs.sc4j.base.log.Logging;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountException;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountProblem;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.LearnedPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.engine.replay.EngineReplayFormat;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValuesFile;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.fogofwarlabs.sc4j.runtime.RuntimeMain;
import org.fogofwarlabs.sc4j.runtime.cli.ExitCode;
import org.fogofwarlabs.sc4j.testmod.*;
import org.fogofwarlabs.sc4j.translate.service.nllb.NllbPreset;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Tests the runtime sequence against test mod jars: a normal run with its reverse shutdown, the exit code of each kind of failure, a run that
 * goes on without the launcher link it cannot connect, and the Beacon.Link of a run, which only its command line describes: offered only with a seed source, a game started without the launcher reaches
 * a node on 127.0.0.1 with its account and keeps the node in its peers file, and two games of one account run at once as two connections
 * while the account cannot be deleted.
 */
final class RuntimeSessionTest {
    private static final @NotNull String MOD_ID = "recording";
    private static final @NotNull String MANIFEST = """
                                                    {"format": 1, "id": "recording", "name": {"en": "Recording"}, "version": "1.0.0", "engine": "%s", "entry": "%s",
                                                     "settings": [
                                                       {"key": "dataDirectory", "type": "directory", "required": true, "label": {"en": "Data"}},
                                                       {"key": "headlessCapable", "type": "boolean", "default": true, "label": {"en": "Headless"}}
                                                     ]}
                                                    """;

    /**
     * Two mods that share their game folder setting; it names no recorded installation, so nothing is detected.
     */
    private static final @NotNull String GAME_MANIFEST = """
                                                         {"format": 1, "id": "%s", "name": {"en": "Game mod"}, "version": "1.0.0", "engine": "0.1", "entry": "%s",
                                                          "settings": [{"key": "gameFolder", "type": "directory", "required": true, "label": {"en": "Test game folder"},
                                                                        "check": {"contains": ["game.info"]}, "detect": {"uninstallEntries": ["SC4J Test Game Without Entry"]},
                                                                        "shared": "test-game"},
                                                                       {"key": "headlessCapable", "type": "boolean", "default": true, "label": {"en": "Headless"}}]}
                                                         """;
    private static final @NotNull List<@NotNull String> GAME_MOD_IDS = List.of("game-a", "game-b");
    private static final @NotNull String BEACON_MOD_ID = "beacon-test";
    private static final @NotNull String BEACON_MANIFEST = """
                                                           {"format": 1, "id": "beacon-test", "name": {"en": "Beacon test"}, "version": "1.0.0", "engine": "0.1", "entry": "%s"}
                                                           """;
    private static final @NotNull InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    private static final int STAMP_DIFFICULTY = 16;
    private static final long NODE_POLL_MILLIS = 20L;
    private static final long RUN_SECONDS = 120L;
    private static final long STOP_SECONDS = 10L;
    private static final long TEST_SECONDS = 300L;

    /**
     * The start of an engine replay: the magic {@code SC4R}, the format version {@code 1} and four more bytes, which the format's
     * identification accepts.
     */
    private static final byte @NotNull [] ENGINE_REPLAY_START = {'S', 'C', '4', 'R', 0, 1, 0, 0, 0, 0};

    private static final int RUN_REGISTERED_FIELD = 0;
    private static final int RUN_ADDRESS_FIELD = 1;
    private static final int RUN_PLAYER_KEY_FIELD = 2;
    private static final int RUN_CONNECTION_FIELD = 3;

    @Test
    void run_should_run_the_application_and_close_the_mod(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory, MANIFEST.formatted("0.1", RecordingMod.class.getName()), true, true);
        Path replay = Files.write(directory.resolve("game." + EngineReplayFormat.FILE_EXTENSION), ENGINE_REPLAY_START);
        ExitCode exitCode = run(install, "--headless", "--replay", replay.toString(), "--join", "host:6121", "--", "--skirmish", "maps/x.json");
        Path storage = install.getStorageDirectory(MOD_ID);

        Assertions.assertEquals(ExitCode.SUCCESS, exitCode);
        Assertions.assertEquals(List.of("headless=true", "replay=" + replay.toAbsolutePath(), "join=host:6121",
                                        "arguments=[--skirmish, maps/x.json]", "identity=recording", "units=" + RecordingMod.UNIT_LIMIT,
                                        "replayFormat=" + EngineReplayFormat.NAME, "saveFormats=1"),
                                Files.readAllLines(storage.resolve(RecordingApplication.LAUNCH_FILE)));
        Assertions.assertTrue(Files.isRegularFile(storage.resolve(RecordingMod.CLOSED_FILE)));
        Assertions.assertTrue(Files.readString(install.getLogDirectory(MOD_ID).resolve(Logging.FILE_NAME)).contains("RUNTIME END: exit code 0"));
        Assertions.assertFalse(Logging.isFileEnabled());
    }

    @Test
    void run_should_end_with_configuration_error_for_a_replay_in_none_of_the_mod_formats(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory, MANIFEST.formatted("0.1", RecordingMod.class.getName()), true, true);
        Path foreign = Files.writeString(directory.resolve("foreign.bin"), "not a replay of this mod");

        Assertions.assertEquals(ExitCode.CONFIGURATION_ERROR, run(install, "--replay", foreign.toString()));
        Assertions.assertTrue(Files.readString(install.getLogDirectory(MOD_ID).resolve(Logging.FILE_NAME)).contains("is no replay of the mod"));
        Assertions.assertEquals(ExitCode.CONFIGURATION_ERROR, run(install, "--replay", directory.resolve("missing.sc4r").toString()));
        Assertions.assertFalse(Files.exists(install.getStorageDirectory(MOD_ID).resolve(RecordingApplication.LAUNCH_FILE)));
    }

    @Test
    void run_should_end_with_mod_failure_for_a_broken_manifest_or_engine(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout broken = createInstall(directory.resolve("broken"), "{\"format\": 1, \"id\": ", true, true);
        InstallLayout future = createInstall(directory.resolve("future"), MANIFEST.formatted("1.0", RecordingMod.class.getName()), true, true);

        Assertions.assertEquals(ExitCode.MOD_FAILURE, run(broken));
        Assertions.assertEquals(ExitCode.MOD_FAILURE, run(future));
    }

    @Test
    void run_should_end_with_configuration_error_for_a_missing_required_setting(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory, MANIFEST.formatted("0.1", RecordingMod.class.getName()), false, true);

        Assertions.assertEquals(ExitCode.CONFIGURATION_ERROR, run(install));
        Assertions.assertFalse(Files.exists(install.getStorageDirectory(MOD_ID)));
    }

    @Test
    void run_should_end_with_mod_failure_when_the_mod_refuses_the_installation(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory, MANIFEST.formatted("0.1", RefusingMod.class.getName()), true, true);

        Assertions.assertEquals(ExitCode.MOD_FAILURE, run(install));
        Assertions.assertTrue(Files.readString(install.getLogDirectory(MOD_ID).resolve(Logging.FILE_NAME)).contains("is not supported"));
    }

    @Test
    void run_should_end_with_mod_failure_for_an_invalid_simulation(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory, MANIFEST.formatted("0.1", InvalidSimulationMod.class.getName()), true, true);

        Assertions.assertEquals(ExitCode.MOD_FAILURE, run(install));
        Assertions.assertTrue(Files.readString(install.getLogDirectory(MOD_ID).resolve(Logging.FILE_NAME)).contains("invalid simulation"));
    }

    @Test
    void run_should_end_with_mod_failure_for_headless_without_a_headless_application(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory, MANIFEST.formatted("0.1", RecordingMod.class.getName()), true, false);

        Assertions.assertEquals(ExitCode.MOD_FAILURE, run(install, "--headless"));
        Assertions.assertFalse(Files.exists(install.getStorageDirectory(MOD_ID).resolve(RecordingApplication.LAUNCH_FILE)));
        Assertions.assertTrue(Files.isRegularFile(install.getStorageDirectory(MOD_ID).resolve(RecordingMod.CLOSED_FILE)));
    }

    @Test
    void run_should_hand_a_headless_run_of_an_engine_only_mod_to_its_headless_runner(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout runner = createInstall(directory.resolve("runner"), MANIFEST.formatted("0.1", HeadlessMod.class.getName()), true, true);
        InstallLayout nothing = createInstall(directory.resolve("nothing"), MANIFEST.formatted("0.1", HeadlessMod.class.getName()), true, false);

        Assertions.assertEquals(ExitCode.SUCCESS, run(runner, "--headless"));
        Assertions.assertEquals("headless=true identity=recording beaconLink=false game=null",
                                Files.readString(runner.getStorageDirectory(MOD_ID).resolve(HeadlessMod.RUN_FILE)));
        Assertions.assertFalse(Files.exists(runner.getBeaconLinkDirectory()));

        Files.delete(runner.getStorageDirectory(MOD_ID).resolve(HeadlessMod.RUN_FILE));
        Assertions.assertEquals(ExitCode.MOD_FAILURE, run(runner));
        Assertions.assertTrue(Files.readString(runner.getLogDirectory(MOD_ID).resolve(Logging.FILE_NAME)).contains("has no client"));
        Assertions.assertFalse(Files.exists(runner.getStorageDirectory(MOD_ID).resolve(HeadlessMod.RUN_FILE)));

        Assertions.assertEquals(ExitCode.MOD_FAILURE, run(nothing, "--headless"));
        Assertions.assertTrue(Files.readString(nothing.getLogDirectory(MOD_ID).resolve(Logging.FILE_NAME))
                                   .contains("registered no first scene, headless runner or application"));
    }

    @Test
    void run_should_go_on_without_a_launcher_link_that_cannot_be_connected(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory.resolve("install"), MANIFEST.formatted("0.1", HeadlessMod.class.getName()), true, true);
        Path socket = directory.resolve("launcher-1.sock");

        Assertions.assertEquals(ExitCode.SUCCESS, run(install, "--headless", "--launcher-link", socket.toString()));
        Assertions.assertTrue(Files.readString(install.getLogDirectory(MOD_ID).resolve(Logging.FILE_NAME)).contains("LAUNCHER LINK: none"));
        Assertions.assertTrue(Files.isRegularFile(install.getStorageDirectory(MOD_ID).resolve(HeadlessMod.RUN_FILE)));
    }

    @Test
    void run_should_give_the_mod_an_unavailable_translator_without_an_installed_preset(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory.resolve("install"), MANIFEST.formatted("0.1", HeadlessMod.class.getName()), true, true);
        Path translationFile = install.getStorageDirectory(MOD_ID).resolve(HeadlessMod.TRANSLATION_FILE);
        Path logFile = install.getLogDirectory(MOD_ID).resolve(Logging.FILE_NAME);

        Assertions.assertEquals(ExitCode.SUCCESS, run(install, "--headless"));
        Assertions.assertEquals("UNAVAILABLE", Files.readString(translationFile));
        Assertions.assertTrue(Files.readString(logFile).contains("TRANSLATION: none"));

        Assertions.assertEquals(ExitCode.SUCCESS, run(install, "--headless", "--translation-preset", "unknown-preset"));
        Assertions.assertEquals("UNAVAILABLE", Files.readString(translationFile));
        Assertions.assertTrue(Files.readString(logFile).contains("TRANSLATION: unknown preset unknown-preset"));

        Assertions.assertEquals(ExitCode.SUCCESS, run(install, "--headless", "--translation-preset", NllbPreset.DISTILLED_600M.getId()));
        Assertions.assertEquals("UNAVAILABLE", Files.readString(translationFile), "a preset that is not installed translates nothing");
        Assertions.assertTrue(Files.readString(logFile).contains("TRANSLATION: local preset " + NllbPreset.DISTILLED_600M.getId()));
    }

    @Test
    void run_should_end_with_run_failure_when_the_application_fails(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory, MANIFEST.formatted("0.1", RecordingMod.class.getName()), true, true);

        Assertions.assertEquals(ExitCode.RUN_FAILURE, run(install, "--join", RecordingApplication.FAILING_JOIN));
        Assertions.assertTrue(Files.isRegularFile(install.getStorageDirectory(MOD_ID).resolve(RecordingMod.CLOSED_FILE)));
    }

    @Test
    void run_should_give_every_mod_of_a_shared_setting_the_one_saved_value(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        Path game = Files.createDirectories(directory.resolve("game"));

        Files.createDirectories(install.getModsDirectory());

        for (String modId : GAME_MOD_IDS) {
            TestJarUtils.write(install.getModsDirectory().resolve(modId + ".jar"),
                               TestJarUtils.createEntries(GAME_MANIFEST.formatted(modId, HeadlessMod.class.getName()), HeadlessMod.class));
        }

        Assertions.assertEquals(ExitCode.CONFIGURATION_ERROR, runMod(install, GAME_MOD_IDS.getFirst(), "--headless"));
        Assertions.assertTrue(Files.readString(install.getLogDirectory(GAME_MOD_IDS.getFirst()).resolve(Logging.FILE_NAME)).contains("value.required"));

        Files.writeString(game.resolve("game.info"), "info");
        new SettingValuesFile(install.getSharedSettingsFile()).write(Map.of("test-game", game.toString()));

        for (String modId : GAME_MOD_IDS) {
            Assertions.assertEquals(ExitCode.SUCCESS, runMod(install, modId, "--headless"));
            Assertions.assertEquals("headless=true identity=" + modId + " beaconLink=false game=" + game,
                                    Files.readString(install.getStorageDirectory(modId).resolve(HeadlessMod.RUN_FILE)));
        }
    }

    @Test
    void run_should_offer_beacon_link_only_with_a_seed_source(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = createInstall(directory.resolve("install"), MANIFEST.formatted("0.1", HeadlessMod.class.getName()), true, true);
        Path runFile = install.getStorageDirectory(MOD_ID).resolve(HeadlessMod.RUN_FILE);
        Path seedsFile = Files.writeString(directory.resolve("seeds.json"), "{\"format\": 1, \"nodes\": [\"127.0.0.1:7455\"]}");
        Path peersFile = directory.resolve("peers.json");

        Assertions.assertEquals(ExitCode.SUCCESS, run(install, "--headless"));
        Assertions.assertTrue(Files.readString(runFile).contains("beaconLink=false"), "a guest without a seed source has no Beacon.Link");

        for (List<String> source : List.of(List.of("--seed", "127.0.0.1:7455"), List.of("--seeds", seedsFile.toString()),
                                           List.of("--peers", peersFile.toString()))) {
            List<String> options = new ArrayList<>(source);

            options.add("--headless");
            Assertions.assertEquals(ExitCode.SUCCESS, run(install, options.toArray(String[]::new)), source.toString());
            Assertions.assertTrue(Files.readString(runFile).contains("beaconLink=true"), source.toString());
        }

        Assertions.assertFalse(Files.exists(install.getBeaconLinkDirectory()), "the run reads and writes nothing of the install's Beacon.Link folder");
        Assertions.assertFalse(Files.exists(peersFile), "a run whose mod opens no session writes no peers file");
    }

    @Test
    @Timeout(TEST_SECONDS)
    void a_game_started_with_only_its_options_reaches_a_node_and_keeps_it_in_its_peers_file(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                                     InterruptedException {
        InstallLayout install = createBeaconInstall(directory.resolve("install"));
        Account account = createAccount(directory.resolve("beaconlink"), "Alice");
        Path peersFile = directory.resolve("shared").resolve("peers.json");
        Path folder = directory.resolve("game");

        try (PeerSocket node = openNode()) {
            Process game = startGame(install, folder, "--account", account.getDirectory().toString(),
                                     "--seed", "127.0.0.1:" + node.getLocalAddress().getPort(), "--peers", peersFile.toString());

            try {
                pollUntil(node, () -> !game.isAlive(), "the game ends");
            } finally {
                stop(game);
            }

            Assertions.assertEquals(ExitCode.SUCCESS.getStatus(), game.exitValue(), readOutput(folder));
            Assertions.assertEquals(HexFormat.of().formatHex(account.getKey().getBytes()), readRun(folder)[RUN_PLAYER_KEY_FIELD],
                                    "the game plays with the account's key");
            Assertions.assertTrue(PeerStore.open(peersFile).getPeers().stream().map(LearnedPeer::getKey).toList().contains(node.getPeer().getKey()),
                                  "the game keeps the node it reached in the shared peers file");
        }

        Assertions.assertFalse(Files.exists(install.getBeaconLinkDirectory()), "the game reads and writes nothing of the install's Beacon.Link folder");
    }

    @Test
    @Timeout(TEST_SECONDS)
    void two_games_of_one_account_connect_at_once_and_keep_the_account_from_deletion(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                              InterruptedException {
        InstallLayout install = createBeaconInstall(directory.resolve("install"));
        AccountStore store = AccountStore.of(directory.resolve("beaconlink"));
        Account account = createAccount(directory.resolve("beaconlink"), "Alice");
        Path release = directory.resolve("release");
        List<Path> folders = List.of(directory.resolve("first"), directory.resolve("second"));
        List<Process> games = new ArrayList<>();

        try (PeerSocket node = openNode()) {
            String seed = "127.0.0.1:" + node.getLocalAddress().getPort();

            try {
                for (Path folder : folders) {
                    games.add(startGame(install, folder, "--account", account.getDirectory().toString(), "--seed", seed,
                                        "--", BeaconLinkMod.HOLD_ARGUMENT, release.toString()));
                }

                pollUntil(node, () -> folders.stream().allMatch(folder -> Files.exists(folder.resolve(BeaconLinkMod.RUN_FILE))) ||
                                      games.stream().anyMatch(game -> !game.isAlive()), "both games register");

                String[] first = readRun(folders.getFirst());
                String[] second = readRun(folders.getLast());

                Assertions.assertEquals(first[RUN_PLAYER_KEY_FIELD], second[RUN_PLAYER_KEY_FIELD], "one player");
                Assertions.assertNotEquals(first[RUN_CONNECTION_FIELD], second[RUN_CONNECTION_FIELD], "two connections");
                Assertions.assertTrue(store.isInUse(account));

                AccountException refused = Assertions.assertThrows(AccountException.class, () -> store.delete(account));

                Assertions.assertEquals(AccountProblem.IN_USE, refused.getProblem());

                Files.writeString(release, "");
                pollUntil(node, () -> games.stream().noneMatch(Process::isAlive), "both games end");
            } finally {
                for (Process game : games) {
                    stop(game);
                }
            }

            for (int index = 0; index < games.size(); index++) {
                Assertions.assertEquals(ExitCode.SUCCESS.getStatus(), games.get(index).exitValue(), readOutput(folders.get(index)));
            }
        }

        Assertions.assertFalse(store.isInUse(account));
        store.delete(account);
        Assertions.assertEquals(List.of(), store.list());
    }

    @Test
    void run_should_end_with_configuration_error_for_an_invalid_command_line(@TempDir @NotNull Path directory) {
        Assertions.assertEquals(ExitCode.CONFIGURATION_ERROR, RuntimeSession.run(new String[]{"--headless"}));
        Assertions.assertEquals(ExitCode.CONFIGURATION_ERROR, RuntimeSession.run(new String[]{"--mod", "a.jar", "--fullscreen"}));
        Assertions.assertEquals(ExitCode.CONFIGURATION_ERROR,
                                RuntimeSession.run(new String[]{"--install", directory.toString(), "--mod", directory.resolve("missing.jar").toString()}));
        Assertions.assertEquals(ExitCode.SUCCESS, RuntimeSession.run(new String[]{"--help"}));
    }

    private static @NotNull ExitCode run(@NotNull InstallLayout install, @NotNull String @NotNull ... options) {
        return runMod(install, MOD_ID, options);
    }

    private static @NotNull ExitCode runMod(@NotNull InstallLayout install, @NotNull String modId, @NotNull String @NotNull ... options) {
        List<String> arguments = new ArrayList<>(List.of("--install", install.getRoot().toString(),
                                                         "--mod", install.getModsDirectory().resolve(modId + ".jar").toString()));

        arguments.addAll(List.of(options));

        return RuntimeSession.run(arguments.toArray(String[]::new));
    }

    private static @NotNull InstallLayout createInstall(@NotNull Path root, @NotNull String manifest, boolean withSettings,
                                                        boolean headlessCapable) throws IOException {
        InstallLayout install = InstallLayout.of(root);
        Path data = Files.createDirectories(root.resolve("data"));

        Files.createDirectories(install.getModsDirectory());
        TestJarUtils.write(install.getModsDirectory().resolve(MOD_ID + ".jar"),
                           TestJarUtils.createEntries(manifest, RecordingMod.class, RecordingApplication.class, RefusingMod.class,
                                                      InvalidSimulationMod.class, InvalidSimulationMod.IdleSystem.class, HeadlessMod.class));

        if (withSettings) {
            new SettingValuesFile(install.getSettingsFile(MOD_ID)).write(Map.of("dataDirectory", data.toString(),
                                                                                RecordingMod.HEADLESS_SETTING, headlessCapable));
        }

        return install;
    }

    private static @NotNull InstallLayout createBeaconInstall(@NotNull Path root) throws IOException {
        InstallLayout install = InstallLayout.of(root);

        Files.createDirectories(install.getModsDirectory());
        TestJarUtils.write(install.getModsDirectory().resolve(BEACON_MOD_ID + ".jar"),
                           TestJarUtils.createEntries(BEACON_MANIFEST.formatted(BeaconLinkMod.class.getName()), BeaconLinkMod.class));

        return install;
    }

    private static @NotNull Account createAccount(@NotNull Path beaconLinkDirectory, @NotNull String name) throws IOException {
        IdentityKeyPair keyPair = IdentityKeyPair.generate(new SecureRandom());

        return AccountStore.of(beaconLinkDirectory).create(keyPair, PlayerStamp.solve(keyPair.getPublicKey(), name, STAMP_DIFFICULTY),
                                                           STAMP_DIFFICULTY, 0L);
    }

    private static @NotNull PeerSocket openNode() throws IOException {
        PeerConfiguration configuration = new PeerConfiguration.Builder().setPlayerStampDifficulty(STAMP_DIFFICULTY).build();

        return PeerSocket.open(configuration, IdentityKeyPair.generate(new SecureRandom()), new InetSocketAddress(LOOPBACK, 0), ProtocolClock.SYSTEM,
                               new SecureRandom());
    }

    /**
     * Starts the runtime in a process of its own, as a person starts a game without the launcher: the test mod headless, its storage and log
     * in the given folder, and only the given options for Beacon.Link.
     */
    private static @NotNull Process startGame(@NotNull InstallLayout install, @NotNull Path folder, @NotNull String @NotNull ... options) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> command = new ArrayList<>(List.of(java, "-XX:-UsePerfData", "-cp", System.getProperty("java.class.path"),
                                                       RuntimeMain.class.getName(),
                                                       "--install", install.getRoot().toString(),
                                                       "--mod", install.getModsDirectory().resolve(BEACON_MOD_ID + ".jar").toString(),
                                                       "--storage", folder.toString(), "--log", folder.resolve("logs").toString(), "--headless"));

        Files.createDirectories(folder);
        command.addAll(List.of(options));

        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(folder.resolve("output.txt").toFile()).start();
    }

    private static void pollUntil(@NotNull PeerSocket node, @NotNull BooleanSupplier condition, @NotNull String description) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RUN_SECONDS);

        while (!condition.getAsBoolean()) {
            Assertions.assertTrue(System.nanoTime() < deadline, description);
            node.poll(NODE_POLL_MILLIS);
        }
    }

    private static void stop(@NotNull Process process) throws InterruptedException {
        process.destroy();
        process.waitFor(STOP_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Reads the test mod's run file: {@code registered}, the observed address, the player key and the connection number.
     */
    private static @NotNull String @NotNull [] readRun(@NotNull Path folder) throws IOException {
        Path file = folder.resolve(BeaconLinkMod.RUN_FILE);

        Assertions.assertTrue(Files.exists(file), readOutput(folder));

        String[] run = Files.readString(file, StandardCharsets.UTF_8).split(" ");

        Assertions.assertEquals(BeaconLinkMod.REGISTERED, run[RUN_REGISTERED_FIELD]);
        Assertions.assertTrue(run[RUN_ADDRESS_FIELD].contains(LOOPBACK.getHostAddress()), run[RUN_ADDRESS_FIELD]);

        return run;
    }

    private static @NotNull String readOutput(@NotNull Path folder) throws IOException {
        Path log = folder.resolve("logs").resolve(Logging.FILE_NAME);

        return Files.readString(folder.resolve("output.txt"), StandardCharsets.UTF_8) + (Files.exists(log) ? Files.readString(log) : "");
    }
}
