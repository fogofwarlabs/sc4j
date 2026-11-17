package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.fogofwarlabs.sc4j.launcher.settings.LauncherSettings;
import org.fogofwarlabs.sc4j.launcher.settings.LauncherSettingsFile;
import org.fogofwarlabs.sc4j.launcher.settings.WindowState;
import org.fogofwarlabs.sc4j.launcher.ui.session.Conversations;
import org.fogofwarlabs.sc4j.launcher.ui.session.RecordingLauncherSession;
import org.fogofwarlabs.sc4j.launcher.update.LauncherRelease;
import org.fogofwarlabs.sc4j.launcher.update.LauncherUpdater;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests where the launcher window opens: its bounds fit the free area of a 1920 by 1080 pixel screen at display scales of 100%, 150% and
 * 200% (in JavaFX logical units), saved bounds are kept on their screen, and saved bounds on no screen are dropped. A display change to
 * another scale decodes the images again and fits the window to its screen without a restart. The launcher starts on the login step (with
 * the New account dialog when there is no account, else with the last used account selected); Log in opens the main view with the account
 * at the top right and no Accounts tab and starts the account's session, which connects to the node of the install's node list on
 * 127.0.0.1, and Log out from the account menu closes the session, frees the account and returns to the login step, at 100% and 200% in
 * English and Korean. Whisper in the account menu's Friends shows the Chat screen with the friend's conversation selected and the input
 * focused, from another screen and from the Chat screen. Open mods folder hands the mods folder to the folder opener, and a mod's website
 * link goes to the document opener.
 * The Update dialog of a newer launcher version opens by itself once per run, once logged in, while no game runs and not for a skipped
 * version.
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
    private static final double WINDOW_ICON_SIZE = 64;
    private static final double TOLERANCE = 1;
    private static final double FIRST_SCALE = 1;
    private static final double CHANGED_SCALE = 2;
    private static final long FX_TIMEOUT_SECONDS = 60;
    private static final @NotNull String VERSION = "1.0.0";
    private static final double PERCENT_SCALE = 100;
    private static final long RAYNOR_USE_MILLIS = Instant.parse("2026-11-10T18:30:00Z").toEpochMilli();
    private static final @NotNull String NAVIGATION_SELECTOR = ".nav-button";
    private static final @NotNull String ACCOUNT_SELECTOR = ".account-button";
    private static final @NotNull String MENU_SELECTOR = ".menu-panel";
    private static final @NotNull String DIALOG_SELECTOR = ".modal-scrim";
    private static final @NotNull String SUCCESS_MARKER = "status-marker-success";
    private static final @NotNull String HYPERLINK_SELECTOR = ".hyperlink";
    private static final @NotNull String LIST_SELECTOR = ".list-view";
    private static final @NotNull String TEXT_FIELD_SELECTOR = ".text-field";
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

    private static @NotNull Rectangle2D createScreen(double scale) {
        return new Rectangle2D(0, 0, SCREEN_WIDTH / scale, (SCREEN_HEIGHT - TASK_BAR_HEIGHT) / scale);
    }

    private static void assertInside(@NotNull Rectangle2D screen, @NotNull Rectangle2D bounds) {
        Assertions.assertTrue(bounds.getMinX() >= screen.getMinX() && bounds.getMaxX() <= screen.getMaxX(), bounds::toString);
        Assertions.assertTrue(bounds.getMinY() >= screen.getMinY() && bounds.getMaxY() <= screen.getMaxY(), bounds::toString);
    }

    private static void assertImagesDecodedAt(@NotNull Parent root, double scale) {
        List<ImageView> views = root.lookupAll("ImageView").stream().map(ImageView.class::cast).toList();

        Assertions.assertFalse(views.isEmpty(), "the shell shows images");

        for (ImageView view : views) {
            Image image = view.getImage();
            double shownWidth = view.getBoundsInLocal().getWidth();

            Assertions.assertNotNull(image);
            Assertions.assertTrue(image.getWidth() >= shownWidth * scale - TOLERANCE,
                                  () -> "an image of " + image.getWidth() + " pixels is shown " + shownWidth + " units wide at " + scale);
        }
    }

    private static @NotNull UiLanguage selectLanguage(@NotNull String languageCode) {
        return languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
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
            LauncherUpdater updater = new LauncherUpdater(InstallLayout.of(directory), new ReleaseClient(server.getApi()));
            ReleaseRepository repository = ReleaseRepository.parse(UPDATE_REPOSITORY);
            ReleaseVersion olderSkipped = ReleaseVersion.parse("1.1.5");
            LauncherRelease newer;
            LauncherSettings skipped;

            Assertions.assertNotNull(repository);
            Assertions.assertNotNull(olderSkipped);
            server.addCheckedRelease(UPDATE_REPOSITORY, "v1.2.0", Map.of("SC4J-1.2.0.zip", new byte[]{1}));
            newer = updater.findUpdate(repository, ReleaseVersion.parse("1.1.0"));
            Assertions.assertNotNull(newer);
            skipped = LauncherSettings.DEFAULT.withSkippedVersion(newer.getVersion());

            Assertions.assertTrue(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, true, false, false), "newer");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(updater.findUpdate(repository, ReleaseVersion.parse("1.2.0")), LauncherSettings.DEFAULT,
                                                                  true, false, false), "same");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(newer, skipped, true, false, false), "skipped");
            Assertions.assertTrue(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT.withSkippedVersion(olderSkipped), true, false, false),
                                  "a release after the skipped one");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, true, true, false), "running game");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, false, false, false), "login step");
            Assertions.assertFalse(LauncherWindow.isUpdateOffered(newer, LauncherSettings.DEFAULT, true, false, true), "once per run");
        }
    }

    @Test
    void fit_display_should_decode_the_images_again_and_fit_the_window_when_the_scale_changes(@TempDir @NotNull Path directory) throws Exception {
        InstallLayout install = InstallLayout.of(directory);
        CompletableFuture<Void> result = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                Stage stage = new Stage();
                LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
                }, folder -> {
                }, new LauncherNodeRunner(install), account -> LauncherSession.open(install, account, VERSION, Platform::runLater));
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
                assertImagesDecodedAt(changedRoot, CHANGED_SCALE);
                Assertions.assertEquals(WINDOW_ICON_SIZE * CHANGED_SCALE, stage.getIcons().getFirst().getWidth());
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

    @ParameterizedTest
    @CsvSource({"1.0, en", "2.0, en", "1.0, ko", "2.0, ko"})
    void launcher_without_accounts_starts_on_the_login_step_with_the_new_account_dialog(double scale, @NotNull String languageCode,
                                                                                        @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = selectLanguage(languageCode);
        LauncherTexts texts = LauncherTexts.of(language);
        String suffix = Math.round(scale * PERCENT_SCALE) + "-" + language.getCode();
        InstallLayout install = InstallLayout.of(directory);

        new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(language));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> LauncherSession.open(install, account, VERSION, Platform::runLater));

            window.fitDisplay(scale, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));

            Parent root = layRoot(stage);
            Node dialog = root.lookup(DIALOG_SELECTOR);

            Assertions.assertTrue(listVisibleNavigation(root).isEmpty(), "the login step comes first");
            Assertions.assertNotNull(dialog, "the New account dialog is shown first");
            Assertions.assertNotNull(dialog.lookup(".text-field"));
            Assertions.assertTrue(LauncherFxScenario.findButton(root, texts.get("accounts.log.in")).isDisabled());
            LauncherFxScenario.saveSample(root, scale, "window-login-empty-" + suffix);

            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({"1.0, en", "2.0, en", "1.0, ko", "2.0, ko"})
    void launcher_logs_in_from_the_login_step_and_logs_out_back_to_it(double scale, @NotNull String languageCode, @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = selectLanguage(languageCode);
        LauncherTexts texts = LauncherTexts.of(language);
        String suffix = Math.round(scale * PERCENT_SCALE) + "-" + language.getCode();
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        Account raynor = store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        List<LauncherSession> sessions = new ArrayList<>();
        AtomicReference<Stage> shown = new AtomicReference<>();

        try (LauncherSessionScenario scenario = new LauncherSessionScenario(directory.resolve("node"))) {
            scenario.writeNodeList(install);
            new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(language));
            LauncherFxScenario.run(() -> {
                Stage stage = new Stage();
                LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
                }, folder -> {
                }, new LauncherNodeRunner(install), account -> {
                    LauncherSession session = LauncherSession.open(install, account, VERSION, Platform::runLater);

                    sessions.add(session);

                    return session;
                });

                shown.set(stage);
                window.fitDisplay(scale, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));

                // The login step: the last used account selected, no navigation, no account at the top right.
                Parent root = layRoot(stage);
                @SuppressWarnings("unchecked")
                ListView<Account> list = (ListView<Account>) root.lookup(".list-view");

                Assertions.assertTrue(listVisibleNavigation(root).isEmpty());
                Assertions.assertFalse(LauncherFxScenario.isShown(root.lookup(ACCOUNT_SELECTOR)));
                Assertions.assertNull(root.lookup(DIALOG_SELECTOR), "with an account no dialog opens");
                Assertions.assertEquals(raynor.getId(), list.getSelectionModel().getSelectedItem().getId());
                LauncherFxScenario.saveSample(root, scale, "window-login-" + suffix);
                LauncherFxScenario.findButton(root, texts.get("accounts.log.in")).fire();

                return null;
            });

            // The main view: the account's session connects to the install's node and the account turns green at the top right.
            LauncherFxScenario.await(() -> sessions.size() == 1 && sessions.getFirst().getSelf().getState() == SessionState.CONNECTED &&
                                           layRoot(shown.get()).lookup(ACCOUNT_SELECTOR).lookup("." + SUCCESS_MARKER) != null,
                                     "the account's session connects");

            Assertions.assertTrue(store.isInUse(raynor), "the logged-in launcher uses the account");

            LauncherFxScenario.run(() -> {
                Parent root = layRoot(shown.get());
                Button account = (Button) root.lookup(ACCOUNT_SELECTOR);

                Assertions.assertEquals(List.of(texts.get("nav.mods"), texts.get("nav.run"), texts.get("nav.chat"), texts.get("nav.settings")),
                                        listVisibleNavigation(root), "no Accounts tab");
                Assertions.assertTrue(LauncherFxScenario.isShown(account));
                Assertions.assertEquals(raynor.getTaggedName(), account.getText());
                LauncherFxScenario.saveSample(root, scale, "window-main-" + suffix);

                account.fire();
                root = layRoot(shown.get());

                Assertions.assertNotNull(root.lookup(MENU_SELECTOR));
                LauncherFxScenario.saveSample(root, scale, "window-menu-" + suffix);
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
                });

                window.fitDisplay(FIRST_SCALE, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
                LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();

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

    @ParameterizedTest
    @CsvSource({"1.0, en", "2.0, en", "1.0, ko", "2.0, ko"})
    void whisper_from_friends_opens_the_chat_tab_on_the_friend_conversation(double scale, @NotNull String languageCode,
                                                                            @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = selectLanguage(languageCode);
        LauncherTexts texts = LauncherTexts.of(language);
        String suffix = Math.round(scale * PERCENT_SCALE) + "-" + language.getCode();
        InstallLayout install = InstallLayout.of(directory);
        AccountStore store = AccountStore.of(install.getBeaconLinkDirectory());
        RecordingLauncherSession session = new RecordingLauncherSession(createCode(1), new SelfView(SessionState.CONNECTED, SelfStatus.ONLINE, "",
                                                                                                    1, null));

        store.create(raynorKey, raynorStamp, PlayerStamp.DEFAULT_DIFFICULTY, RAYNOR_USE_MILLIS);
        session.setFriends(List.of(new FriendView(createCode(2), "Kerrigan#a1b2c3", FriendState.IN_LAUNCHER, null, "", 1, false),
                                   new FriendView(createCode(3), "Zeratul#d4e5f6", FriendState.IN_LAUNCHER, null, "", 1, false)));
        new LauncherSettingsFile(install.getLauncherSettingsFile()).write(LauncherSettings.DEFAULT.withLanguage(language));
        LauncherFxScenario.run(() -> {
            Stage stage = new Stage();
            LauncherWindow window = new LauncherWindow(stage, install, VERSION, address -> {
            }, folder -> {
            }, new LauncherNodeRunner(install), account -> session);

            window.fitDisplay(scale, new Rectangle2D(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT));
            LauncherFxScenario.findButton(layRoot(stage), texts.get("accounts.log.in")).fire();

            // From the Mods screen: the Chat screen opens on Kerrigan's conversation.
            whisperFromFriends(stage, texts, 0);
            assertChatShowsWhispers(stage, texts, "Kerrigan#a1b2c3");

            LauncherFxScenario.disableViewportCache(layRoot(stage));

            LauncherFxScenario.saveSample(layRoot(stage), scale, "window-whisper-" + suffix);

            // From the Chat screen: the screen shows Zeratul's conversation.
            whisperFromFriends(stage, texts, 1);
            assertChatShowsWhispers(stage, texts, "Zeratul#d4e5f6");

            return null;
        });
    }
}
