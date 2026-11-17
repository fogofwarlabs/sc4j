package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.nodes.NodePingResult;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tests the Settings screen offscreen: two columns of equal width, the launcher panel's fields stacked one per row on the left and the
 * Beacon.Link panel with the peer list under it on the right, at 100% and 200% in English and Korean; Peers shows the list, and Ping asks
 * for the listed peers and marks every row as being pinged until the results come.
 */
final class OptionsScreenTest {
    private static final @NotNull String VERSION = "1.1.0";
    private static final @NotNull String SOURCE = "owner/sc4j";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final double PERCENT_SCALE = 100;
    private static final double TOLERANCE = 1;
    private static final @NotNull String PANEL_TITLE_SELECTOR = ".panel-title";
    private static final @NotNull String FORM_SELECTOR = ".form";
    private static final int NODE_PORT = 7455;
    private static final @NotNull List<@NotNull ConfiguredNode> NODES = List.of(ConfiguredNode.of("node.example.org", NODE_PORT, null),
                                                                                ConfiguredNode.of("192.0.2.10", NODE_PORT, null),
                                                                                ConfiguredNode.of("seed.example.net", NODE_PORT + 1, null));

    /**
     * Records the pinged peers and holds their results back.
     */
    private static final class PingActions implements OptionsScreen.Actions {
        private final @NotNull List<@NotNull ConfiguredNode> pinged;

        /**
         * Creates a {@link PingActions} instance.
         */
        private PingActions() {
            this.pinged = new ArrayList<>();
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

    private static @NotNull OptionsScreen createScreen(@NotNull LauncherTexts texts, @NotNull Path directory, @NotNull PingActions actions) {
        UpdateView view = new UpdateView(texts, VERSION, SOURCE, new UpdateView.Actions() {
            @Override
            public void onCheck(@NotNull String source) {
            }

            @Override
            public void onUpdate() {
            }
        });

        return new OptionsScreen(texts, null, false, view, NODES, null, new LauncherNodeRunner(InstallLayout.of(directory)), actions);
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

    @ParameterizedTest
    @CsvSource({"1.0, en", "2.0, en", "1.0, ko", "2.0, ko"})
    void settings_screen_shows_two_columns_of_equal_width(double scale, @NotNull String languageCode, @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        LauncherTexts texts = LauncherTexts.of(language);
        String suffix = Math.round(scale * PERCENT_SCALE) + "-" + language.getCode();

        LauncherFxScenario.run(() -> {
            GridPane root = createScreen(texts, directory, new PingActions()).getRoot();

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), language);
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

            // The launcher panel on the left: language, sound and update, one field per row.
            Assertions.assertEquals(List.of(texts.get("settings.launcher")), listPanelTitles(left));
            Assertions.assertEquals(3, fields.size());

            for (int index = 1; index < fields.size(); index++) {
                Bounds above = getSceneBounds(fields.get(index - 1));
                Bounds below = getSceneBounds(fields.get(index));

                Assertions.assertEquals(above.getMinX(), below.getMinX(), TOLERANCE, "the fields start in one line");
                Assertions.assertTrue(above.getMaxY() <= below.getMinY(), "field " + index + " is under the one before it");
            }

            // The Beacon.Link panel on the right, and the peer list under it once shown.
            Assertions.assertEquals(List.of(texts.get("beaconlink.title")), listPanelTitles(right));
            LauncherFxScenario.saveSample(root, scale, "settings-" + suffix);
            LauncherFxScenario.findButton(root, texts.get("beaconlink.advanced.show")).fire();
            root.applyCss();
            root.layout();

            Assertions.assertEquals(List.of(texts.get("beaconlink.title"), texts.get("nodes.title")), listPanelTitles(right));
            Assertions.assertTrue(getSceneBounds(right.getChildrenUnmodifiable().get(0)).getMaxY() <=
                                  getSceneBounds(right.getChildrenUnmodifiable().get(1)).getMinY(), "the peer list is under the Beacon.Link panel");
            LauncherFxScenario.saveSample(root, scale, "settings-peers-" + suffix);

            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko"})
    void ping_should_mark_every_listed_peer_until_the_results_come(@NotNull String languageCode, @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        LauncherTexts texts = LauncherTexts.of(language);
        PingActions actions = new PingActions();

        LauncherFxScenario.run(() -> {
            GridPane root = createScreen(texts, directory, actions).getRoot();

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), language);
            root.applyCss();
            root.layout();
            LauncherFxScenario.findButton(root, texts.get("beaconlink.advanced.show")).fire();
            LauncherFxScenario.findButton(root, texts.get("nodes.ping")).fire();
            root.applyCss();
            root.layout();

            Assertions.assertEquals(NODES, actions.pinged);
            Assertions.assertEquals(NODES.size(), countShownLabels(root, texts.get("nodes.ping.running")), "every row is being pinged");
            LauncherFxScenario.saveSample(root, 1, "settings-nodes-" + language.getCode());

            return null;
        });
    }
}
