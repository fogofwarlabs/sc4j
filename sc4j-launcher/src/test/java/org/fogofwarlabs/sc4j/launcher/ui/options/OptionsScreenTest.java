package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tests the peer list of the Settings screen offscreen: Peers shows the list, and Ping asks for the listed peers and marks every row as being
 * pinged until the results come.
 */
final class OptionsScreenTest {
    private static final @NotNull String VERSION = "1.1.0";
    private static final @NotNull String SOURCE = "owner/sc4j";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
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

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko"})
    void ping_should_mark_every_listed_peer_until_the_results_come(@NotNull String languageCode, @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        LauncherTexts texts = LauncherTexts.of(language);
        PingActions actions = new PingActions();

        LauncherFxScenario.run(() -> {
            UpdateView view = new UpdateView(texts, VERSION, SOURCE, new UpdateView.Actions() {
                @Override
                public void onCheck(@NotNull String source) {
                }

                @Override
                public void onUpdate() {
                }
            });
            OptionsScreen screen = new OptionsScreen(texts, null, view, NODES, null, new LauncherNodeRunner(InstallLayout.of(directory)), actions);
            VBox root = screen.getRoot();

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
