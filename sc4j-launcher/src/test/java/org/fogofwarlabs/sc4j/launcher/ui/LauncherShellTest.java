package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.form.SettingsForm;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.ui.mods.ModsScreen;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests the launcher window's content at display scales of 100%, 150% and 200%: the layout keeps its logical size, every image is decoded at
 * its logical size times the scale (so it stays sharp), and the window renders at the scale; each rendering is saved as a sample picture.
 * Before the login the app bar shows neither the navigation nor an account; after it, it lists Mods, Run log, Chat and Settings (no Accounts tab)
 * and ends with the account button with its status, which opens the account menu, at 100% and 200% in English and Korean; every entry's
 * text is centred in the entry, and the Chat entry shows its dot over the text's top right corner without moving or widening the entries.
 * In a window of the
 * launcher's minimum size, a screen taller than the space between the app bar and the status bar scrolls and leaves both bars in the window; a
 * shorter screen fills that space. The Mods screen's mod list keeps the regular list item, and its footers keep the same room on every side of
 * their buttons; an empty mod list shows its text centred, also on several lines, with the room of a panel body on its sides.
 */
final class LauncherShellTest {
    private static final @NotNull String MANIFEST = """
                                                    {
                                                      "format": 1,
                                                      "id": "example",
                                                      "name": {"en": "Example mod"},
                                                      "version": "1.0.0",
                                                      "engine": "0.1",
                                                      "entry": "org.example.ExampleMod",
                                                      "description": {"en": "A mod with an icon."},
                                                      "images": {"icon": "meta/icon.png"}
                                                    }
                                                    """;
    private static final @NotNull String ICON_ENTRY = "meta/icon.png";
    private static final @NotNull String JAR_NAME = "example.jar";
    private static final @NotNull String SECOND_JAR_NAME = "second.jar";
    private static final @NotNull String SECOND_MANIFEST = """
                                                           {
                                                             "format": 1,
                                                             "id": "second",
                                                             "name": {"en": "Second mod"},
                                                             "version": "2.1.0",
                                                             "engine": "0.1",
                                                             "entry": "org.example.SecondMod"
                                                           }
                                                           """;
    private static final double REGULAR_CELL_PADDING = 10;
    private static final @NotNull String LONG_FOLDER_NAME = "a-folder-with-a-rather-long-name-for-the-launcher-install";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final double PERCENT_SCALE = 100;
    private static final @NotNull String TAGGED_NAME = "Raynor#60bf07";
    private static final @NotNull String APP_BAR_SELECTOR = ".app-bar";
    private static final @NotNull String NAVIGATION_SELECTOR = ".nav-button";
    private static final @NotNull String DOT_BADGE_SELECTOR = ".dot-badge";
    private static final @NotNull String TEXT_SELECTOR = ".text";
    private static final @NotNull String ACCOUNT_SELECTOR = ".account-button";
    private static final @NotNull String STATUS_BAR_SELECTOR = ".status-bar";
    private static final double MINIMUM_SCENE_WIDTH = 1100;
    private static final double MINIMUM_SCENE_HEIGHT = 720;
    private static final double TALL_SCREEN_HEIGHT = 2000;
    private static final double TOLERANCE = 1;
    private static final double DOT_DISTANCE = 4;
    private static final @NotNull String COMPACT_LIST_STYLE_CLASS = "compact";
    private static final long FX_TIMEOUT_SECONDS = 60;

    /**
     * Receives no action; the test only lays the screen out.
     */
    private static final class NoActions implements ModsScreen.Actions {
        @Override
        public void onSelect(@NotNull ModEntry entry) {
        }

        @Override
        public void onOpenModsFolder() {
        }

        @Override
        public void onRefresh() {
        }

        @Override
        public void onRun(@NotNull ModEntry entry, @NotNull SettingsForm form) {
        }

        @Override
        public void onCopy(@NotNull String text) {
        }

