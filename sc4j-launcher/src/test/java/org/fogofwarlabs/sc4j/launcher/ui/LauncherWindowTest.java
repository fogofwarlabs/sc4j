package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderStroke;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.client.firewall.FirewallState;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkConnectivity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.fogofwarlabs.sc4j.launcher.settings.LauncherSettings;
import org.fogofwarlabs.sc4j.launcher.settings.LauncherSettingsFile;
import org.fogofwarlabs.sc4j.launcher.settings.WindowState;
import org.fogofwarlabs.sc4j.launcher.translation.LauncherTranslation;
import org.fogofwarlabs.sc4j.launcher.ui.session.Conversations;
import org.fogofwarlabs.sc4j.launcher.ui.session.RecordingLauncherSession;
import org.fogofwarlabs.sc4j.launcher.update.LauncherRelease;
import org.fogofwarlabs.sc4j.launcher.update.LauncherUpdater;
import org.fogofwarlabs.sc4j.launcher.update.PackagePlatform;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.fogofwarlabs.sc4j.translate.service.TranslationPresetUtils;
import org.fogofwarlabs.sc4j.translate.service.TranslationStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests where the launcher window opens: its bounds fit the free area of a 1920 by 1080 pixel screen at display scales of 100%, 150% and
 * 200% (in JavaFX logical units), saved bounds are kept on their screen, and saved bounds on no screen are dropped. A display change to
 * another scale builds the views again and fits the window to its screen without a restart. The launcher starts on the login step (with
 * the New account dialog when there is no account, else with the last used account selected); Log in opens the main view with the account
 * at the top right and no Accounts tab and starts the account's session, which connects to the node of the install's node list on
 * 127.0.0.1, and Log out from the account menu closes the session, frees the account and returns to the login step. Whisper in the account
 * menu's Friends shows the Chat screen with the friend's conversation selected and the input focused, from another screen and from the Chat
 * screen. Open mods folder hands the mods folder to the folder opener, and a mod's website link goes to the document opener.
 * The Update dialog of a newer launcher version opens by itself once per version and run, once logged in, while no game runs and not for a
 * skipped version; the automatic look for it is due again a period after the last one.
 * The chat runs from the login on, in the display language: the session joins the shared channel without the Chat screen. The Chat entry
 * shows a dot while a line or a whisper is unread and the sound plays for it while the Chat screen is hidden; reading the conversation clears
 * its part of the dot, and the Settings switch mutes the sound. A restricted network shows a warning above every screen, once per run, until
 * it is closed. Closing the window asks to minimize, quit or cancel, in a dialog whose footer has the same room on every side of its buttons.
 */
final class LauncherWindowTest {
    private static final double SCREEN_WIDTH = 1920;
    private static final double SCREEN_HEIGHT = 1080;
    private static final double TASK_BAR_HEIGHT = 48;
    private static final double DEFAULT_WIDTH = 1280;
    private static final double DEFAULT_HEIGHT = 800;
    private static final int SAVED_X = 100;
    private static final int SAVED_Y = 50;
    private static final int SAVED_WIDTH = 1200;
    private static final int SAVED_HEIGHT = 760;
    private static final int OFF_SCREEN_X = 5000;
    private static final double TOLERANCE = 1;
    private static final double FIRST_SCALE = 1;
    private static final double CHANGED_SCALE = 2;
    private static final long FX_TIMEOUT_SECONDS = 60;
    private static final @NotNull String VERSION = "1.0.0";
    private static final long RAYNOR_USE_MILLIS = Instant.parse("2026-11-10T18:30:00Z").toEpochMilli();
    private static final @NotNull String NAVIGATION_SELECTOR = ".nav-button";
    private static final @NotNull String ACCOUNT_SELECTOR = ".account-button";
    private static final @NotNull String MENU_SELECTOR = ".menu-panel";
    private static final @NotNull String DIALOG_SELECTOR = ".modal-scrim";
    private static final @NotNull String SUCCESS_MARKER = "status-marker-success";
    private static final @NotNull String HYPERLINK_SELECTOR = ".hyperlink";
    private static final @NotNull String LIST_SELECTOR = ".list-view";
    private static final @NotNull String TEXT_FIELD_SELECTOR = ".text-field";
    private static final @NotNull String DOT_BADGE_SELECTOR = ".dot-badge";
    private static final @NotNull String CHECK_BOX_SELECTOR = ".check-box";
    private static final @NotNull String NOTICE_SELECTOR = ".notice";
    private static final @NotNull String TEXT_ONLY_STYLE_CLASS = "text-only";
    private static final @NotNull Runnable NO_SOUND = () -> {
    };
    /**
     * The place of the channel list among the lists of the Chat screen, from left to right.
     */
    private static final int CHANNEL_LIST = 0;
    /**
     * The place of the conversation list among the lists of the Chat screen, from left to right.
     */
    private static final int CONVERSATION_LIST = 1;
    private static final @NotNull String MOD_JAR_NAME = "example.jar";
    private static final @NotNull String UPDATE_REPOSITORY = "owner/sc4j";
    private static final @NotNull String MOD_WEBSITE = "https://example.org/mod";
    private static final @NotNull String MOD_MANIFEST = """
                                                        {
                                                          "format": 1,
                                                          "id": "example",
                                                          "name": {"en": "Example"},
                                                          "version": "1.0.0",
                                                          "engine": "0.1",
                                                          "entry": "org.example.ExampleMod",
                                                          "website": "%s"
                                                        }
                                                        """;

