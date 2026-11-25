package org.fogofwarlabs.sc4j.launcher.ui.session;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import org.fogofwarlabs.sc4j.beaconlink.client.firewall.FirewallState;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkConnectivity;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherShell;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Tests the account menu at the top right: Friends, My status, Sign out everywhere else and Log out. With a connected session the Friends
 * panel lists eight friends at once in compact rows, inside the window, with their state and device count, shows and copies the own friend
 * code, adds a friend by code (a text that is no code, the own code and a known friend are refused in one line), ignores and removes the
 * selected friend, and hands Whisper to the window with the selected friend and closes; My status sends the chosen status (an away text that
 * is too long is refused), and Sign out everywhere else asks once. Without a connection the panels and the menu say why, and Sign out
 * everywhere else is disabled. With the real sessions of three accounts at a node on 127.0.0.1 the Friends panel shows the friends added by
 * code in the launcher and away with a text.
 */
final class AccountMenuTest {
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final @NotNull String TAGGED_NAME = "Raynor#60bf07";
    private static final @NotNull String AWAY_TEXT = "Back at 9";
    private static final @NotNull String TOO_LONG_AWAY_TEXT = "x".repeat(40);
    private static final @NotNull String MENU_SELECTOR = ".menu-panel";
    private static final @NotNull String DIALOG_SELECTOR = ".modal-scrim";
    private static final @NotNull String ACCOUNT_SELECTOR = ".account-button";
    private static final @NotNull String FRIEND_CODE_SELECTOR = ".text-mono";
    private static final @NotNull String COMPACT_LIST_STYLE_CLASS = "compact";
    private static final @NotNull String WARNING_MARKER = "status-marker-warning";
    private static final @NotNull String GAME_MOD_ID = "test-game";
    private static final @NotNull String GAME_NAME = "Test game";
    private static final @NotNull FriendCode OWN_CODE = createCode(1);
    private static final @NotNull FriendCode KERRIGAN = createCode(2);
    private static final @NotNull FriendCode ZERATUL = createCode(3);
    private static final @NotNull FriendCode ARTANIS = createCode(4);
    private static final @NotNull FriendCode FENIX = createCode(5);
    private static final @NotNull FriendCode NEW_FRIEND = createCode(6);
    private static final @NotNull List<@NotNull FriendView> FRIENDS = List.of(
            new FriendView(KERRIGAN, "Kerrigan#a1b2c3", FriendState.IN_GAME, GAME_MOD_ID, "", 2, false),
            new FriendView(ZERATUL, "Zeratul#d4e5f6", FriendState.AWAY, null, "brb", 1, false),
            new FriendView(ARTANIS, "Artanis#0a0b0c", FriendState.IN_LAUNCHER, null, "", 1, true),
            new FriendView(FENIX, "Fenix#112233", FriendState.OFFLINE, null, "", 0, false),
            new FriendView(createCode(7), "Tassadar#998877", FriendState.IN_LAUNCHER, null, "", 1, false),
            new FriendView(createCode(8), "Nova#445566", FriendState.IN_GAME, GAME_MOD_ID, "", 1, false),
            new FriendView(createCode(9), "Swann#778899", FriendState.AWAY, null, "", 1, false),
            new FriendView(createCode(10), "Stukov#aabbcc", FriendState.OFFLINE, null, "", 0, false)
    );

    /**
     * Records what the menu hands to the window.
     */
    private static final class RecordingActions implements AccountMenu.Actions {
        private final @NotNull AtomicInteger logOuts;
        private final @NotNull List<@NotNull String> copied;
        private final @NotNull List<@NotNull String> whispered;

        private RecordingActions() {
            this.logOuts   = new AtomicInteger();
            this.copied    = new ArrayList<>();
            this.whispered = new ArrayList<>();
        }

        @Override
        public void onLogOut() {
            this.logOuts.incrementAndGet();
        }

        @Override
        public void onCopy(@NotNull String text) {
            this.copied.add(text);
        }

        @Override
        public void onWhisper(@NotNull FriendCode player, @NotNull String taggedName) {
            this.whispered.add(player.getText() + " " + taggedName);
        }
    }

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @NotNull RecordingLauncherSession createSession(@NotNull SelfView self) {
        RecordingLauncherSession session = new RecordingLauncherSession(OWN_CODE, self);

        session.setFriends(FRIENDS);

        return session;
    }

