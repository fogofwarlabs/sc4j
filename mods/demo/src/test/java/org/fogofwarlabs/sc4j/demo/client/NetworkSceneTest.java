package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.demo.game.DemoGames;
import org.fogofwarlabs.sc4j.demo.map.DemoMaps;
import org.fogofwarlabs.sc4j.demo.net.DemoNetworkSettings;
import org.fogofwarlabs.sc4j.engine.client.app.ClientScene;
import org.fogofwarlabs.sc4j.engine.client.community.ChatLogBox;
import org.fogofwarlabs.sc4j.engine.client.community.TableBox;
import org.fogofwarlabs.sc4j.engine.client.community.TableRow;
import org.fogofwarlabs.sc4j.engine.client.dialog.ComboBox;
import org.fogofwarlabs.sc4j.engine.client.dialog.Control;
import org.fogofwarlabs.sc4j.engine.client.dialog.Label;
import org.fogofwarlabs.sc4j.engine.client.dialog.ListBox;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.hud.WaitingDialog;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.lobby.RecentMaps;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.*;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.fogofwarlabs.sc4j.engine.net.lobby.LobbyMapState;
import org.fogofwarlabs.sc4j.engine.net.lobby.LobbyMember;
import org.fogofwarlabs.sc4j.engine.replay.EngineReplayFormat;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Tests the demo's network screens offscreen with two clients in one JVM on 127.0.0.1: the host opens a room, the joiner finds it in the
 * room list through the loopback room discovery and joins it, chooses its faction, both say they are ready and the host starts; both clients
 * play the network game in lockstep, leave it with their replays stored, and the host's replay plays to its end in the replay viewer. The
 * network screen, the room and the network game mid-game are saved as sample pictures. When the joiner stalls, the host's waiting dialog
 * names it with the countdown, and its drop button lets the host's game go on alone. Over Beacon.Link with a node on 127.0.0.1, the host
 * opens the Beacon.Link tab and creates a private room with a password at a turn rate (its map becomes a recent map), the joiner's tab
 * lists it as private from the node, a wrong password is refused with its reason and the right one joins; the room names both players with
 * their tags, and both play the started game at the host's turn rate; the tab, the create and password popups and the room are saved as
 * sample pictures. On a Beacon.Link played by hand, the tab shows the channel's users with their mods and latencies, the chat with slash
 * commands and whispers, the channel list and the friends, the rooms with their states and turn rates through their filter, an away status
 * with its text, and the refusal of a node that holds too many devices of the account, which asks nothing; it is saved as sample pictures at
 * two frame sizes. At every window size and scale the chosen tab is lit, the selected room shows the faction's warm band, and the create popup's
 * buttons follow its last row.
 */
final class NetworkSceneTest {
    private static final long UPDATE_MILLISECONDS = 16L;
    private static final long WAIT_SECONDS = 60L;
    private static final int MID_GAME_LOOP = 2400;
    private static final int TEAM_TOLERANCE = 48;
    private static final int TEAM_PIXELS = 20;
    private static final int BLUE = 0x3C78D8;
    private static final long NODE_SEED = 20261108L;
    private static final int FIRST_LOOPS = 100;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;
    private static final int SELF_KEY = 1;
    private static final int KIRA_KEY = 2;
    private static final int TAMSIN_KEY = 3;
    private static final int ARI_KEY = 4;
    private static final long GOOD_MILLISECONDS = 40;
    private static final long FAIR_MILLISECONDS = 150;
    private static final long POOR_MILLISECONDS = 320;
    private static final int TWO_PLAYERS = 2;
    private static final int FOUR_PLAYERS = 4;
    private static final int OBSERVED_PORT = 50000;
    private static final @NotNull String MOD_ID = "demo";
    private static final @NotNull String MOD_VERSION = "0.1.0";
    /**
     * The help's title and its twelve command lines.
     */
    private static final int HELP_LINES = 13;
    private static final int FAST_TURN_RATE = 24;
    private static final int SLOW_TURN_RATE = 8;
    private static final int TURN_RATE_COLUMN = 3;
    /**
     * How far right of a tab's left edge, in logical units, its colour is read: inside the frame, left of the text.
     */
    private static final float TAB_SAMPLE_INSET = 20;
    /**
     * Where in the room table the selected row's colour is read: the middle of the first row under the header, at this share of the width.
     */
    private static final float ROW_MIDDLE = 1.5f;
    private static final float ROW_SAMPLE_SHARE = 0.55f;
    private static final int SAMPLE_SIZE = 4;
    private static final int CHANNELS = 3;
    private static final int RED_CHANNEL = 0;
    private static final int GREEN_CHANNEL = 1;
    private static final int BLUE_CHANNEL = 2;
    private static final int CHANNEL_MASK = 0xFF;
    private static final int LIT_DIFFERENCE = 20;
    /**
     * The largest gap in logical units between the create popup's last row and its buttons.
     */
    private static final float LARGEST_BUTTON_GAP = 48;
    private static final @NotNull String PASSWORD = "pw-4417";
    private static final @NotNull String AWAY_TEXT = "back soon";
    private static final int PERCENT = 100;
    /**
     * The offset from the waiting dialog's bottom right padding corner into its drop button.
     */
    private static final int BUTTON_INSET = 2;
    /**
     * The chat lines before the help: the channel line and the line of the channel setup.
     */
    private static final int LINES_BEFORE_HELP = 2;
    /**
     * The colour of a friend's row in the side table, packed {@code 0xRRGGBBAA}.
     */
    private static final int FRIEND_ROW_COLOR = 0xF0D040FF;