    private static @NotNull IdentityKeyPair raynorKey;
    private static @NotNull PlayerStamp raynorStamp;

    @BeforeAll
    static void startToolkitAndSolveStamp() {
        LauncherFxScenario.startToolkit();

        raynorKey   = IdentityKeyPair.generate(new SecureRandom());
        raynorStamp = PlayerStamp.solve(raynorKey.getPublicKey(), "Raynor", PlayerStamp.DEFAULT_DIFFICULTY);
    }

    private static @NotNull LauncherTranslation createTranslation(@NotNull InstallLayout install) {
        return new LauncherTranslation(TranslationStorage.of(install.getUserDataDirectory()), TranslationPresetUtils.getPresets());
    }

    private static @NotNull Rectangle2D createScreen(double scale) {
        return new Rectangle2D(0, 0, SCREEN_WIDTH / scale, (SCREEN_HEIGHT - TASK_BAR_HEIGHT) / scale);
    }

    private static void assertInside(@NotNull Rectangle2D screen, @NotNull Rectangle2D bounds) {
        Assertions.assertTrue(bounds.getMinX() >= screen.getMinX() && bounds.getMaxX() <= screen.getMaxX(), bounds::toString);
        Assertions.assertTrue(bounds.getMinY() >= screen.getMinY() && bounds.getMaxY() <= screen.getMaxY(), bounds::toString);
    }

    private static @NotNull Parent layRoot(@NotNull Stage stage) {
        Parent root = stage.getScene().getRoot();

        root.applyCss();
        root.layout();

        return root;
    }

    private static @NotNull List<@NotNull String> listVisibleNavigation(@NotNull Parent root) {
        return root.lookupAll(NAVIGATION_SELECTOR).stream().filter(LauncherFxScenario::isShown).map(node -> ((ToggleButton) node).getText()).toList();
    }

    private static @NotNull FriendCode createCode(int fill) {
        byte[] key = new byte[FriendCode.KEY_LENGTH];

        Arrays.fill(key, (byte) fill);

        return FriendCode.of(key);
    }

    /**
     * Whispers from the account menu: opens Friends, selects the friend and presses Whisper.
     */
    private static void whisperFromFriends(@NotNull Stage stage, @NotNull LauncherTexts texts, int friend) {
        Parent root = layRoot(stage);

        ((Button) root.lookup(ACCOUNT_SELECTOR)).fire();
        root = layRoot(stage);
        LauncherFxScenario.findButton(root.lookup(MENU_SELECTOR), texts.get("session.menu.friends")).fire();
        root = layRoot(stage);
        ((ListView<?>) root.lookup(DIALOG_SELECTOR).lookup(LIST_SELECTOR)).getSelectionModel().select(friend);
        root = layRoot(stage);
        LauncherFxScenario.findButton(root.lookup(DIALOG_SELECTOR), texts.get("session.friends.whisper")).fire();
    }

    /**
     * Checks that the Chat screen is shown with a player's conversation selected and the input focused.
     */
    private static void assertChatShowsWhispers(@NotNull Stage stage, @NotNull LauncherTexts texts, @NotNull String taggedName) {
        Parent root = layRoot(stage);
        List<Node> lists = List.copyOf(root.lookupAll(LIST_SELECTOR));

        Assertions.assertNull(root.lookup(DIALOG_SELECTOR), "Whisper closes the Friends panel");
        Assertions.assertTrue(root.lookupAll(NAVIGATION_SELECTOR).stream().map(node -> (ToggleButton) node)
                                  .anyMatch(button -> button.isSelected() && button.getText().equals(texts.get("nav.chat"))),
                              "the Chat tab is shown");
        Assertions.assertTrue(lists.size() > CONVERSATION_LIST, "the Chat screen is shown");

        ListView<?> conversations = (ListView<?>) lists.get(CONVERSATION_LIST);

        Assertions.assertEquals(taggedName, ((Conversations.Conversation) conversations.getSelectionModel().getSelectedItem()).getTaggedName());
        Assertions.assertSame(root.lookup(TEXT_FIELD_SELECTOR), stage.getScene().getFocusOwner(), "the input is focused");
    }