    private static @NotNull FriendCode createCode(int fill) {
        byte[] key = new byte[FriendCode.KEY_LENGTH];

        Arrays.fill(key, (byte) fill);

        return FriendCode.of(key);
    }

    private static <T extends Node> @NotNull List<T> find(@NotNull Parent root, @NotNull Class<T> type) {
        List<T> found = new ArrayList<>();

        for (Node node : root.lookupAll("*")) {
            if (type.isInstance(node)) {
                found.add(type.cast(node));
            }
        }

        return found;
    }

    private static boolean showsText(@NotNull Parent root, @NotNull String text) {
        return find(root, Label.class).stream().anyMatch(label -> LauncherFxScenario.isShown(label) && label.getText().contains(text));
    }

    private static @NotNull Parent getMenu(@NotNull LauncherShell shell) {
        Node menu = shell.getRoot().lookup(MENU_SELECTOR);

        Assertions.assertNotNull(menu, "the account menu is open");

        return (Parent) menu;
    }

    private static @NotNull Parent getDialog(@NotNull LauncherShell shell) {
        Node dialog = shell.getRoot().lookup(DIALOG_SELECTOR);

        Assertions.assertNotNull(dialog, "a dialog is shown");

        return (Parent) dialog;
    }

    private static @NotNull TextField getCodeInput(@NotNull Parent dialog) {
        List<TextField> fields = find(dialog, TextField.class);

        Assertions.assertFalse(fields.isEmpty(), "the dialog has the friend code input");

        return fields.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static @NotNull ListView<FriendView> getFriendList(@NotNull Parent dialog) {
        return (ListView<FriendView>) find(dialog, ListView.class).getFirst();
    }

    private static void lay(@NotNull LauncherShell shell) {
        shell.getRoot().applyCss();
        shell.getRoot().layout();
    }

    private static @NotNull List<@NotNull String> listMenuEntries(@NotNull LauncherShell shell) {
        return find(getMenu(shell), Button.class).stream().map(Button::getText).toList();
    }

    /**
     * Builds the shell of a logged-in account with its account menu, the way the window does.
     */
    private static @NotNull LauncherShell createShell(@NotNull LauncherTexts texts, @NotNull Path directory, @NotNull String taggedName,
                                                      @NotNull LauncherSessionView session, @NotNull AccountMenu.Actions actions) {
        AtomicReference<AccountMenu> menu = new AtomicReference<>();
        LauncherShell shell = new LauncherShell(texts, InstallLayout.of(directory), 1, section -> {
        }, () -> {
        }, () -> menu.get().open());
        Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);
        Function<String, String> modNames = modId -> modId.equals(GAME_MOD_ID) ? GAME_NAME : modId;

        menu.set(new AccountMenu(shell, texts, taggedName, session, modNames, actions));
        session.setOnChanged(() -> menu.get().update());
        ThemeUtils.apply(scene, texts.getLanguage());
        shell.show(LauncherShell.Section.MODS, new Region());
        menu.get().update();
        lay(shell);

        return shell;
    }

    private static void openMenu(@NotNull LauncherShell shell) {
        ((Button) shell.getRoot().lookup(ACCOUNT_SELECTOR)).fire();
        lay(shell);
    }

    private static void openEntry(@NotNull LauncherShell shell, @NotNull String entry) {
        openMenu(shell);

        Button found = find(getMenu(shell), Button.class).stream().filter(button -> button.getText().startsWith(entry)).findFirst().orElseThrow();

        found.fire();
        lay(shell);
    }

    private static void addFriend(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull String code) {
        Parent friends = getDialog(shell);

        getCodeInput(friends).setText(code);
        LauncherFxScenario.findButton(friends, texts.get("session.friends.add")).fire();
        lay(shell);
    }


    @Test
    void account_menu_shows_friends_whispers_status_and_sign_out_of_a_connected_session(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        RecordingLauncherSession session = createSession(new SelfView(SessionState.CONNECTED, SelfStatus.ONLINE, "", 2, null,
                                                                      LinkConnectivity.UNKNOWN, FirewallState.NOT_NEEDED));
        RecordingActions actions = new RecordingActions();

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory, TAGGED_NAME, session, actions);