        @Override
        public void onOpenWebsite(@NotNull String address) {
        }

        @Override
        public void onUpdate(@NotNull ModEntry entry) {
        }

        @Override
        public void onChangeVersion(@NotNull ModEntry entry) {
        }

        @Override
        public void onRemove(@NotNull ModEntry entry) {
        }

        @Override
        public void onInstall() {
        }
    }

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    /**
     * Checks that the account button is the app bar's last item and ends at the bar's right padding.
     */
    private static void assertAtRightEnd(@NotNull HBox bar, @NotNull Button account) {
        Assertions.assertSame(account, bar.getChildren().getLast(), "the account button ends the app bar");
        Assertions.assertEquals(bar.getWidth() - bar.getPadding().getRight(), account.getBoundsInParent().getMaxX(), TOLERANCE);
    }

    /**
     * Checks that a navigation entry's text is centred in the entry and that the entry is as wide as its text and padding, so its dot badge
     * takes no room.
     */
    private static void assertTextCentred(@NotNull ToggleButton button) {
        Bounds text = button.lookup(TEXT_SELECTOR).getBoundsInParent();
        Insets insets = button.getInsets();

        Assertions.assertEquals(insets.getLeft() + (button.getWidth() - insets.getLeft() - insets.getRight()) / 2, text.getCenterX(), TOLERANCE,
                                button.getText() + " is centred horizontally");
        Assertions.assertEquals(insets.getTop() + (button.getHeight() - insets.getTop() - insets.getBottom()) / 2, text.getCenterY(), TOLERANCE,
                                button.getText() + " is centred vertically");
        Assertions.assertEquals(text.getWidth() + insets.getLeft() + insets.getRight(), button.getWidth(), TOLERANCE,
                                button.getText() + " has no room for the dot");
    }

    /**
     * Checks that the shown dot of a navigation entry sits at the top right corner of the entry's text, at most {@link #DOT_DISTANCE} away.
     */
    private static void assertDotAtTextCorner(@NotNull ToggleButton button) {
        Node textNode = button.lookup(TEXT_SELECTOR);
        Region dotNode = (Region) button.lookup(DOT_BADGE_SELECTOR);
        Bounds text = textNode.localToScene(textNode.getLayoutBounds());
        Bounds dot = dotNode.localToScene(dotNode.getLayoutBounds());
        double outsideX = Math.max(0, Math.max(dot.getMinX() - text.getMaxX(), text.getMaxX() - dot.getMaxX()));
        double outsideY = Math.max(0, Math.max(dot.getMinY() - text.getMinY(), text.getMinY() - dot.getMaxY()));

        Assertions.assertTrue(dotNode.getWidth() > 0 && dotNode.getHeight() > 0, "the dot has its size");
        Assertions.assertTrue(outsideX <= DOT_DISTANCE && outsideY <= DOT_DISTANCE, () -> "the dot " + dot + " sits at the text corner of " + text);
    }