    private static @NotNull ToggleButton findNavigation(@NotNull Parent root, @NotNull String text) {
        for (Node node : root.lookupAll(NAVIGATION_SELECTOR)) {
            if (node instanceof ToggleButton button && button.getText().equals(text)) {
                return button;
            }
        }

        throw Exceptions.newAssertionError("No navigation entry {0}", text);
    }

    private static boolean isChatDotShown(@NotNull Stage stage, @NotNull LauncherTexts texts) {
        Node dot = findNavigation(layRoot(stage), texts.get("nav.chat")).lookup(DOT_BADGE_SELECTOR);

        Assertions.assertNotNull(dot, "the Chat entry has a dot badge");

        return dot.isVisible();
    }

    /**
     * Finds the shown notice whose text is the given one.
     */
    private static @Nullable Node findNotice(@NotNull Stage stage, @NotNull String text) {
        for (Node notice : layRoot(stage).lookupAll(NOTICE_SELECTOR)) {
            if (LauncherFxScenario.isShown(notice) && notice.lookupAll(".label").stream().anyMatch(node -> ((Label) node).getText().equals(text))) {
                return notice;
            }
        }

        return null;
    }

    private static @NotNull SelfView createConnectedSelf(@NotNull LinkConnectivity connectivity) {
        return createConnectedSelf(connectivity, FirewallState.NOT_NEEDED);
    }

    private static @NotNull SelfView createConnectedSelf(@NotNull LinkConnectivity connectivity, @NotNull FirewallState firewall) {
        return new SelfView(SessionState.CONNECTED, SelfStatus.ONLINE, "", 1, null, connectivity, firewall);
    }

    private static @NotNull RecordingLauncherSession createConnectedSession() {
        return new RecordingLauncherSession(createCode(1), createConnectedSelf(LinkConnectivity.UNKNOWN));
    }

    private static @NotNull ChatEntryView createLine(@NotNull String channel, @NotNull FriendCode author, @NotNull String text) {
        return new ChatEntryView(ChatEntryKind.SAY, "Kerrigan#a1b2c3: ", text, channel, author, "Kerrigan#a1b2c3");
    }