            // The menu: its four entries under the account, the account's two devices.
            openMenu(shell);

            Parent menu = getMenu(shell);

            Assertions.assertEquals(List.of(texts.get("session.menu.friends"), texts.get("session.menu.status"), texts.get("session.menu.sign.out"),
                                            texts.get("session.menu.log.out")),
                                    listMenuEntries(shell));
            Assertions.assertTrue(showsText(menu, TAGGED_NAME));
            Assertions.assertTrue(showsText(menu, texts.format("session.devices", 2)));
            Assertions.assertFalse(LauncherFxScenario.findButton(menu, texts.get("session.menu.sign.out")).isDisabled());

            // Friends: every friend with its state, mod name, devices and ignore mark; the own friend code, copied as it is.
            LauncherFxScenario.findButton(menu, texts.get("session.menu.friends")).fire();
            lay(shell);

            Assertions.assertNull(shell.getRoot().lookup(MENU_SELECTOR), "an entry closes the menu");

            Parent friends = getDialog(shell);

            for (FriendView friend : FRIENDS) {
                Assertions.assertTrue(showsText(friends, friend.getTaggedName()), friend.getTaggedName());
            }

            Assertions.assertTrue(showsText(friends, texts.get("session.friend.in_game")));
            Assertions.assertTrue(showsText(friends, GAME_NAME));
            Assertions.assertTrue(showsText(friends, texts.format("session.friend.devices", 2)));
            Assertions.assertTrue(showsText(friends, "brb"));
            Assertions.assertTrue(showsText(friends, texts.get("session.friend.ignored")));
            Assertions.assertFalse(showsText(friends, texts.get("session.not.connected")));
            Assertions.assertEquals(OWN_CODE.getGroupedText(), ((Label) friends.lookup(FRIEND_CODE_SELECTOR)).getText());
            Assertions.assertTrue(LauncherFxScenario.findButton(friends, texts.get("session.friends.whisper")).isDisabled(), "no friend is selected");

            // The friends show in compact rows; the panel stays inside the window.
            ListView<FriendView> friendList = getFriendList(friends);
            Bounds panel = friendList.getParent().getParent().localToScene(friendList.getParent().getParent().getBoundsInLocal());

            Assertions.assertTrue(friendList.getStyleClass().contains(COMPACT_LIST_STYLE_CLASS));
            Assertions.assertTrue(panel.getMinY() >= 0 && panel.getMaxY() <= SCENE_HEIGHT, "the panel stays inside the window");

            LauncherFxScenario.findButton(friends, texts.get("session.friends.copy")).fire();

            Assertions.assertEquals(List.of(OWN_CODE.getText()), actions.copied);

            // Add friend: a text that is no code, the own code and a known friend are refused; a new code is added.
            addFriend(shell, texts, "Kerrigan#a1b2c3");

            Assertions.assertTrue(showsText(friends, texts.get("session.friends.invalid")));

            addFriend(shell, texts, OWN_CODE.getGroupedText());

            Assertions.assertTrue(showsText(friends, texts.get("session.friends.own")));

            addFriend(shell, texts, KERRIGAN.getText().toLowerCase(Locale.ROOT));

            Assertions.assertTrue(showsText(friends, texts.get("session.friends.known")));
            Assertions.assertTrue(session.getCommands().isEmpty(), "nothing refused reaches the session");

            addFriend(shell, texts, NEW_FRIEND.getGroupedText());

            Assertions.assertEquals(List.of("add " + NEW_FRIEND.getText()), session.getCommands());
            Assertions.assertTrue(getCodeInput(friends).getText().isEmpty(), "an added code leaves the field");

            // The selected friend: ignore, remove.
            getFriendList(friends).getSelectionModel().select(0);
            lay(shell);
            LauncherFxScenario.findButton(friends, texts.get("session.friends.ignore")).fire();
            LauncherFxScenario.findButton(friends, texts.get("session.friends.remove")).fire();
            getFriendList(friends).getSelectionModel().select(2);
            lay(shell);
            LauncherFxScenario.findButton(friends, texts.get("session.friends.unignore")).fire();

