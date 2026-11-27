package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.nodes.NodePingResult;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.translation.LauncherTranslation;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherShell;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.launcher.ui.Tone;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.translate.service.TranslationPresetUtils;
import org.fogofwarlabs.sc4j.translate.service.TranslationStorage;
import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tests the Settings screen offscreen: two columns of equal width, the launcher panel's fields stacked one per row on the left (the version
 * first, the update source last) and the Beacon.Link panel with the peer list under it on the right, with one header height, the same room
 * right of the header controls as above and below them and on every side of the peer footer; Peers shows and hides the list and keeps the
 * header heights, and Ping asks for the listed peers and marks every row as being pinged until the results come. Under the launcher panel the
 * chat translation panel lists the offered presets with their state, Download shows the licence before anything is downloaded, and the
 * model choice reports the chosen preset.
 */
final class OptionsScreenTest {
    private static final @NotNull String VERSION = "1.1.0";
    private static final @NotNull String SOURCE = "owner/sc4j";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final double MINIMUM_WINDOW_WIDTH = 1100;
    private static final double MINIMUM_WINDOW_HEIGHT = 720;
    private static final @NotNull String SMALL_PRESET_ID = "small";
    private static final @NotNull String SMALL_PRESET_FILE = "model.bin";
    private static final int SMALL_PRESET_SIZE = 16;
    private static final int PEER_TOGGLES = 4;
    private static final @NotNull String NEWER_VERSION = "1.2.0";
    private static final double TOLERANCE = 1;
    private static final @NotNull String PANEL_TITLE_SELECTOR = ".panel-title";
    private static final @NotNull String FORM_SELECTOR = ".form";
    private static final @NotNull String HEADER_SELECTOR = ".panel-header";
    private static final int NODE_PORT = 7455;
    private static final @NotNull List<@NotNull ConfiguredNode> NODES = List.of(ConfiguredNode.of("node.example.org", NODE_PORT, null),
                                                                                ConfiguredNode.of("192.0.2.10", NODE_PORT, null),
                                                                                ConfiguredNode.of("seed.example.net", NODE_PORT + 1, null));

    /**
     * Records the pinged peers, holding their results back, the chosen translation presets and the opened addresses.
     */
    private static final class PingActions implements OptionsScreen.Actions {
        private final @NotNull List<@NotNull ConfiguredNode> pinged;
        private final @NotNull List<@Nullable String> chosenPresets;
        private final @NotNull List<@NotNull String> opened;

        /**
         * Creates a {@link PingActions} instance.
         */
        private PingActions() {
            this.pinged        = new ArrayList<>();
            this.chosenPresets = new ArrayList<>();
            this.opened        = new ArrayList<>();
        }

        @Override
        public void onLanguageChanged(@Nullable UiLanguage language) {
        }

        @Override
        public void onChatSoundMutedChanged(boolean muted) {
        }

        @Override
        public @Nullable String onNodesChanged(@NotNull List<@NotNull ConfiguredNode> nodes) {
            return null;
        }

        @Override
        public void onPingNodes(@NotNull List<@NotNull ConfiguredNode> nodes, @NotNull Consumer<@NotNull List<@NotNull NodePingResult>> results) {
            this.pinged.addAll(nodes);
        }

        @Override
        public void onTranslationPresetChanged(@Nullable String presetId) {
            this.chosenPresets.add(presetId);
        }

        @Override
        public void onOpenWebsite(@NotNull String address) {
            this.opened.add(address);
        }
    }

    private static final class SmallPreset implements TranslationPreset {
        @Override
        public @NotNull String getId() {
            return SMALL_PRESET_ID;
        }

        @Override
        public @NotNull String getDisplayName() {
            return "Small";
        }

        @Override
        public @NotNull String getLicenseName() {
            return "Test";
        }

        @Override
        public @NotNull URI getLicenseAddress() {
            return URI.create("https://example.org/license");
        }

        @Override
        public @NotNull List<@NotNull PresetFile> getFiles() {
            return List.of(new PresetFile(SMALL_PRESET_FILE, URI.create("https://example.org/model.bin"), SMALL_PRESET_SIZE, ""));
        }

        @Override
        public @NotNull List<@NotNull Locale> getSupportedLanguages() {
            return List.of(Locale.ENGLISH);
        }

        @Override
        public @Nullable String findCode(@NotNull Locale language) {
            return null;
        }

        @Override
        public @Nullable Locale findLanguage(@NotNull String code) {
            return null;
        }