    private static void requestClose(@NotNull Stage stage) {
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    /**
     * Checks that a button has the text-only role: no visible background and no visible border.
     */
    private static void assertTextOnly(@NotNull Button button) {
        Assertions.assertTrue(button.getStyleClass().contains(TEXT_ONLY_STYLE_CLASS));

        if (button.getBackground() != null) {
            for (BackgroundFill fill : button.getBackground().getFills()) {
                Assertions.assertEquals(Color.TRANSPARENT, fill.getFill(), "no background");
            }
        }

        if (button.getBorder() != null) {
            for (BorderStroke stroke : button.getBorder().getStrokes()) {
                Assertions.assertEquals(Color.TRANSPARENT, stroke.getTopStroke(), "no border");
            }
        }
    }

    private static @NotNull Hyperlink findHyperlink(@NotNull Parent root, @NotNull String text) {
        for (Node node : root.lookupAll(HYPERLINK_SELECTOR)) {
            if (node instanceof Hyperlink link && link.getText().equals(text)) {
                return link;
            }
        }

        throw Exceptions.newAssertionError("No link {0}", text);
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, 1.5, 2.0})
    void place_window_should_fit_the_default_bounds_into_the_screen_at_a_display_scale(double scale) {
        Rectangle2D screen = createScreen(scale);
        Rectangle2D bounds = LauncherWindow.placeWindow(null, screen);

        assertInside(screen, bounds);
        Assertions.assertEquals(Math.min(DEFAULT_WIDTH, screen.getWidth()), bounds.getWidth());
        Assertions.assertEquals(Math.min(DEFAULT_HEIGHT, screen.getHeight()), bounds.getHeight());
        Assertions.assertEquals(screen.getWidth() - bounds.getMaxX(), bounds.getMinX(), TOLERANCE, "the window is centred");
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, 1.5, 2.0})
    void place_window_should_keep_saved_bounds_inside_their_screen(double scale) {
        Rectangle2D screen = createScreen(scale);
        WindowState saved = new WindowState(SAVED_X, SAVED_Y, SAVED_WIDTH, SAVED_HEIGHT, false);

        Assertions.assertSame(screen, LauncherWindow.findScreen(saved, List.of(screen)));

        Rectangle2D bounds = LauncherWindow.placeWindow(saved, screen);

        assertInside(screen, bounds);
        Assertions.assertEquals(Math.min(SAVED_WIDTH, screen.getWidth()), bounds.getWidth());
        Assertions.assertEquals(Math.min(SAVED_HEIGHT, screen.getHeight()), bounds.getHeight());
    }

    @Test
    void find_screen_should_drop_saved_bounds_on_no_screen() {
        WindowState saved = new WindowState(OFF_SCREEN_X, SAVED_Y, SAVED_WIDTH, SAVED_HEIGHT, false);

        Assertions.assertNull(LauncherWindow.findScreen(saved, List.of(createScreen(1))));
    }

    @Test
    void is_update_offered_should_open_the_dialog_once_for_a_newer_version_that_is_not_skipped(@TempDir @NotNull Path directory) throws Exception {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            LauncherUpdater updater = new LauncherUpdater(InstallLayout.of(directory), new ReleaseClient(server.getApi()), PackagePlatform.LINUX_X64);
            ReleaseRepository repository = ReleaseRepository.parse(UPDATE_REPOSITORY);
            ReleaseVersion olderSkipped = ReleaseVersion.parse("1.1.5");
            LauncherRelease newer;
            LauncherSettings skipped;

            Assertions.assertNotNull(repository);
            Assertions.assertNotNull(olderSkipped);
            server.addCheckedRelease(UPDATE_REPOSITORY, "v1.2.0", Map.of("SC4J-1.2.0-linux-x64.zip", new byte[]{1}));
            newer = updater.findUpdate(repository, ReleaseVersion.parse("1.1.0"));
            Assertions.assertNotNull(newer);
            skipped = LauncherSettings.DEFAULT.withSkippedVersion(newer.getVersion());

            Assertions.assertTrue(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, true, false, null), "newer");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(updater.findUpdate(repository, ReleaseVersion.parse("1.2.0")), LauncherSettings.DEFAULT,
                                                                  true, false, null), "same");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(newer, skipped, true, false, null), "skipped");
            Assertions.assertTrue(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT.withSkippedVersion(olderSkipped), true, false, null),
                                  "a release after the skipped one");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, true, true, null), "running game");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, false, false, null), "login step");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, true, false, ReleaseVersion.parse("v1.2.0")),
                                   "once per version");
            Assertions.assertTrue(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, true, false, olderSkipped),
                                  "a release after the one offered");
        }
    }

    @Test
    void is_update_check_due_should_wait_the_period_after_the_last_check() {
        Instant start = Instant.parse("2026-11-21T10:00:00Z");

        Assertions.assertTrue(LauncherWindow.isUpdateCheckDue(null, start), "the first check");
        Assertions.assertFalse(LauncherWindow.isUpdateCheckDue(start, start.plus(Duration.ofMinutes(29))), "within the period");
        Assertions.assertTrue(LauncherWindow.isUpdateCheckDue(start, start.plus(Duration.ofMinutes(30))), "after the period");
        Assertions.assertTrue(LauncherWindow.isUpdateCheckDue(start, start.plus(Duration.ofDays(1))), "after a long sleep");
    }

    @Test
    void fit_display_should_build_the_views_again_and_fit_the_window_when_the_scale_changes(@TempDir @NotNull Path directory) throws Exception {
        InstallLayout install = InstallLayout.of(directory);
        CompletableFuture<Void> result = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                Stage stage = new Stage();
                LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
                }, folder -> {
                }, new LauncherNodeRunner(install), account -> LauncherSession.open(install, account, VERSION, Platform::runLater), NO_SOUND,
                                                           null, createTranslation(install));
                Rectangle2D firstScreen = createScreen(FIRST_SCALE);
                Rectangle2D changedScreen = createScreen(CHANGED_SCALE);

                stage.setWidth(DEFAULT_WIDTH);
                stage.setHeight(DEFAULT_HEIGHT);
                window.fitDisplay(FIRST_SCALE, firstScreen);

                Assertions.assertEquals(DEFAULT_WIDTH, stage.getWidth(), "the window fits the first screen");

                Parent firstRoot = stage.getScene().getRoot();

                window.fitDisplay(CHANGED_SCALE, changedScreen);

                Parent changedRoot = stage.getScene().getRoot();

                changedRoot.applyCss();
                changedRoot.layout();

                Assertions.assertNotSame(firstRoot, changedRoot, "the views are built again");
                Assertions.assertEquals(changedScreen.getWidth(), stage.getMinWidth());
                Assertions.assertEquals(changedScreen.getHeight(), stage.getMinHeight());
                assertInside(changedScreen, new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));

                window.fitDisplay(CHANGED_SCALE, changedScreen);

                Assertions.assertSame(changedRoot, stage.getScene().getRoot(), "the same scale builds nothing again");
                result.complete(null);
            } catch (RuntimeException | Error exception) {
                result.completeExceptionally(exception);
            }
        });

        result.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void launcher_without_accounts_starts_on_the_login_step_with_the_new_account_dialog(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);

        new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(UiLanguage.ENGLISH));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> LauncherSession.open(install, account, VERSION, Platform::runLater), NO_SOUND, null,
                                                       createTranslation(install));

            window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));

            Parent root = layRoot(stage);
            Node dialog = root.lookup(DIALOG_SELECTOR);

            Assertions.assertTrue(listVisibleNavigation(root).isEmpty(), "the login step comes first");
            Assertions.assertNotNull(dialog, "the New account dialog is shown first");
            Assertions.assertNotNull(dialog.lookup(".text-field"));
            Assertions.assertTrue(LauncherFxScenario.findButton(root, texts.get("accounts.log.in")).isDisabled());

            return null;
        });
    }

    @Test
    void launcher_logs_in_from_the_login_step_and_logs_out_back_to_it(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        Account raynor = store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        List<LauncherSession> sessions = new ArrayList<>();
        AtomicReference<Stage> shown = new AtomicReference<>();

        try (LauncherSessionScenario scenario = new LauncherSessionScenario(directory.resolve("node"))) {
            scenario.writeNodeList(install);
            new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(UiLanguage.ENGLISH));
            LauncherFxScenario.run(() -> {
                Stage stage = new Stage();
                LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
                }, folder -> {
                }, new LauncherNodeRunner(install), account -> {
                    LauncherSession session = LauncherSession.open(install, account, VERSION, Platform::runLater);

                    sessions.add(session);

                    return session;
                }, NO_SOUND, null, createTranslation(install));

                shown.set(stage);
                window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));

                // The login step: the last used account selected, no navigation, no account at the top right.
                Parent root = layRoot(stage);
                @SuppressWarnings("unchecked")
                ListView<Account> list = (ListView<Account>) root.lookup(".list-view");

                Assertions.assertTrue(listVisibleNavigation(root).isEmpty());
                Assertions.assertFalse(LauncherFxScenario.isShown(root.lookup(ACCOUNT_SELECTOR)));
                Assertions.assertNull(root.lookup(DIALOG_SELECTOR), "with an account no dialog opens");
                Assertions.assertEquals(raynor.getId(), list.getSelectionModel().getSelectedItem().getId());
                LauncherFxScenario.findButton(root, texts.get("accounts.log.in")).fire();

                return null;
            });

            // The main view: the account's session connects to the install's node and the account turns green at the top right.
            LauncherFxScenario.await(() -> sessions.size() == 1 && sessions.getFirst().getSelf().getState() == SessionState.CONNECTED &&
                                           layRoot(shown.get()).lookup(ACCOUNT_SELECTOR).lookup("." + SUCCESS_MARKER) != null,
                                     "the account's session connects");

            Assertions.assertTrue(store.isInUse(raynor), "the logged-in launcher uses the account");

            // The chat runs from the login on: the session joins the shared channel while the Mods screen is shown.
            LauncherFxScenario.await(() -> sessions.getFirst().getChannels().stream()
                                                   .anyMatch(channel -> channel.getName().equals(ChannelChat.SHARED_CHANNEL)),
                                     "the session joins the shared channel without the Chat screen");

            LauncherFxScenario.run(() -> {
                Parent root = layRoot(shown.get());
                Button account = (Button) root.lookup(ACCOUNT_SELECTOR);

                Assertions.assertEquals(List.of(texts.get("nav.mods"), texts.get("nav.run"), texts.get("nav.chat"), texts.get("nav.settings")),
                                        listVisibleNavigation(root), "no Accounts tab");
                Assertions.assertTrue(LauncherFxScenario.isShown(account));
                Assertions.assertEquals(raynor.getTaggedName(), account.getText());

                account.fire();
                root = layRoot(shown.get());

                Assertions.assertNotNull(root.lookup(MENU_SELECTOR));
                LauncherFxScenario.findButton(root, texts.get("session.menu.log.out")).fire();

                // Log out: the session ends and the login step returns with the account still selected.
                root = layRoot(shown.get());

                Assertions.assertTrue(listVisibleNavigation(root).isEmpty());
                Assertions.assertFalse(LauncherFxScenario.isShown(root.lookup(ACCOUNT_SELECTOR)));
                Assertions.assertNull(root.lookup(MENU_SELECTOR));
                Assertions.assertFalse(LauncherFxScenario.findButton(root, texts.get("accounts.log.in")).isDisabled());

                return null;
            });

            Assertions.assertFalse(store.isInUse(raynor), "log out closes the session and frees the account");
            Assertions.assertEquals(1, sessions.size());
        }
    }

    @Test
    void launcher_opens_folders_with_the_folder_opener_and_websites_with_the_document_opener(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        List<String> documents = new ArrayList<>();
        List<Path> folders = new ArrayList<>();
        List<LauncherSession> sessions = new ArrayList<>();

        store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        Files.createDirectories(install.getModsDirectory());
        TestJarUtils.write(install.getModsDirectory().resolve(MOD_JAR_NAME),
                           Map.of(TestJarUtils.MANIFEST_ENTRY, MOD_MANIFEST.formatted(MOD_WEBSITE).getBytes(StandardCharsets.UTF_8)));
        new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(UiLanguage.ENGLISH));

        try {
            LauncherFxScenario.run(() -> {
                Stage stage = new Stage();
                LauncherNodeRunner runner = new LauncherNodeRunner(install);
                LauncherWindow window = new LauncherWindow(stage, install, VERSION, documents::add, folders::add, runner, account -> {
                    LauncherSession session = LauncherSession.open(install, account, VERSION, Platform::runLater);

                    sessions.add(session);

                    return session;
                }, NO_SOUND, null, createTranslation(install));

                window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
                LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();
                Assertions.assertTrue(findNavigation(layRoot(stage), texts.get("nav.chat")).isSelected(), "logging in shows the chat");
                findNavigation(layRoot(stage), texts.get("nav.mods")).fire();

                Parent root = layRoot(stage);

                LauncherFxScenario.findButton(root, texts.get("mods.open.folder")).fire();

                Assertions.assertEquals(List.of(install.getModsDirectory()), folders);
                Assertions.assertTrue(documents.isEmpty(), "a folder does not go to the document opener");

                findHyperlink(root, MOD_WEBSITE).fire();

                Assertions.assertEquals(List.of(MOD_WEBSITE), documents);
                Assertions.assertEquals(1, folders.size(), "a website does not go to the folder opener");

                return null;
            });
        } finally {
            for (LauncherSession session : sessions) {
                session.close();
            }
        }
    }

    @Test
    void whisper_from_friends_opens_the_chat_tab_on_the_friend_conversation(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        RecordingLauncherSession session = createConnectedSession();

        store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        session.setFriends(List.of(new FriendView(createCode(2), "Kerrigan#a1b2c3", FriendState.IN_LAUNCHER, null, "", 1, false),
                                   new FriendView(createCode(3), "Zeratul#d4e5f6", FriendState.IN_LAUNCHER, null, "", 1, false)));
        new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(UiLanguage.ENGLISH));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> session, NO_SOUND, null, createTranslation(install));

            window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
            LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();

            // From the Mods screen: the Chat screen opens on Kerrigan's conversation.
            whisperFromFriends(stage, texts, 0);
            assertChatShowsWhispers(stage, texts, "Kerrigan#a1b2c3");

            // From the Chat screen: the screen shows Zeratul's conversation.
            whisperFromFriends(stage, texts, 1);
            assertChatShowsWhispers(stage, texts, "Zeratul#d4e5f6");

            return null;
        });
    }

    @Test
    void chat_dot_shows_unread_lines_and_whispers_until_read_and_the_sound_plays_while_the_chat_is_hidden(@TempDir @NotNull Path directory) throws Exception {
        // Korean, so that the chat that the login starts in the display language does not open in the default English.
        UiLanguage language = UiLanguage.KOREAN;
        LauncherTexts texts = LauncherTexts.of(language);
        InstallLayout install = InstallLayout.of(directory);
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        RecordingLauncherSession session = createConnectedSession();
        FriendCode kerrigan = createCode(2);
        AtomicInteger sounds = new AtomicInteger();

        store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(language));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> session, sounds::incrementAndGet, null, createTranslation(install));
            List<ChannelView> channels = List.of(new ChannelView(ChannelChat.SHARED_CHANNEL, 2, List.of()), new ChannelView("Ladder", 2, List.of()));
            List<ChatEntryView> entries = new ArrayList<>();

            window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
            LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();

            // The login starts the chat in the display language; nothing is unread yet.
            Assertions.assertEquals(List.of("open chat " + language.getCode()), session.getCommands());
            Assertions.assertFalse(isChatDotShown(stage, texts));

            // A line arrives while the Mods screen is shown: the dot shows and the sound plays.
            entries.add(createLine(ChannelChat.SHARED_CHANNEL, kerrigan, "gg"));
            session.setChat(channels, List.of(), entries);

            Assertions.assertTrue(isChatDotShown(stage, texts));
            Assertions.assertEquals(1, sounds.get(), "the sound plays while the Chat screen is hidden");

            // The Chat screen shows the shared channel, which reads it.
            findNavigation(layRoot(stage), texts.get("nav.chat")).fire();

            Assertions.assertFalse(isChatDotShown(stage, texts), "reading the channel clears the dot");

            // A line in the other channel shows the dot until that channel is shown.
            entries.add(createLine("Ladder", kerrigan, "1v1?"));
            session.setChat(channels, List.of(), entries);

            Assertions.assertTrue(isChatDotShown(stage, texts));

            ((ListView<?>) List.copyOf(layRoot(stage).lookupAll(LIST_SELECTOR)).get(CHANNEL_LIST)).getSelectionModel().select(1);

            Assertions.assertFalse(isChatDotShown(stage, texts));

            // A whisper shows the dot until its conversation is shown.
            session.setWhispers(List.of(new WhisperView(kerrigan, "Kerrigan#a1b2c3", "Ready?", RAYNOR_USE_MILLIS, WhisperState.RECEIVED)));

            Assertions.assertTrue(isChatDotShown(stage, texts));

            ((ListView<?>) List.copyOf(layRoot(stage).lookupAll(LIST_SELECTOR)).get(CONVERSATION_LIST)).getSelectionModel().select(0);

            Assertions.assertFalse(isChatDotShown(stage, texts));

            return null;
        });
    }

    @Test
    void network_warning_shows_once_per_run_above_every_screen_until_closed(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        String warning = texts.get("notice.network");
        InstallLayout install = InstallLayout.of(directory);
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        RecordingLauncherSession session = createConnectedSession();

        store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(UiLanguage.ENGLISH));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> session, NO_SOUND, null, createTranslation(install));

            window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
            LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();

            Assertions.assertNull(findNotice(stage, warning), "nothing is known of the network yet");

            session.setSelf(createConnectedSelf(LinkConnectivity.DIRECT));

            Assertions.assertNull(findNotice(stage, warning), "a direct network shows no warning");

            session.setSelf(createConnectedSelf(LinkConnectivity.RESTRICTED));

            Assertions.assertNotNull(findNotice(stage, warning), "a restricted network shows the warning");

            findNavigation(layRoot(stage), texts.get("nav.settings")).fire();
            Node notice = findNotice(stage, warning);

            Assertions.assertNotNull(notice, "the warning shows above another screen");

            LauncherFxScenario.findButton(notice, texts.get("notice.network.close")).fire();

            Assertions.assertNull(findNotice(stage, warning), "Close hides the warning");

            session.setSelf(createConnectedSelf(LinkConnectivity.DIRECT));
            session.setSelf(createConnectedSelf(LinkConnectivity.RESTRICTED));

            Assertions.assertNull(findNotice(stage, warning), "the warning does not come again in this run");

            return null;
        });
    }

    @Test
    void firewall_warning_asks_to_allow_the_launcher_while_the_firewall_blocks_it(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        String warning = texts.get("notice.firewall");
        InstallLayout install = InstallLayout.of(directory);
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        RecordingLauncherSession session = createConnectedSession();

        store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(UiLanguage.ENGLISH));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> session, NO_SOUND, null, createTranslation(install));

            window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
            LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();
            session.setSelf(createConnectedSelf(LinkConnectivity.UNKNOWN, FirewallState.ALLOWED));

            Assertions.assertNull(findNotice(stage, warning), "an allowed launcher shows no warning");

            session.setSelf(createConnectedSelf(LinkConnectivity.UNKNOWN, FirewallState.BLOCKED));
            Node notice = findNotice(stage, warning);

            Assertions.assertNotNull(notice, "a blocked launcher shows the warning");

            LauncherFxScenario.findButton(notice, texts.get("notice.firewall.allow")).fire();

            Assertions.assertTrue(session.getCommands().contains("allow firewall"), "Allow asks the session to allow the launcher");

            session.setSelf(createConnectedSelf(LinkConnectivity.UNKNOWN, FirewallState.CHECKING));

            Assertions.assertNull(findNotice(stage, warning), "the warning hides while the request runs");

            session.setSelf(createConnectedSelf(LinkConnectivity.UNKNOWN, FirewallState.NO_RULE));
            notice = findNotice(stage, warning);

            Assertions.assertNotNull(notice, "a launcher without a rule shows the warning again");

            LauncherFxScenario.findButton(notice, texts.get("notice.firewall.close")).fire();

            Assertions.assertNull(findNotice(stage, warning), "Close hides the warning");

            session.setSelf(createConnectedSelf(LinkConnectivity.UNKNOWN, FirewallState.BLOCKED));

            Assertions.assertNull(findNotice(stage, warning), "the warning does not come again in this run");

            return null;
        });
    }

    @Test
    void settings_switch_mutes_the_chat_sound_and_saves_it(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        LauncherSettingsFile settingsFile = new LauncherSettingsFile(install.getLauncherSettingsFile());
        RecordingLauncherSession session = createConnectedSession();
        AtomicInteger sounds = new AtomicInteger();

        store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        settingsFile.write(LauncherSettings.DEFAULT.withLanguage(UiLanguage.ENGLISH));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> session, sounds::incrementAndGet, null, createTranslation(install));

            window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
            LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();
            findNavigation(layRoot(stage), texts.get("nav.settings")).fire();

            CheckBox mute = (CheckBox) layRoot(stage).lookup(CHECK_BOX_SELECTOR);

            Assertions.assertNotNull(mute);
            Assertions.assertEquals(texts.get("settings.chat.sound.mute"), mute.getText());
            Assertions.assertFalse(mute.isSelected(), "the chat sound is on by default");

            mute.fire();

            Assertions.assertTrue(settingsFile.read().isChatSoundMuted(), "the switch is saved in the launcher settings");

            // A muted sound does not play; the dot still shows.
            session.setChat(List.of(new ChannelView(ChannelChat.SHARED_CHANNEL, 2, List.of())), List.of(),
                            List.of(createLine(ChannelChat.SHARED_CHANNEL, createCode(2), "gg")));

            Assertions.assertEquals(0, sounds.get());
            Assertions.assertTrue(isChatDotShown(stage, texts));

            return null;
        });
    }

    @Test
    void close_request_asks_to_minimize_quit_or_cancel(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        LauncherSettingsFile settingsFile = new LauncherSettingsFile(install.getLauncherSettingsFile());
        RecordingLauncherSession session = createConnectedSession();

        store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        settingsFile.write(LauncherSettings.DEFAULT.withLanguage(UiLanguage.ENGLISH));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> session, NO_SOUND, null, createTranslation(install));

            window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
            LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();

            // The dialog: Quit on the left, then Cancel (Escape) and Minimize, the default action; a second request opens no second dialog.
            requestClose(stage);
            requestClose(stage);

            Parent root = layRoot(stage);
            Parent dialog = (Parent) root.lookup(DIALOG_SELECTOR);
            Button minimize = LauncherFxScenario.findButton(dialog, texts.get("close.minimize"));
            Button cancel = LauncherFxScenario.findButton(dialog, texts.get("close.cancel"));

            Assertions.assertEquals(1, root.lookupAll(DIALOG_SELECTOR).size());
            Assertions.assertEquals(List.of(texts.get("close.quit"), texts.get("close.cancel"), texts.get("close.minimize")),
                                    dialog.lookupAll(".button").stream().map(node -> ((Button) node).getText()).toList());
            Assertions.assertTrue(minimize.isDefaultButton(), "Minimize is the default action");
            Assertions.assertTrue(minimize.getStyleClass().contains("primary"));
            Assertions.assertTrue(cancel.isCancelButton());
            assertTextOnly(LauncherFxScenario.findButton(dialog, texts.get("close.quit")));

            Assertions.assertTrue(dialog.lookupAll(".label").stream().anyMatch(node -> ((Label) node).getText().equals(texts.get("close.text"))),
                                  "the dialog asks whether to quit");
            Assertions.assertTrue(LauncherFxScenario.assertFooterInsets(dialog) >= 1, "the dialog footer is checked");

            // Cancel and Escape keep the window.
            cancel.fire();

            Assertions.assertNull(layRoot(stage).lookup(DIALOG_SELECTOR));
            Assertions.assertFalse(stage.isIconified());

            requestClose(stage);
            Event.fireEvent(LauncherFxScenario.findButton(layRoot(stage), texts.get("close.minimize")),
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));

            Assertions.assertNull(layRoot(stage).lookup(DIALOG_SELECTOR), "Escape cancels");

            // Minimize keeps the session.
            requestClose(stage);
            LauncherFxScenario.findButton(layRoot(stage), texts.get("close.minimize")).fire();

            Assertions.assertNull(layRoot(stage).lookup(DIALOG_SELECTOR));
            Assertions.assertTrue(stage.isIconified());
            Assertions.assertFalse(session.isClosed(), "the chat keeps running");

            // Quit closes the session and saves the window.
            requestClose(stage);

            Assertions.assertFalse(stage.isIconified(), "the request brings the window back");

            LauncherFxScenario.findButton(layRoot(stage), texts.get("close.quit")).fire();

            Assertions.assertTrue(session.isClosed());
            Assertions.assertNotNull(settingsFile.read().getWindow());

            return null;
        });
    }
}