            Assertions.assertEquals(List.of("add " + NEW_FRIEND.getText(), "ignore " + KERRIGAN.getText(), "remove " + KERRIGAN.getText(),
                                            "unignore " + ARTANIS.getText()), session.getCommands());

            // Whisper: an offline friend cannot be whispered to; the selected friend goes to the window and the panel closes.
            getFriendList(friends).getSelectionModel().select(3);
            lay(shell);

            Assertions.assertTrue(LauncherFxScenario.findButton(friends, texts.get("session.friends.whisper")).isDisabled(), "Fenix is offline");

            getFriendList(friends).getSelectionModel().select(0);
            lay(shell);
            LauncherFxScenario.findButton(friends, texts.get("session.friends.whisper")).fire();
            lay(shell);

            Assertions.assertEquals(List.of(KERRIGAN.getText() + " Kerrigan#a1b2c3"), actions.whispered);
            Assertions.assertNull(shell.getRoot().lookup(DIALOG_SELECTOR), "Whisper closes the panel");

            openMenu(shell);

            // My status: Away with a text; a text that is too long is refused.
            session.getCommands().clear();
            LauncherFxScenario.findButton(getMenu(shell), texts.get("session.menu.status")).fire();
            lay(shell);

            Parent status = getDialog(shell);
            TextField awayText = find(status, TextField.class).getFirst();

            Assertions.assertTrue(awayText.isDisabled(), "the away text goes with Away only");

            find(status, ToggleButton.class).stream().filter(toggle -> toggle.getText().equals(texts.get("session.status.away"))).findFirst()
                                            .orElseThrow().fire();
            awayText.setText(TOO_LONG_AWAY_TEXT);
            LauncherFxScenario.findButton(status, texts.get("session.save")).fire();
            lay(shell);

            Assertions.assertTrue(showsText(status, texts.get("session.status.too.long")));
            Assertions.assertTrue(session.getCommands().isEmpty());

            awayText.setText(AWAY_TEXT);
            lay(shell);
            LauncherFxScenario.findButton(status, texts.get("session.save")).fire();
            lay(shell);

            Assertions.assertEquals(List.of("status " + SelfStatus.AWAY + ":" + AWAY_TEXT), session.getCommands());
            Assertions.assertNull(shell.getRoot().lookup(DIALOG_SELECTOR));
            Assertions.assertNotNull(shell.getRoot().lookup(ACCOUNT_SELECTOR).lookup("." + WARNING_MARKER), "the account button shows Away");

            // Sign out everywhere else: one confirmation, then the session.
            session.getCommands().clear();
            openMenu(shell);

            Assertions.assertTrue(showsText(getMenu(shell), AWAY_TEXT), "the menu shows the away text");

            LauncherFxScenario.findButton(getMenu(shell), texts.get("session.menu.sign.out")).fire();
            lay(shell);

            Assertions.assertTrue(session.getCommands().isEmpty());

            LauncherFxScenario.findButton(getDialog(shell), texts.get("session.sign.out")).fire();

            Assertions.assertEquals(List.of("sign out"), session.getCommands());
            Assertions.assertNull(shell.getRoot().lookup(DIALOG_SELECTOR));

            // Log out: the menu closes and the window logs out.
            openMenu(shell);
            LauncherFxScenario.findButton(getMenu(shell), texts.get("session.menu.log.out")).fire();

            Assertions.assertEquals(1, actions.logOuts.get());
            Assertions.assertNull(shell.getRoot().lookup(MENU_SELECTOR));