        @Override
        public @NotNull TranslationModel createTranslationModel(@NotNull Path folder) throws ModelException {
            throw new ModelException("The small preset has no model.");
        }

        @Override
        public @Nullable LanguageIdentifier createLanguageIdentifier(@NotNull Path folder) {
            return null;
        }
    }

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static long countShownLabels(@NotNull Node root, @NotNull String text) {
        return root.lookupAll(".label").stream()
                   .filter(node -> node instanceof Label label && text.equals(label.getText()) && LauncherFxScenario.isShown(node))
                   .count();
    }

    private static @NotNull UpdateView createUpdateView(@NotNull LauncherTexts texts) {
        return new UpdateView(texts, VERSION, SOURCE, new UpdateView.Actions() {
            @Override
            public void onCheck(@NotNull String source) {
            }

            @Override
            public void onUpdate() {
            }
        });
    }

    private static @NotNull OptionsScreen createScreen(@NotNull LauncherTexts texts, @NotNull Path directory, @NotNull PingActions actions) {
        return createScreen(texts, directory, actions, createUpdateView(texts));
    }

    private static @NotNull OptionsScreen createScreen(@NotNull LauncherTexts texts, @NotNull Path directory, @NotNull PingActions actions,
                                                       @NotNull UpdateView view) {
        return createScreen(createShell(texts, directory), texts, directory, actions, view);
    }

    private static @NotNull OptionsScreen createScreen(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull Path directory,
                                                       @NotNull PingActions actions, @NotNull UpdateView view) {
        return createScreen(shell, texts, directory, actions, view, TranslationPresetUtils.getPresets());
    }

    private static @NotNull OptionsScreen createScreen(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull Path directory,
                                                       @NotNull PingActions actions, @NotNull UpdateView view,
                                                       @NotNull List<@NotNull TranslationPreset> presets) {
        InstallLayout install = InstallLayout.of(directory);
        LauncherTranslation translation = new LauncherTranslation(TranslationStorage.of(install.getUserDataDirectory()), presets);

        return new OptionsScreen(shell, texts, null, false, view, NODES, null, new LauncherNodeRunner(install), translation, actions);
    }

    private static @NotNull ComboBox<?> findModelChoice(@NotNull Node root, @NotNull LauncherTexts texts) {
        return (ComboBox<?>) root.lookupAll(".combo-box").stream()
                                 .filter(node -> node instanceof ComboBox<?> box && !box.getItems().isEmpty() &&
                                                 texts.get("translation.none").equals(box.getItems().getFirst().toString()))
                                 .findFirst()
                                 .orElseThrow();
    }

    private static @NotNull LauncherShell createShell(@NotNull LauncherTexts texts, @NotNull Path directory) {
        return new LauncherShell(texts, InstallLayout.of(directory), 1, section -> {
        }, () -> {
        }, () -> {
        });
    }

    private static @NotNull Bounds getSceneBounds(@NotNull Node node) {
        return node.localToScene(node.getLayoutBounds());
    }

    /**
     * Lists the titles of the shown panels of a column from the top.
     */
    private static @NotNull List<@NotNull String> listPanelTitles(@NotNull Parent column) {
        List<String> titles = new ArrayList<>();

        for (Node panel : column.getChildrenUnmodifiable()) {
            if (LauncherFxScenario.isShown(panel)) {
                titles.add(((Label) panel.lookup(PANEL_TITLE_SELECTOR)).getText());
            }
        }

        return titles;
    }

    @Test
    void settings_screen_shows_two_columns_of_equal_width(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);

        LauncherFxScenario.run(() -> {
            UpdateView view = createUpdateView(texts);
            GridPane root = createScreen(texts, directory, new PingActions(), view).getRoot();

            view.showStatus(texts.get("update.current"), Tone.SUCCESS, false);
            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), UiLanguage.ENGLISH);
            root.applyCss();
            root.layout();

            Parent left = (Parent) root.getChildren().get(0);
            Parent right = (Parent) root.getChildren().get(1);
            Bounds leftBounds = getSceneBounds(left);
            Bounds rightBounds = getSceneBounds(right);
            List<Node> fields = ((Parent) left.lookup(FORM_SELECTOR)).getChildrenUnmodifiable();