    private static void runUntil(@NotNull String goal, @NotNull BooleanSupplier condition, @NotNull DemoClientScenario first,
                                 @NotNull DemoClientScenario second) {
        runUntil(goal, condition, first, second, () -> {
        });
    }

    /**
     * Runs both clients until a condition holds; the extra poll runs after each update of both, for example the poll of a node.
     */
    private static void runUntil(@NotNull String goal, @NotNull BooleanSupplier condition, @NotNull DemoClientScenario first,
                                 @NotNull DemoClientScenario second, @NotNull Runnable extraPoll) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            first.runUpdate(UPDATE_MILLISECONDS);
            second.runUpdate(UPDATE_MILLISECONDS);
            extraPoll.run();
        }

        Assertions.assertTrue(condition.getAsBoolean(), "the clients did not get there in time: " + goal);
    }

    private static <S extends ClientScene> @NotNull S requireScene(@NotNull DemoClientScenario scenario, @NotNull Class<S> type) {
        return Assertions.assertInstanceOf(type, scenario.getScene());
    }

    private static @NotNull FoundRoom createRoom(int key, @NotNull String name, int capacity, int turnRate, @Nullable JoinRefusal refusal,
                                                 @NotNull LatencyEstimate latency) {
        byte[] bytes = new byte[LinkPlayer.KEY_LENGTH];

        Arrays.fill(bytes, (byte) key);

        return new FoundRoom(LinkParticipant.of(LinkPlayer.of(bytes), 1L), 1,
                             new RoomSettings(name, "Ashen Ford", 1, capacity, new byte[]{(byte) turnRate}), refusal, latency);
    }

    private static @NotNull FoundRoom createPrivateRoom(int key, @NotNull String name, int capacity, @NotNull LatencyEstimate latency) {
        byte[] bytes = new byte[LinkPlayer.KEY_LENGTH];

        Arrays.fill(bytes, (byte) key);

        return new FoundRoom(LinkParticipant.of(LinkPlayer.of(bytes), 1L), 1, new RoomSettings(name, "Ashen Ford", 1, capacity),
                             MOD_ID, MOD_VERSION, bytes, null, latency);
    }

    /**
     * Opens the Beacon.Link tab on a community session played by hand: three users in the channel (one of another mod), a line, three rooms
     * (one of another version) and a node that registered the session at once.
     */
    private static @NotNull BeaconLinkScene openRecordedTab(@NotNull DemoClientScenario scenario) throws IOException {
        scenario.getClient().getScenes().replace(new BeaconLinkScene(scenario.getMenu().getDemo(), scenario.getMenu(), DemoNetworkSettings.ANY_PORTS,
                                                                     ""));
        scenario.runFrame();
        scenario.runFrame();

        return requireScene(scenario, BeaconLinkScene.class);
    }

    private static @NotNull RecordingCommunitySession createCommunity(@NotNull PlayerPresence kira) {
        PlayerPresence tamsin = RecordingCommunitySession.createPresence(TAMSIN_KEY, "Tamsin", "skirmish", "2.0.0", false, PlayerStatus.IN_GAME,
                                                                         LatencyEstimate.of(FAIR_MILLISECONDS));
        PlayerPresence ari = RecordingCommunitySession.createPresence(ARI_KEY, "Ari", MOD_ID, MOD_VERSION, true, PlayerStatus.AWAY,
                                                                      LatencyEstimate.of(POOR_MILLISECONDS));
        RecordingCommunitySession community = new RecordingCommunitySession(RecordingCommunitySession.createPresence(SELF_KEY, "Rowan", MOD_ID,
                                                                                                                     MOD_VERSION, true,
                                                                                                                     PlayerStatus.AVAILABLE));

        community.addSummary("Trade", FOUR_PLAYERS);
        community.setChannelSetup(channel -> {
            channel.addMember(kira);
            channel.addMember(tamsin);
            channel.addMember(ari);
            channel.receiveLine(kira, ChatLineKind.SAY, "anyone for a duel?");
        });

        return community;
    }

    private static @NotNull List<FoundRoom> createRooms() {
        return List.of(createRoom(KIRA_KEY, "Kira's game", TWO_PLAYERS, FAST_TURN_RATE, null, LatencyEstimate.of(GOOD_MILLISECONDS)),
                       createPrivateRoom(ARI_KEY, "Open field", FOUR_PLAYERS, LatencyEstimate.of(POOR_MILLISECONDS)),
                       createRoom(TAMSIN_KEY, "Old rules", TWO_PLAYERS, SLOW_TURN_RATE, JoinRefusal.MOD_VERSION,
                                  LatencyEstimate.of(FAIR_MILLISECONDS)));
    }

    /**
     * Reads the mean red, green and blue of a small square of the last frame at a logical position of its surface.
     */
    private static int @NotNull [] readArea(@NotNull DemoClientScenario scenario, float logicalX, float logicalY) {
        UiSurface surface = scenario.getClient().getSurface();

        Assertions.assertNotNull(surface, "the client drew a frame");

        int[] channels = new int[CHANNELS];
        int left = Math.round(surface.toFramebufferX(logicalX));
        int top = Math.round(surface.toFramebufferY(logicalY));

        for (int y = top; y < top + SAMPLE_SIZE; y++) {
            for (int x = left; x < left + SAMPLE_SIZE; x++) {
                int color = scenario.readColor(x, y);

                channels[RED_CHANNEL] += color >>> Short.SIZE & CHANNEL_MASK;
                channels[GREEN_CHANNEL] += color >>> Byte.SIZE & CHANNEL_MASK;
                channels[BLUE_CHANNEL] += color & CHANNEL_MASK;
            }
        }

        for (int index = 0; index < channels.length; index++) {
            channels[index] /= SAMPLE_SIZE * SAMPLE_SIZE;
        }

        return channels;
    }

    private static int sum(int @NotNull [] channels) {
        return channels[RED_CHANNEL] + channels[GREEN_CHANNEL] + channels[BLUE_CHANNEL];
    }

    private static @NotNull List<String> getRoomNames(@NotNull BeaconLinkScene tab) {
        return tab.getShownRooms().stream().map(room -> room.getSettings().getRoomName()).toList();
    }

    /**
     * Runs both clients until the Beacon.Link tab lists the one room, searching again while it lists none.
     */
    private static void listRoom(@NotNull BeaconLinkScene tab, @NotNull DemoClientScenario host, @NotNull DemoClientScenario joiner,
                                 @NotNull Runnable pollNode) {
        runUntil("the Beacon.Link tab lists the room", () -> {
            if (tab.getShownRooms().isEmpty()) {
                joiner.type("r");
            }

            return tab.getShownRooms().size() == 1;
        }, host, joiner, pollNode);
    }

    /**
     * Types a line into the chat input and sends it with Enter.
     */
    private static void say(@NotNull DemoClientScenario scenario, @NotNull BeaconLinkScene tab, @NotNull String line) {
        tab.requireDialog().setFocused(tab.requireControl(BeaconLinkScene.CHAT_INPUT_ID));
        scenario.type(line);
        scenario.press(Key.ENTER);
        tab.requireDialog().setFocused(null);
    }

    @Test
    void two_clients_meet_in_a_room_play_a_network_game_and_view_its_replay(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                     ManifestException,
                                                                                                                     ModException {
        try (DemoClientScenario host = new DemoClientScenario(directory.resolve("host"));
             DemoClientScenario joiner = new DemoClientScenario(directory.resolve("joiner"))) {
            DemoClient hostDemo = host.getMenu().getDemo();
            DemoClient joinerDemo = joiner.getMenu().getDemo();

            host.getClient().getScenes().replace(new NetworkScene(hostDemo, host.getMenu(), DemoNetworkSettings.ANY_PORTS));
            host.runFrame();

            NetworkScene hostNetwork = requireScene(host, NetworkScene.class);

            Assertions.assertInstanceOf(Label.class, hostNetwork.requireControl(NetworkScene.NAME_ID), "the name is shown, not asked");
            Assertions.assertEquals(BeaconLink.GUEST_NAME, hostNetwork.requireControl(NetworkScene.NAME_ID).getText());
            Assertions.assertFalse(hostNetwork.requireControl(NetworkScene.BEACON_LINK_TAB_ID).isEnabled(), "a guest has no Beacon.Link");
            host.type("h");
            host.runFrame();

            NetworkRoomScene hostRoom = requireScene(host, NetworkRoomScene.class);
            InetSocketAddress discovery = hostRoom.getLobby().getDiscoveryAddress();

            Assertions.assertNotNull(discovery);
            joiner.getClient().getScenes().replace(new NetworkScene(joinerDemo, joiner.getMenu(),
                                                                    new DemoNetworkSettings(DemoNetworkSettings.ANY_PORT, discovery.getPort())));
            joiner.runFrame();

            NetworkScene joinerNetwork = requireScene(joiner, NetworkScene.class);

            runUntil("the room list shows the room", () -> joinerNetwork.getShownRooms().size() == 1, host, joiner);
            joiner.runFrame();
            joiner.saveFrame("network");
            ((ListBox) joinerNetwork.requireControl(NetworkScene.ROOMS_ID)).setSelection(0);
            joiner.type("j");
            joiner.runFrame();

            NetworkRoomScene joinerRoom = requireScene(joiner, NetworkRoomScene.class);

            runUntil("the joiner is in the room with the map", () -> {
                LobbyMember local = joinerRoom.getLobby().getLobby().getLocalMember();

                return local != null && local.getMapState() == LobbyMapState.PRESENT &&
                       hostRoom.getLobby().getLobby().getMembers().size() == TWO_PLAYERS;
            }, host, joiner);
            ((ComboBox) joinerRoom.requireControl(NetworkRoomScene.FACTION_ID)).setSelection(1);
            ((ComboBox) hostRoom.requireControl(NetworkRoomScene.FACTION_ID)).setSelection(0);
            runUntil("both factions are chosen",
                     () -> hostRoom.getLobby().getLobby().getMembers().stream().allMatch(member -> member.getFaction() != null), host, joiner);
            joiner.type("y");
            host.type("y");
            runUntil("everyone is ready", () -> hostRoom.getLobby().getLobby().isReadyToStart(), host, joiner);
            host.runFrame();
            host.saveFrame("network-room");

            TableBox members = Assertions.assertInstanceOf(TableBox.class, hostRoom.requireControl(NetworkRoomScene.MEMBERS_ID),
                                                           "the room shows its members in a table");

            Assertions.assertEquals(List.of("Slot 1", "Dawnhold", "Ready"), members.getRows().getFirst().getCells().subList(1, 4));

            host.type("s");
            host.runUpdate(UPDATE_MILLISECONDS);
            Assertions.assertInstanceOf(DemoGameScene.class, host.getScene(), () -> hostRoom.requireControl(NetworkRoomScene.STATUS_ID).getText());
            runUntil("both clients play the game", () -> host.getScene() instanceof DemoGameScene && joiner.getScene() instanceof DemoGameScene,
                     host, joiner);

            DemoGameScene hostGame = requireScene(host, DemoGameScene.class);
            DemoGameScene joinerGame = requireScene(joiner, DemoGameScene.class);
            Game hostState = hostGame.getGame();
            Game joinerState = joinerGame.getGame();

            Assertions.assertNotNull(hostState);
            Assertions.assertNotNull(joinerState);
            Assertions.assertNotNull(hostGame.getNetwork());
            Assertions.assertSame(joinerDemo.getContent().getCindermaw(),
                                  joinerState.getSetup().getPlayers().get(DemoGames.SECOND_SLOT).getFaction());
            runUntil("both games reach the middle",
                     () -> hostState.getClock().getFrame() >= MID_GAME_LOOP && joinerState.getClock().getFrame() >= MID_GAME_LOOP, host, joiner);
            Assertions.assertEquals(0, hostGame.getNetwork().getLockstep().getDesyncMonitor().getMismatchCount(), "no desync");
            host.runFrame();
            host.saveFrame("network-game");
            Assertions.assertTrue(host.countPixels(BLUE, TEAM_TOLERANCE) >= TEAM_PIXELS, "the host's units in their blue");

            DemoGameScreen joinerScreen = joinerGame.getScreen();
            DemoGameScreen hostScreen = hostGame.getScreen();

            Assertions.assertNotNull(joinerScreen);
            Assertions.assertNotNull(hostScreen);
            joinerScreen.runCommand("/leave");
            host.runUpdate(UPDATE_MILLISECONDS);
            hostScreen.runCommand("/leave");
            host.runFrame();
            joiner.runFrame();

            String replayName = "game-" + hostState.getSetup().getSeed() + "." + EngineReplayFormat.FILE_EXTENSION;
            Path hostReplay = directory.resolve("host").resolve(DemoClient.REPLAY_FOLDER).resolve(replayName);

            Assertions.assertTrue(Files.isRegularFile(hostReplay), "the host stored the network game's replay");
            Assertions.assertTrue(Files.isRegularFile(directory.resolve("joiner").resolve(DemoClient.REPLAY_FOLDER).resolve(replayName)),
                                  "the joiner stored the network game's replay");
            host.getClient().getScenes().replace(new ReplayViewerScene(hostDemo, host.getMenu(), hostReplay));
            host.runFrame();

            ReplayViewerScene viewer = requireScene(host, ReplayViewerScene.class);
            DemoGameScreen viewerScreen = viewer.getScreen();

            Assertions.assertNotNull(viewerScreen);
            Assertions.assertTrue(viewer.getLoopCount() >= MID_GAME_LOOP);
            viewerScreen.runCommand("/seek " + viewer.getLoopCount());
            Assertions.assertEquals(viewer.getLoopCount(), viewer.getLoop(), "the network game's replay plays to its end");
        }
    }

    @Test
    void a_stalled_machine_shows_in_the_waiting_dialog_and_its_drop_lets_the_game_go_on(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                                 ManifestException,
                                                                                                                                 ModException {
        try (DemoClientScenario host = new DemoClientScenario(directory.resolve("host"));
             DemoClientScenario joiner = new DemoClientScenario(directory.resolve("joiner"))) {
            host.getClient().getScenes().replace(new NetworkScene(host.getMenu().getDemo(), host.getMenu(), DemoNetworkSettings.ANY_PORTS));
            host.runFrame();
            host.type("h");
            host.runFrame();

            NetworkRoomScene hostRoom = requireScene(host, NetworkRoomScene.class);
            InetSocketAddress discovery = hostRoom.getLobby().getDiscoveryAddress();

            Assertions.assertNotNull(discovery);
            joiner.getClient().getScenes().replace(new NetworkScene(joiner.getMenu().getDemo(), joiner.getMenu(),
                                                                    new DemoNetworkSettings(DemoNetworkSettings.ANY_PORT, discovery.getPort())));
            joiner.runFrame();

            NetworkScene joinerNetwork = requireScene(joiner, NetworkScene.class);

            runUntil("the room list shows the room", () -> joinerNetwork.getShownRooms().size() == 1, host, joiner);
            ((ListBox) joinerNetwork.requireControl(NetworkScene.ROOMS_ID)).setSelection(0);
            joiner.type("j");
            joiner.runFrame();

            NetworkRoomScene joinerRoom = requireScene(joiner, NetworkRoomScene.class);

            runUntil("the joiner is in the room with the map", () -> {
                LobbyMember local = joinerRoom.getLobby().getLobby().getLocalMember();

                return local != null && local.getMapState() == LobbyMapState.PRESENT &&
                       hostRoom.getLobby().getLobby().getMembers().size() == TWO_PLAYERS;
            }, host, joiner);
            ((ComboBox) joinerRoom.requireControl(NetworkRoomScene.FACTION_ID)).setSelection(1);
            ((ComboBox) hostRoom.requireControl(NetworkRoomScene.FACTION_ID)).setSelection(0);
            runUntil("both factions are chosen",
                     () -> hostRoom.getLobby().getLobby().getMembers().stream().allMatch(member -> member.getFaction() != null), host, joiner);
            joiner.type("y");
            host.type("y");
            runUntil("everyone is ready", () -> hostRoom.getLobby().getLobby().isReadyToStart(), host, joiner);
            host.type("s");
            host.runUpdate(UPDATE_MILLISECONDS);
            runUntil("both clients play the game", () -> host.getScene() instanceof DemoGameScene && joiner.getScene() instanceof DemoGameScene,
                     host, joiner);

            DemoGameScene hostGame = requireScene(host, DemoGameScene.class);
            Game hostState = hostGame.getGame();
            DemoGameScreen hostScreen = hostGame.getScreen();

            Assertions.assertNotNull(hostState);
            Assertions.assertNotNull(hostScreen);
            runUntil("the game runs", () -> hostState.getClock().getFrame() >= FIRST_LOOPS, host, joiner);

            WaitingDialog dialog = hostScreen.getScreen().getWaitingDialog();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

            Assertions.assertNotNull(dialog, "a network game has a waiting dialog");
            Assertions.assertFalse(dialog.isShown(), "nobody is overdue while both machines run");

            while (!dialog.isShown() && System.nanoTime() < deadline) {
                host.runUpdate(UPDATE_MILLISECONDS);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(UPDATE_MILLISECONDS));
            }

            Assertions.assertTrue(dialog.isShown(), "the host waits for the stalled joiner");

            int stalledFrame = hostState.getClock().getFrame();

            host.runFrame();
            host.saveFrame("network-waiting");

            float scale = host.getClient().getDisplayScale().getScale();
            float buttonX = dialog.getX() + dialog.getWidth() - dialog.getSkin().getDialogPadding() - BUTTON_INSET;
            float buttonY = dialog.getY() + dialog.getHeight() - dialog.getSkin().getDialogPadding() - BUTTON_INSET;

            Assertions.assertTrue(dialog.isDropButtonAt(buttonX, buttonY));
            host.click(PointerButton.LEFT, buttonX * scale, buttonY * scale);

            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

            while (hostState.getClock().getFrame() <= stalledFrame + FIRST_LOOPS && System.nanoTime() < deadline) {
                host.runUpdate(UPDATE_MILLISECONDS);
            }

            Assertions.assertTrue(hostState.getClock().getFrame() > stalledFrame + FIRST_LOOPS, "the host's game goes on without the joiner");
            Assertions.assertFalse(dialog.isShown());
            Assertions.assertNotNull(hostGame.getNetwork());
            Assertions.assertEquals(1, hostGame.getNetwork().getLockstep().getDepartures().size(), "the joiner is dropped");
        }
    }

    @Test
    void two_clients_meet_in_a_private_beacon_link_room_with_its_password_and_turn_rate(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                                 ManifestException,
                                                                                                                                 ModException {
        try (PeerSocket node = PeerSocket.open(ClientScenario.createNodeConfiguration(), IdentityKeyPair.generate(new SecureRandom()),
                                               new InetSocketAddress(DemoNetworkSettings.LOOPBACK, DemoNetworkSettings.ANY_PORT),
                                               ProtocolClock.SYSTEM, new SplittableRandom(NODE_SEED))) {
            String nodeAddress = DemoNetworkSettings.LOOPBACK + ':' + node.getLocalAddress().getPort();

            try (DemoClientScenario host = new DemoClientScenario(directory.resolve("host"), nodeAddress, "Raynor");
                 DemoClientScenario joiner = new DemoClientScenario(directory.resolve("joiner"), nodeAddress, "Kerrigan")) {
                DemoClient hostDemo = host.getMenu().getDemo();
                DemoClient joinerDemo = joiner.getMenu().getDemo();
                Runnable pollNode = () -> {
                    try {
                        node.poll(0L);
                    } catch (IOException exception) {
                        Assertions.fail(exception);
                    }
                };

                host.getClient().getScenes().replace(new NetworkScene(hostDemo, host.getMenu(), DemoNetworkSettings.ANY_PORTS));
                host.runFrame();

                NetworkScene hostNetwork = requireScene(host, NetworkScene.class);

                Assertions.assertEquals(hostDemo.getTaggedPlayerName(), hostNetwork.requireControl(NetworkScene.NAME_ID).getText());
                Assertions.assertTrue(hostDemo.getTaggedPlayerName().startsWith("Raynor#"), "the menus show Name#tag");
                Assertions.assertTrue(hostNetwork.requireControl(NetworkScene.BEACON_LINK_TAB_ID).isEnabled());
                host.saveFrame("network-account");
                host.type("b");
                host.runFrame();

                BeaconLinkScene hostTab = requireScene(host, BeaconLinkScene.class);
                BeaconLinkSession hostSession = hostTab.getSession();

                Assertions.assertInstanceOf(Label.class, hostTab.requireControl(BeaconLinkScene.NAME_ID), "the name is shown, not asked");
                Assertions.assertEquals(hostDemo.getTaggedPlayerName(), hostTab.requireControl(BeaconLinkScene.NAME_ID).getText());

                Assertions.assertNotNull(hostSession, "the tab opens a Beacon.Link session");
                runUntil("the host registers at the node", () -> hostSession.getObservedAddress() != null, host, joiner, pollNode);
                host.type("h");
                host.runFrame();

                DemoPopup create = hostTab.getPopup();

                Assertions.assertNotNull(create, "Host asks for the room first");
                Assertions.assertEquals("Raynor's game", create.requireControl(BeaconLinkScene.CREATE_NAME_ID).getText());
                Assertions.assertFalse(create.requireControl(BeaconLinkScene.CREATE_PASSWORD_ID).isVisible(), "a public room has no password");
                ((ComboBox) create.requireControl(BeaconLinkScene.CREATE_TURN_RATE_ID))
                        .setSelection(DemoNetworkSettings.TURN_RATES.indexOf(FAST_TURN_RATE));
                ((ComboBox) create.requireControl(BeaconLinkScene.CREATE_PRIVACY_ID)).setSelection(1);
                host.press(Key.ENTER);

                Assertions.assertTrue(create.isOpen(), "a private room needs a password");

                host.runFrame();
                create.requireControl(BeaconLinkScene.CREATE_PASSWORD_ID).setText(PASSWORD);
                host.runFrame();
                host.saveFrame("network-beaconlink-create");

                Assertions.assertTrue(create.requireControl(BeaconLinkScene.CREATE_PASSWORD_ID).isVisible(), "a private room shows its password");
                host.press(Key.ENTER);
                host.runFrame();

                NetworkRoomScene hostRoom = requireScene(host, NetworkRoomScene.class);

                Assertions.assertFalse(create.isOpen());
                Assertions.assertTrue(hostRoom.getLobby().isOverBeaconLink());
                Assertions.assertEquals(List.of(DemoMaps.ASHEN_FORD),
                                        RecentMaps.read(directory.resolve("host").resolve(BeaconLinkScene.RECENT_MAPS_FILE)).getMaps(),
                                        "the hosted map is a recent map");
                Assertions.assertEquals("Turn rate " + FAST_TURN_RATE, hostRoom.requireControl(NetworkRoomScene.TURN_RATE_ID).getText());
                joiner.getClient().getScenes().replace(new BeaconLinkScene(joinerDemo, joiner.getMenu(), DemoNetworkSettings.ANY_PORTS, ""));
                joiner.runFrame();

                BeaconLinkScene firstTab = requireScene(joiner, BeaconLinkScene.class);

                listRoom(firstTab, host, joiner, pollNode);
                joiner.runFrame();
                joiner.saveFrame("network-beaconlink");

                Assertions.assertEquals("Private",
                                        ((TableBox) firstTab.requireControl(BeaconLinkScene.ROOMS_ID)).getRows().getFirst().getCells().getLast());
                ((TableBox) firstTab.requireControl(BeaconLinkScene.ROOMS_ID)).setSelection(0);
                joiner.type("j");

                DemoPopup wrongPrompt = firstTab.getPopup();

                Assertions.assertNotNull(wrongPrompt, "a private room asks for its password");
                joiner.type("guess");
                joiner.runFrame();
                joiner.saveFrame("network-beaconlink-password");
                joiner.press(Key.ENTER);
                joiner.runFrame();
                runUntil("the wrong password is refused", () -> joiner.getScene() instanceof BeaconLinkScene tab && tab != firstTab, host, joiner,
                         pollNode);

                BeaconLinkScene secondTab = requireScene(joiner, BeaconLinkScene.class);

                Assertions.assertEquals(NetworkRoomScene.WRONG_PASSWORD_TEXT, secondTab.requireControl(BeaconLinkScene.STATUS_ID).getText());
                listRoom(secondTab, host, joiner, pollNode);
                ((TableBox) secondTab.requireControl(BeaconLinkScene.ROOMS_ID)).setSelection(0);
                joiner.type("j");
                Assertions.assertNotNull(secondTab.getPopup());
                joiner.type(PASSWORD);
                joiner.press(Key.ENTER);
                joiner.runFrame();

                NetworkRoomScene joinerRoom = requireScene(joiner, NetworkRoomScene.class);

                runUntil("the joiner is in the room with the map", () -> {
                    LobbyMember local = joinerRoom.getLobby().getLobby().getLocalMember();

                    return local != null && local.getMapState() == LobbyMapState.PRESENT &&
                           hostRoom.getLobby().getLobby().getMembers().size() == TWO_PLAYERS;
                }, host, joiner, pollNode);
                Assertions.assertEquals(FAST_TURN_RATE, joinerRoom.getLobby().getTurnRate(), "the joiner knows the room's turn rate");
                joiner.type("y");
                host.type("y");
                runUntil("everyone is ready", () -> hostRoom.getLobby().getLobby().isReadyToStart(), host, joiner, pollNode);
                host.runFrame();
                host.saveFrame("network-beaconlink-room");

                Assertions.assertEquals(List.of(hostDemo.getTaggedPlayerName(), joinerDemo.getTaggedPlayerName()),
                                        hostRoom.getLobby().getLobby().getMembers().stream().map(LobbyMember::getName).toList(),
                                        "the room names the players by their account names with their tags");
                host.type("s");
                host.runUpdate(UPDATE_MILLISECONDS);
                runUntil("both clients play the game", () -> host.getScene() instanceof DemoGameScene && joiner.getScene() instanceof DemoGameScene,
                         host, joiner, pollNode);

                DemoGameScene hostGame = requireScene(host, DemoGameScene.class);
                DemoGameScene joinerGame = requireScene(joiner, DemoGameScene.class);
                Game hostState = hostGame.getGame();

                Assertions.assertNotNull(hostState);
                Assertions.assertNotNull(hostGame.getNetwork());
                Assertions.assertNotNull(joinerGame.getNetwork());
                Assertions.assertEquals(DemoNetworkSettings.createSchedule(FAST_TURN_RATE).getFramesPerTurn(),
                                        joinerGame.getNetwork().getLockstep().getSchedule().getFramesPerTurn(),
                                        "the game runs at the host's turn rate");
                runUntil("the game runs", () -> hostState.getClock().getFrame() >= FIRST_LOOPS, host, joiner, pollNode);
                Assertions.assertEquals(0, hostGame.getNetwork().getLockstep().getDesyncMonitor().getMismatchCount(), "no desync");
            }
        }
    }

    @Test
    void beacon_link_tab_shows_the_channel_chat_friends_and_filtered_rooms_of_a_recorded_session(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                                          ManifestException,
                                                                                                                                          ModException {
        PlayerPresence kira = RecordingCommunitySession.createPresence(KIRA_KEY, "Kira", MOD_ID, MOD_VERSION, true, PlayerStatus.AVAILABLE,
                                                                       LatencyEstimate.of(GOOD_MILLISECONDS));
        RecordingCommunitySession community = createCommunity(kira);
        RecordingBeaconLink link = new RecordingBeaconLink(community, createRooms(),
                                                           new InetSocketAddress(DemoNetworkSettings.LOOPBACK, OBSERVED_PORT),
                                                           community.getSelf().getName());

        try (DemoClientScenario scenario = new DemoClientScenario(directory.resolve("tab"), link, DemoClientScenario.WIDTH,
                                                                  DemoClientScenario.HEIGHT)) {
            BeaconLinkScene tab = openRecordedTab(scenario);
            ChatLogBox log = (ChatLogBox) tab.requireControl(BeaconLinkScene.CHAT_LOG_ID);
            TableBox side = (TableBox) tab.requireControl(BeaconLinkScene.SIDE_ID);
            String channel = tab.getDemo().getClient().getLaunch().getIdentity().getModId() + "-1";

            Assertions.assertNotNull(tab.getChat(), "the tab enters the chat once the node registered the session");
            Assertions.assertEquals(List.of("Kira's game", "Open field", "Old rules"), getRoomNames(tab));
            Assertions.assertEquals(List.of("Open", "Private", "Closed"),
                                    ((TableBox) tab.requireControl(BeaconLinkScene.ROOMS_ID)).getRows().stream().map(row -> row.getCells().getLast())
                                                                                             .toList(), "the rooms show their states");

            List<String> turnRates = ((TableBox) tab.requireControl(BeaconLinkScene.ROOMS_ID)).getRows().stream()
                                                                                              .map(row -> row.getCells().get(TURN_RATE_COLUMN))
                                                                                              .toList();

            Assertions.assertEquals(List.of(Integer.toString(FAST_TURN_RATE), Integer.toString(DemoNetworkSettings.DEFAULT_TURN_RATE),
                                            Integer.toString(SLOW_TURN_RATE)), turnRates, "the rooms show their turn rates");
            Assertions.assertEquals(List.of("Channel: " + channel, kira.getTaggedName() + ": anyone for a duel?"), log.getTexts());
            Assertions.assertEquals(channel + " (4)", tab.requireControl(BeaconLinkScene.CHANNEL_ID).getText());
            Assertions.assertEquals(List.of("Rowan", "Ari", "Kira", "Tamsin"),
                                    side.getRows().stream().map(row -> row.getCells().getFirst().split("#")[0]).toList());
            Assertions.assertEquals(LatencyClass.GOOD, side.getRows().get(2).getLatency());
            Assertions.assertEquals("skirmish 2.0.0, Other version", side.getRows().get(3).getCells().get(1));

            say(scenario, tab, "/help");
            say(scenario, tab, "gg");
            community.receiveWhisper(kira, "ready?");
            side.setSelection(2);
            scenario.type("a");
            scenario.runFrame();
            scenario.saveFrame("network-beaconlink-chat");

            List<String> texts = log.getTexts();

            Assertions.assertEquals("Commands", texts.get(LINES_BEFORE_HELP));
            Assertions.assertEquals(List.of(community.getSelf().getTaggedName() + ": gg", "From " + kira.getTaggedName() + ": ready?",
                                            "Friend added: " + kira.getTaggedName()),
                                    texts.subList(LINES_BEFORE_HELP + HELP_LINES, texts.size()));
            Assertions.assertEquals(List.of(kira.getPlayer()), community.getFriends());

            Assertions.assertEquals(kira.getTaggedName(), side.getRows().get(1).getCells().getFirst(), "a friend comes right after this player");
            side.setSelection(1);
            scenario.type("w");
            Assertions.assertEquals("/w " + kira.getTaggedName() + " ", tab.requireControl(BeaconLinkScene.CHAT_INPUT_ID).getText());
            scenario.type("hi");
            scenario.press(Key.ENTER);
            Assertions.assertEquals(kira.getPlayer(), community.getWhispers().getFirst().getOther());
            tab.requireDialog().setFocused(null);

            scenario.type("f");
            scenario.runFrame();
            Assertions.assertSame(BeaconLinkScene.SidePanel.FRIENDS, tab.getSidePanel());
            Assertions.assertEquals(List.of(new TableRow(List.of(kira.getTaggedName(), "Online", "demo 0.1.0"), FRIEND_ROW_COLOR, null)),
                                    ((TableBox) tab.requireControl(BeaconLinkScene.SIDE_ID)).getRows());
            scenario.saveFrame("network-beaconlink-friends");

            scenario.type("n");
            scenario.runFrame();

            TableBox channels = (TableBox) tab.requireControl(BeaconLinkScene.SIDE_ID);

            Assertions.assertEquals(List.of("Trade", channel, "SC4J"), channels.getRows().stream().map(row -> row.getCells().getFirst()).toList());
            channels.setSelection(0);
            scenario.type("w");
            scenario.runFrame();
            Assertions.assertEquals("Trade (4)", tab.requireControl(BeaconLinkScene.CHANNEL_ID).getText());
            scenario.saveFrame("network-beaconlink-channels");

            ((ComboBox) tab.requireControl(BeaconLinkScene.LATENCY_ID)).setSelection(1);
            ((ComboBox) tab.requireControl(BeaconLinkScene.VERSIONS_ID)).setSelection(1);
            scenario.runFrame();
            Assertions.assertEquals(List.of("Kira's game"), getRoomNames(tab));
            scenario.saveFrame("network-beaconlink-filter");

            ComboBox presence = (ComboBox) tab.requireControl(BeaconLinkScene.PRESENCE_ID);

            presence.setSelection(1);
            scenario.runFrame();
            Assertions.assertNotNull(tab.getPopup(), "Away asks for an away text");
            scenario.type(AWAY_TEXT);
            scenario.runFrame();
            scenario.saveFrame("network-beaconlink-away");
            scenario.press(Key.ENTER);
            scenario.runFrame();
            Assertions.assertNull(tab.getPopup());
            Assertions.assertSame(PlayerStatus.AWAY, community.getSelf().getStatus());
            Assertions.assertEquals(AWAY_TEXT, community.getSelf().getAwayText());
            Assertions.assertEquals(1, presence.getSelection());

            link.setFailure(LinkFailure.TOO_MANY_CONNECTIONS);
            scenario.runFrame();
            Assertions.assertNull(tab.getPopup(), "a refused session asks nothing");
            Assertions.assertEquals(LinkFailure.TOO_MANY_CONNECTIONS.getDescription(), tab.requireControl(BeaconLinkScene.NODES_ID).getText());
            scenario.saveFrame("network-beaconlink-too-many-devices");
            link.setFailure(null);
        }

        try (DemoClientScenario large = new DemoClientScenario(directory.resolve("large"), link, LARGE_WIDTH, LARGE_HEIGHT)) {
            openRecordedTab(large);
            large.runFrame();
            large.saveFrame("network-beaconlink-chat-1920x1080");
        }
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 1.0", "1280, 720, 1.5", "1280, 720, 2.0", "1920, 1080, 1.5", "2560, 1440, 1.0"})
    void beacon_link_tab_opens_the_create_popup_and_the_password_prompt_of_a_private_room(int width, int height, float scale,
                                                                                          @TempDir @NotNull Path directory) throws IOException,
                                                                                                                                   ManifestException,
                                                                                                                                   ModException {
        PlayerPresence kira = RecordingCommunitySession.createPresence(KIRA_KEY, "Kira", MOD_ID, MOD_VERSION, true, PlayerStatus.AVAILABLE,
                                                                       LatencyEstimate.of(GOOD_MILLISECONDS));
        RecordingCommunitySession community = createCommunity(kira);
        RecordingBeaconLink link = new RecordingBeaconLink(community, createRooms(),
                                                           new InetSocketAddress(DemoNetworkSettings.LOOPBACK, OBSERVED_PORT),
                                                           community.getSelf().getName());
        String suffix = width + "x" + height + "-" + Math.round(scale * PERCENT);

        try (DemoClientScenario scenario = new DemoClientScenario(directory, link, width, height)) {
            scenario.getClient().setDisplayScale(DisplayScale.of(scale, 1));

            BeaconLinkScene tab = openRecordedTab(scenario);
            TableBox rooms = (TableBox) tab.requireControl(BeaconLinkScene.ROOMS_ID);

            rooms.setSelection(0);
            scenario.runFrame();
            scenario.saveFrame("beaconlink-rooms-" + suffix);
            DemoDialogAssert.assertShownWhole(scenario.getClient(), tab.requireDialog(), "the Beacon.Link tab");

            Control chosenTab = tab.requireControl(NetworkScene.BEACON_LINK_TAB_ID);
            Control otherTab = tab.requireControl(NetworkScene.LOCAL_TAB_ID);
            float tabRow = chosenTab.getTop() + chosenTab.getDefinition().getHeight() / 2f;
            int[] chosen = readArea(scenario, tab.requireDialog().getLeft() + chosenTab.getLeft() + TAB_SAMPLE_INSET,
                                    tab.requireDialog().getTop() + tabRow);
            int[] other = readArea(scenario, tab.requireDialog().getLeft() + otherTab.getLeft() + TAB_SAMPLE_INSET,
                                   tab.requireDialog().getTop() + tabRow);
            float selectedRow = rooms.getTop() + scenario.getMenu().getDemo().getFont().getLineHeight() * ROW_MIDDLE;
            int[] row = readArea(scenario, tab.requireDialog().getLeft() + rooms.getLeft() + rooms.getDefinition().getWidth() * ROW_SAMPLE_SHARE,
                                 tab.requireDialog().getTop() + selectedRow);

            Assertions.assertTrue(sum(chosen) > sum(other) + LIT_DIFFERENCE, "the chosen tab is lit: " + Arrays.toString(chosen) + " against " +
                                                                             Arrays.toString(other));
            Assertions.assertTrue(row[RED_CHANNEL] > row[BLUE_CHANNEL] + LIT_DIFFERENCE,
                                  "the selected room shows the faction's warm band: " + Arrays.toString(row));
            scenario.type("h");
            scenario.runFrame();
            scenario.saveFrame("beaconlink-create-" + suffix);

            DemoPopup create = tab.getPopup();

            Assertions.assertNotNull(create, "Host opens the create popup");
            Assertions.assertEquals(BeaconLinkScene.CREATE_DIALOG, create.getDialogId());

            Control lastRow = create.requireControl(BeaconLinkScene.CREATE_PRIVACY_ID);
            Control createButton = create.requireControl(BeaconLinkScene.CREATE_ID);

            Assertions.assertTrue(createButton.getTop() - lastRow.getTop() - lastRow.getDefinition().getHeight() <= LARGEST_BUTTON_GAP,
                                  "the buttons follow the last row without an empty band");
            DemoDialogAssert.assertShownWhole(scenario.getClient(), create.requireDialog(), "the create popup");
            DemoDialogAssert.assertShownWhole(scenario.getClient(), tab.requireDialog(), "the Beacon.Link tab under the popup");
            scenario.press(Key.ESCAPE);
            Assertions.assertNull(tab.getPopup(), "Escape closes the popup");

            ((TableBox) tab.requireControl(BeaconLinkScene.ROOMS_ID)).setSelection(getRoomNames(tab).indexOf("Open field"));
            scenario.type("j");
            scenario.runFrame();
            scenario.saveFrame("beaconlink-password-" + suffix);

            DemoPopup prompt = tab.getPopup();

            Assertions.assertNotNull(prompt, "a private room asks for its password");
            Assertions.assertEquals(DemoPopup.PROMPT, prompt.getDialogId());
            DemoDialogAssert.assertShownWhole(scenario.getClient(), prompt.requireDialog(), "the password prompt");
        }
    }
}