            return null;
        });
    }

    @Test
    void account_menu_shows_why_the_session_is_offline(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        RecordingLauncherSession session = createSession(new SelfView(SessionState.OFFLINE, SelfStatus.ONLINE, "", 0, SessionFailure.SIGNED_OUT,
                                                                      LinkConnectivity.UNKNOWN, FirewallState.NOT_NEEDED));

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory, TAGGED_NAME, session, new RecordingActions());

            openMenu(shell);

            Assertions.assertTrue(showsText(getMenu(shell), texts.get("session.failure.signed_out")));
            Assertions.assertTrue(LauncherFxScenario.findButton(getMenu(shell), texts.get("session.menu.sign.out")).isDisabled(),
                                  "nothing to sign out without a connection");

            LauncherFxScenario.findButton(getMenu(shell), texts.get("session.menu.friends")).fire();
            lay(shell);

            Assertions.assertTrue(showsText(getDialog(shell), texts.get("session.not.connected")));
            Assertions.assertTrue(showsText(getDialog(shell), texts.get("session.friends.empty")));
            Assertions.assertEquals(OWN_CODE.getGroupedText(), ((Label) getDialog(shell).lookup(FRIEND_CODE_SELECTOR)).getText(),
                                    "the own code is known offline");
            LauncherFxScenario.findButton(getDialog(shell), texts.get("session.close")).fire();

            openEntry(shell, texts.get("session.menu.status"));

            Assertions.assertTrue(showsText(getDialog(shell), texts.get("session.not.connected")));

            find(getDialog(shell), ToggleButton.class).stream().filter(toggle -> toggle.getText().equals(texts.get("session.status.away"))).findFirst()
                                                      .orElseThrow().fire();
            LauncherFxScenario.findButton(getDialog(shell), texts.get("session.save")).fire();

            Assertions.assertEquals(SelfStatus.AWAY, session.getSelf().getStatus(), "the status is kept for the session");

            return null;
        });
    }

    @Test
    void account_menu_shows_the_real_sessions_of_three_accounts_at_a_node(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);

        try (LauncherSessionScenario scenario = new LauncherSessionScenario(directory.resolve("node"))) {
            Account kerrigan = LauncherSessionScenario.createAccount(directory.resolve("kerrigan"), "Kerrigan");
            Account raynor = LauncherSessionScenario.createAccount(directory.resolve("raynor"), "Raynor");
            Account zeratul = LauncherSessionScenario.createAccount(directory.resolve("zeratul"), "Zeratul");
            LauncherSession kerriganSession = scenario.startSession(kerrigan, Runnable::run);
            LauncherSession raynorSession = scenario.startSession(raynor, Platform::runLater);
            LauncherSession zeratulSession = scenario.startSession(zeratul, Runnable::run);
            FriendCode kerriganCode = kerriganSession.getFriendCode();
            FriendCode raynorCode = raynorSession.getFriendCode();
            FriendCode zeratulCode = zeratulSession.getFriendCode();

            try {
                LauncherShell shell = LauncherFxScenario.run(() -> createShell(texts, directory, raynor.getTaggedName(), raynorSession,
                                                                               new RecordingActions()));

                LauncherSessionScenario.await(() -> kerriganSession.getSelf().getState() == SessionState.CONNECTED &&
                                                    raynorSession.getSelf().getState() == SessionState.CONNECTED &&
                                                    zeratulSession.getSelf().getState() == SessionState.CONNECTED, "the three launchers connect");
                kerriganSession.addFriend(raynorCode);
                zeratulSession.addFriend(raynorCode);

                // Raynor adds Kerrigan and Zeratul in the Friends panel by their friend codes; Kerrigan shows in the launcher.
                LauncherFxScenario.run(() -> {
                    openEntry(shell, texts.get("session.menu.friends"));
                    addFriend(shell, texts, kerriganCode.getGroupedText());
                    addFriend(shell, texts, zeratulCode.getGroupedText());

                    return null;
                });
                LauncherFxScenario.await(() -> {
                    lay(shell);

                    return showsText(getDialog(shell), kerrigan.getTaggedName()) &&
                           showsText(getDialog(shell), texts.get("session.friend.in_launcher"));
                }, "Raynor's panel shows Kerrigan in the launcher");

                // Kerrigan goes away with a text.
                kerriganSession.setStatus(SelfStatus.AWAY, AWAY_TEXT);
                LauncherFxScenario.await(() -> {
                    lay(shell);

                    return showsText(getDialog(shell), AWAY_TEXT);
                }, "Raynor's panel shows Kerrigan away");
                LauncherFxScenario.run(() -> {
                    LauncherFxScenario.findButton(getDialog(shell), texts.get("session.close")).fire();

                    return null;
                });
            } finally {
                raynorSession.close();
                zeratulSession.close();
                kerriganSession.close();
            }
        }
    }
}