            // Two columns of equal width side by side over the whole screen.
            Assertions.assertEquals(2, root.getChildren().size());
            Assertions.assertEquals(leftBounds.getWidth(), rightBounds.getWidth(), TOLERANCE, "the columns are equally wide");
            Assertions.assertEquals(leftBounds.getMinY(), rightBounds.getMinY(), TOLERANCE);
            Assertions.assertTrue(leftBounds.getMaxX() < rightBounds.getMinX(), "the launcher column is on the left");
            Assertions.assertEquals(SCENE_WIDTH, rightBounds.getMaxX() - leftBounds.getMinX(), TOLERANCE, "the columns fill the width");

            // The launcher panel on the left: version, language, sound and update source, one field per row; the chat translation panel
            // under it.
            Assertions.assertEquals(List.of(texts.get("settings.launcher"), texts.get("settings.translation")), listPanelTitles(left));
            Assertions.assertEquals(List.of(view.getVersionField(), fields.get(1), fields.get(2), view.getSourceField()), fields);

            for (int index = 1; index < fields.size(); index++) {
                Bounds above = getSceneBounds(fields.get(index - 1));
                Bounds below = getSceneBounds(fields.get(index));

                Assertions.assertEquals(above.getMinX(), below.getMinX(), TOLERANCE, "the fields start in one line");
                Assertions.assertTrue(above.getMaxY() <= below.getMinY(), "field " + index + " is under the one before it");
            }

            // The Beacon.Link panel on the right, and the peer list under it once shown.
            Assertions.assertEquals(List.of(texts.get("beaconlink.title")), listPanelTitles(right));
            Assertions.assertEquals(2, LauncherFxScenario.assertHeaderInsets(root), "Check now and Peers are checked");
            view.showStatus(texts.format("update.available", NEWER_VERSION), Tone.ACCENT, true);
            root.applyCss();
            root.layout();
            LauncherFxScenario.findButton(root, texts.get("beaconlink.advanced.show")).fire();
            root.applyCss();
            root.layout();

            Assertions.assertEquals(List.of(texts.get("beaconlink.title"), texts.get("nodes.title")), listPanelTitles(right));
            Assertions.assertTrue(getSceneBounds(right.getChildrenUnmodifiable().get(0)).getMaxY() <=
                                  getSceneBounds(right.getChildrenUnmodifiable().get(1)).getMinY(), "the peer list is under the Beacon.Link panel");
            Assertions.assertEquals(3, LauncherFxScenario.assertHeaderInsets(root), "Check now, Peers and Ping are checked");
            Assertions.assertEquals(1, LauncherFxScenario.assertFooterInsets(root), "the peer footer is checked");

            // Peers hides and shows the list again; the Beacon.Link header keeps its height.
            Region header = (Region) right.getChildrenUnmodifiable().getFirst().lookup(HEADER_SELECTOR);
            double height = header.getHeight();

            for (int toggle = 0; toggle < PEER_TOGGLES; toggle++) {
                boolean shown = toggle % 2 == 1;

                LauncherFxScenario.findButton(root, texts.get(shown ? "beaconlink.advanced.show" : "beaconlink.advanced.hide")).fire();
                root.applyCss();
                root.layout();

                Assertions.assertEquals(height, header.getHeight(), "the Beacon.Link header keeps its height on toggle " + toggle);
                Assertions.assertEquals(shown ? 3 : 2, LauncherFxScenario.assertHeaderInsets(root), "the shown headers have one height");
            }