    @ParameterizedTest
    @CsvSource({"1.0, en", "2.0, en", "1.0, ko", "2.0, ko"})
    void app_bar_has_no_accounts_tab_and_its_account_button_opens_the_account_menu(double scale, @NotNull String languageCode,
                                                                                   @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        LauncherTexts texts = LauncherTexts.of(language);
        String suffix = Math.round(scale * PERCENT_SCALE) + "-" + language.getCode();
        InstallLayout install = InstallLayout.of(directory);
        List<LauncherShell.Section> navigated = new ArrayList<>();
        AtomicInteger menuOpened = new AtomicInteger();

        LauncherFxScenario.run(() -> {
            LauncherShell shell = new LauncherShell(texts, install, scale, navigated::add, () -> {
            }, menuOpened::incrementAndGet);
            Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);
            HBox bar = (HBox) shell.getRoot().lookup(APP_BAR_SELECTOR);
            Button account = (Button) bar.lookup(ACCOUNT_SELECTOR);
            List<String> sections = new ArrayList<>();

            ThemeUtils.apply(scene, language);

            // The login step: no navigation and no account in the app bar.
            shell.showLogin(new Region());
            shell.getRoot().applyCss();
            shell.getRoot().layout();

            for (Node button : bar.lookupAll(NAVIGATION_SELECTOR)) {
                Assertions.assertFalse(LauncherFxScenario.isShown(button), "no navigation before the login");
            }

            Assertions.assertFalse(LauncherFxScenario.isShown(account), "no account before the login");
            LauncherFxScenario.saveSample(bar, scale, "top-bar-login-" + suffix);

            // Logged in: Mods, Run log, Chat and Settings, and the account with its status at the right end.
            shell.show(LauncherShell.Section.MODS, new Region());
            shell.setAccount(TAGGED_NAME, Tone.SUCCESS);
            shell.getRoot().applyCss();
            shell.getRoot().layout();

            for (Node button : bar.lookupAll(NAVIGATION_SELECTOR)) {
                sections.add(((ToggleButton) button).getText());
            }

            Assertions.assertEquals(List.of(texts.get("nav.mods"), texts.get("nav.run"), texts.get("nav.chat"), texts.get("nav.settings")), sections,
                                    "no Accounts tab");
            Assertions.assertSame(account, LauncherFxScenario.findButton(bar, TAGGED_NAME));
            Assertions.assertNotNull(account.getGraphic(), "the account shows its status");
            assertAtRightEnd(bar, account);

            for (Node button : bar.lookupAll(NAVIGATION_SELECTOR)) {
                assertTextCentred((ToggleButton) button);
            }

            LauncherFxScenario.saveSample(bar, scale, "top-bar-account-" + suffix);

            // The dot of the Chat entry: shown on that entry only, and the entries keep their place.
            List<Double> places = bar.lookupAll(NAVIGATION_SELECTOR).stream().map(Node::getLayoutX).toList();

            shell.setNavigationDotShown(LauncherShell.Section.CHAT, true);
            shell.getRoot().applyCss();
            shell.getRoot().layout();

            for (Node button : bar.lookupAll(NAVIGATION_SELECTOR)) {
                boolean chat = ((ToggleButton) button).getText().equals(texts.get("nav.chat"));

                Assertions.assertEquals(chat, button.lookup(DOT_BADGE_SELECTOR).isVisible(), ((ToggleButton) button).getText());
                assertTextCentred((ToggleButton) button);

                if (chat) {
                    assertDotAtTextCorner((ToggleButton) button);
                }
            }

            Assertions.assertEquals(places, bar.lookupAll(NAVIGATION_SELECTOR).stream().map(Node::getLayoutX).toList(), "the entries do not move");
            LauncherFxScenario.saveSample(bar, scale, "top-bar-chat-dot-" + suffix);
            account.fire();

            return null;
        });

        Assertions.assertEquals(1, menuOpened.get(), "the account button opens the account menu");
        Assertions.assertTrue(navigated.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, 1.5, 2.0})
    void launcher_content_decodes_images_for_the_display_scale_and_keeps_its_layout(double scale, @TempDir @NotNull Path directory) throws Exception {
        InstallLayout install = InstallLayout.of(directory);
        Map<String, byte[]> entries = new LinkedHashMap<>();

        try (InputStream icon = ThemeUtils.class.getResourceAsStream(ThemeUtils.SC4J_LOGO)) {
            Assertions.assertNotNull(icon);
            entries.put(TestJarUtils.MANIFEST_ENTRY, MANIFEST.getBytes(StandardCharsets.UTF_8));
            entries.put(ICON_ENTRY, icon.readAllBytes());
        }

        Files.createDirectories(install.getModsDirectory());
        TestJarUtils.write(install.getModsDirectory().resolve(JAR_NAME), entries);

        ModCatalog catalog = ModCatalog.scan(install, InstallationRecords.NONE);
        CompletableFuture<Void> result = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
                LauncherShell shell = new LauncherShell(texts, install, scale, section -> {
                }, () -> {
                }, () -> {
                });
                ModsScreen screen = new ModsScreen(install, catalog, texts, scale, new SimpleBooleanProperty(false), new NoActions());
                Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);
                ModEntry entry = catalog.findByJarName(JAR_NAME);

                Assertions.assertNotNull(entry);
                shell.show(LauncherShell.Section.MODS, screen.getRoot());
                screen.select(entry);
                ThemeUtils.apply(scene, UiLanguage.ENGLISH);
                shell.getRoot().applyCss();
                shell.getRoot().layout();

                Assertions.assertEquals(SCENE_WIDTH, shell.getRoot().getWidth(), "the layout is in logical units at every scale");

                for (Node node : shell.getRoot().lookupAll("ImageView")) {
                    ImageView view = (ImageView) node;
                    Image image = view.getImage();
                    double shownWidth = view.getBoundsInLocal().getWidth();

                    Assertions.assertNotNull(image);
                    Assertions.assertTrue(image.getWidth() >= shownWidth * scale - TOLERANCE,
                                          () -> "an image of " + image.getWidth() + " pixels is shown " + shownWidth + " units wide at " + scale);
                }

                LauncherFxScenario.saveSample(shell.getRoot(), scale, "launcher-scale-" + Math.round(scale * PERCENT_SCALE));
                result.complete(null);
            } catch (IOException | RuntimeException | Error exception) {
                result.completeExceptionally(exception);
            }
        });

        result.get(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void screen_scrolls_between_the_bars_when_it_is_taller_than_the_window(boolean tall, @TempDir @NotNull Path directory) throws Exception {
        InstallLayout install = InstallLayout.of(directory);

        LauncherFxScenario.run(() -> {
            LauncherShell shell = new LauncherShell(LauncherTexts.of(UiLanguage.ENGLISH), install, 1, section -> {
            }, () -> {
            }, () -> {
            });
            Scene scene = new Scene(shell.getRoot(), MINIMUM_SCENE_WIDTH, MINIMUM_SCENE_HEIGHT);
            Region screen = new Region();

            if (tall) {
                screen.setMinHeight(TALL_SCREEN_HEIGHT);
            }

            ThemeUtils.apply(scene, UiLanguage.ENGLISH);
            shell.show(LauncherShell.Section.SETTINGS, screen);
            shell.getRoot().applyCss();
            shell.getRoot().layout();

            Node appBar = shell.getRoot().lookup(APP_BAR_SELECTOR);
            Node statusBar = shell.getRoot().lookup(STATUS_BAR_SELECTOR);
            ScrollPane scroll = (ScrollPane) shell.getRoot().lookup(".scroll-pane");

            Assertions.assertEquals(0, appBar.localToScene(appBar.getBoundsInLocal()).getMinY(), TOLERANCE, "the app bar starts at the top");
            Assertions.assertEquals(MINIMUM_SCENE_HEIGHT, statusBar.localToScene(statusBar.getBoundsInLocal()).getMaxY(), TOLERANCE,
                                    "the status bar ends at the bottom");
            Assertions.assertNotNull(scroll);

            double viewport = scroll.getViewportBounds().getHeight();
            double content = scroll.getContent().getLayoutBounds().getHeight();

            if (tall) {
                Assertions.assertTrue(content > viewport + TOLERANCE, "a taller screen scrolls");
            } else {
                Assertions.assertEquals(viewport, content, TOLERANCE, "a shorter screen fills the space");
            }

            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({"1.0, en", "2.0, en", "1.0, ko", "2.0, ko"})
    void mods_list_keeps_the_regular_list_item(double scale, @NotNull String languageCode, @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        InstallLayout install = InstallLayout.of(directory);
        Map<String, byte[]> entries = new LinkedHashMap<>();

        try (InputStream icon = ThemeUtils.class.getResourceAsStream(ThemeUtils.SC4J_LOGO)) {
            Assertions.assertNotNull(icon);
            entries.put(TestJarUtils.MANIFEST_ENTRY, MANIFEST.getBytes(StandardCharsets.UTF_8));
            entries.put(ICON_ENTRY, icon.readAllBytes());
        }

        Files.createDirectories(install.getModsDirectory());
        TestJarUtils.write(install.getModsDirectory().resolve(JAR_NAME), entries);
        TestJarUtils.write(install.getModsDirectory().resolve(SECOND_JAR_NAME),
                           Map.of(TestJarUtils.MANIFEST_ENTRY, SECOND_MANIFEST.getBytes(StandardCharsets.UTF_8)));

        ModCatalog catalog = ModCatalog.scan(install, InstallationRecords.NONE);

        LauncherFxScenario.run(() -> {
            LauncherTexts texts = LauncherTexts.of(language);
            LauncherShell shell = new LauncherShell(texts, install, scale, section -> {
            }, () -> {
            }, () -> {
            });
            ModsScreen screen = new ModsScreen(install, catalog, texts, scale, new SimpleBooleanProperty(false), new NoActions());
            Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);
            ModEntry entry = catalog.findByJarName(JAR_NAME);

            Assertions.assertNotNull(entry);
            shell.show(LauncherShell.Section.MODS, screen.getRoot());
            screen.select(entry);
            ThemeUtils.apply(scene, language);
            shell.getRoot().applyCss();
            shell.getRoot().layout();

            ListView<?> list = (ListView<?>) shell.getRoot().lookup(".list-view");
            ListCell<?> cell = (ListCell<?>) list.lookup(".list-cell");

            Assertions.assertFalse(list.getStyleClass().contains(COMPACT_LIST_STYLE_CLASS));
            Assertions.assertEquals(REGULAR_CELL_PADDING, cell.getPadding().getTop(), TOLERANCE);
            Assertions.assertTrue(LauncherFxScenario.assertFooterInsets(shell.getRoot()) >= 2, "the list and the detail footers are checked");
            LauncherFxScenario.saveSample(shell.getRoot(), scale, "mods-list-" + Math.round(scale * PERCENT_SCALE) + "-" + language.getCode());

            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({"1.0, en", "1.0, ko"})
    void empty_mod_list_centres_its_wrapped_text(double scale, @NotNull String languageCode, @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        InstallLayout install = InstallLayout.of(directory.resolve(LONG_FOLDER_NAME).resolve(LONG_FOLDER_NAME));

        Files.createDirectories(install.getModsDirectory());

        ModCatalog catalog = ModCatalog.scan(install, InstallationRecords.NONE);

        LauncherFxScenario.run(() -> {
            LauncherTexts texts = LauncherTexts.of(language);
            LauncherShell shell = new LauncherShell(texts, install, scale, section -> {
            }, () -> {
            }, () -> {
            });
            ModsScreen screen = new ModsScreen(install, catalog, texts, scale, new SimpleBooleanProperty(false), new NoActions());
            Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);

            shell.show(LauncherShell.Section.MODS, screen.getRoot());
            ThemeUtils.apply(scene, language);
            shell.getRoot().applyCss();
            shell.getRoot().layout();

            LauncherFxScenario.assertPlaceholderCentred((ListView<?>) shell.getRoot().lookup(".list-view"));
            Assertions.assertTrue(LauncherFxScenario.assertFooterInsets(shell.getRoot()) >= 1, "the list footer is checked");
            LauncherFxScenario.saveSample(shell.getRoot(), scale, "mods-empty-" + Math.round(scale * PERCENT_SCALE) + "-" + language.getCode());

            return null;
        });
    }
}