            return null;
        });
    }

    @Test
    void translation_panel_shows_the_licence_before_a_download_and_reports_the_chosen_model(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        TranslationPreset preset = TranslationPresetUtils.getPresets().getFirst();
        PingActions actions = new PingActions();

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory);
            OptionsScreen screen = createScreen(shell, texts, directory, actions, createUpdateView(texts));
            Parent root = shell.getRoot();
            ComboBox<?> choice;

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), UiLanguage.ENGLISH);
            shell.show(LauncherShell.Section.SETTINGS, screen.getRoot());
            root.applyCss();
            root.layout();

            // The preset is listed with its state, and only Download is offered for a preset that is not installed.
            Assertions.assertEquals(1, countShownLabels(root, preset.getDisplayName()));
            Assertions.assertEquals(1, countShownLabels(root, texts.get("translation.missing")));
            Assertions.assertTrue(root.lookupAll(".button").stream()
                                      .noneMatch(node -> node instanceof Button button && texts.get("translation.delete").equals(button.getText()) &&
                                                         LauncherFxScenario.isShown(button)), "Delete is offered for an installed preset only");

            // Download shows the licence and the size first; Cancel closes it with nothing downloaded.
            LauncherFxScenario.findButton(root, texts.get("translation.download")).fire();
            root.applyCss();
            root.layout();

            Node dialog = root.lookup(".modal-scrim");

            Assertions.assertNotNull(dialog, "Download asks first");
            Assertions.assertTrue(dialog.lookupAll(".hyperlink").stream()
                                        .anyMatch(link -> link instanceof Hyperlink hyperlink && preset.getLicenseName().equals(hyperlink.getText())));
            ((Hyperlink) dialog.lookup(".hyperlink")).fire();
            Assertions.assertEquals(List.of(preset.getLicenseAddress().toString()), actions.opened);
            LauncherFxScenario.findButton(dialog, texts.get("translation.cancel")).fire();
            root.applyCss();
            root.layout();

            Assertions.assertNull(root.lookup(".modal-scrim"));
            Assertions.assertNull(screen.getRoot().lookupAll(".progress-bar").stream().filter(LauncherFxScenario::isShown).findFirst().orElse(null),
                                  "nothing downloads");
            Assertions.assertFalse(Files.exists(TranslationStorage.of(InstallLayout.of(directory).getUserDataDirectory()).getPresetFolder(preset)));

            // A preset that is not installed is not offered in the model choice.
            choice = findModelChoice(screen.getRoot(), texts);

            Assertions.assertEquals(1, choice.getItems().size(), "only Off is offered");

            return null;
        });
    }

    @Test
    void ping_should_mark_every_listed_peer_until_the_results_come(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        PingActions actions = new PingActions();

        LauncherFxScenario.run(() -> {
            GridPane root = createScreen(texts, directory, actions).getRoot();

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), UiLanguage.ENGLISH);
            root.applyCss();
            root.layout();
            LauncherFxScenario.findButton(root, texts.get("beaconlink.advanced.show")).fire();
            LauncherFxScenario.findButton(root, texts.get("nodes.ping")).fire();
            root.applyCss();
            root.layout();

            Assertions.assertEquals(NODES, actions.pinged);
            Assertions.assertEquals(NODES.size(), countShownLabels(root, texts.get("nodes.ping.running")), "every row is being pinged");

            return null;
        });
    }

    @Test
    void model_choice_offers_the_installed_presets_and_reports_the_chosen_one(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        TranslationPreset preset = new SmallPreset();
        Path folder = TranslationStorage.of(InstallLayout.of(directory).getUserDataDirectory()).getPresetFolder(preset);
        PingActions actions = new PingActions();

        Files.createDirectories(folder);
        Files.write(folder.resolve(SMALL_PRESET_FILE), new byte[SMALL_PRESET_SIZE]);

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory);
            OptionsScreen screen = createScreen(shell, texts, directory, actions, createUpdateView(texts), List.of(preset));
            ComboBox<?> choice = findModelChoice(screen.getRoot(), texts);

            Assertions.assertEquals(2, choice.getItems().size(), "Off and the installed preset are offered");
            choice.getSelectionModel().select(1);
            choice.getSelectionModel().select(0);

            Assertions.assertEquals(Arrays.asList(SMALL_PRESET_ID, null), actions.chosenPresets);

            return null;
        });
    }

    @Test
    void showing_the_peer_list_keeps_the_columns_inside_the_smallest_window(@TempDir @NotNull Path directory) throws Exception {
        for (UiLanguage language : UiLanguage.values()) {
            LauncherTexts texts = LauncherTexts.of(language);

            LauncherFxScenario.run(() -> {
                LauncherShell shell = createShell(texts, directory);
                OptionsScreen screen = createScreen(shell, texts, directory, new PingActions(), createUpdateView(texts));
                Parent root = shell.getRoot();
                GridPane grid = screen.getRoot();

                ThemeUtils.apply(new Scene(root, MINIMUM_WINDOW_WIDTH, MINIMUM_WINDOW_HEIGHT), language);
                shell.show(LauncherShell.Section.SETTINGS, grid);
                root.applyCss();
                root.layout();

                double width = grid.getWidth();
                double leftWidth = ((Region) grid.getChildren().getFirst()).getWidth();

                LauncherFxScenario.findButton(root, texts.get("beaconlink.advanced.show")).fire();
                root.applyCss();
                root.layout();

                Assertions.assertTrue(grid.minWidth(-1) <= width, language + ": the peer list fits the screen width");
                Assertions.assertEquals(width, grid.getWidth(), TOLERANCE, language + ": the screen keeps its width");
                Assertions.assertEquals(leftWidth, ((Region) grid.getChildren().getFirst()).getWidth(), TOLERANCE,
                                        language + ": the launcher column keeps its width");

                return null;
            });
        }
    }
}
