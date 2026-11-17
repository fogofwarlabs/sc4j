package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/**
 * Tests the panel header offscreen: a header with only its title, one with a label and one with a button have the same height, which stays
 * while the button changes its text, is pressed, disabled or hidden, and while the panels are squeezed; the controls are centred in it.
 */
final class PanelHeaderTest {
    private static final double SCENE_WIDTH = 600;
    private static final double SCENE_HEIGHT = 800;
    private static final double BODY_CONTENT_HEIGHT = 80;
    private static final double SQUEEZED_HEIGHT = 120;
    private static final double TOLERANCE = 0.5;
    private static final @NotNull String HEADER_SELECTOR = ".panel-header";

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @NotNull VBox createBody() {
        Region content = new Region();

        content.setPrefHeight(BODY_CONTENT_HEIGHT);

        return ComponentUtils.createPanelBody(content);
    }

    private static @NotNull List<@NotNull Double> layOut(@NotNull VBox root) {
        root.applyCss();
        root.layout();

        return root.lookupAll(HEADER_SELECTOR).stream().map(header -> header.getLayoutBounds().getHeight()).toList();
    }

    private static void assertCentred(@NotNull Node header, @NotNull Node control) {
        Bounds bounds = header.sceneToLocal(control.localToScene(control.getLayoutBounds()));
        Region region = (Region) header;
        double contentTop = region.snappedTopInset();
        double contentBottom = region.getHeight() - region.snappedBottomInset();

        Assertions.assertEquals((contentTop + contentBottom) / 2, bounds.getCenterY(), TOLERANCE, "the control is centred in the header");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko"})
    void every_header_should_keep_one_height_whatever_its_controls(@NotNull String languageCode) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;

        LauncherFxScenario.run(() -> {
            Label count = ComponentUtils.createLabel("3 peers", "text-caption");
            Button button = ComponentUtils.createSmallButton("Peers", ButtonRole.SECONDARY);
            VBox titleOnly = ComponentUtils.createPanel("TITLE", createBody());
            VBox withLabel = ComponentUtils.createPanel("LABEL", createBody(), count);
            VBox withButton = ComponentUtils.createPanel("BUTTON", createBody(), button);
            VBox root = new VBox(titleOnly, withLabel, withButton);
            double height;

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), language);

            List<Double> heights = layOut(root);

            height = heights.getFirst();
            Assertions.assertTrue(height > 0);
            Assertions.assertEquals(List.of(height, height, height), heights, "title only, label and button headers have one height");
            assertCentred(withLabel.lookup(HEADER_SELECTOR), count);
            assertCentred(withButton.lookup(HEADER_SELECTOR), button);

            // The button's states and texts do not change the header's height.
            for (int toggle = 0; toggle < 4; toggle++) {
                button.setText(toggle % 2 == 0 ? "Hide peers" : "Peers");
                button.arm();
                button.requestFocus();
                Assertions.assertEquals(List.of(height, height, height), layOut(root), "the header keeps its height on toggle " + toggle);
                button.disarm();
            }

            button.setDisable(true);
            Assertions.assertEquals(List.of(height, height, height), layOut(root), "a disabled button keeps the height");
            button.setVisible(false);
            button.setManaged(false);
            Assertions.assertEquals(List.of(height, height, height), layOut(root), "a hidden button keeps the height");

            // Panels squeezed below their bodies' height keep their headers.
            root.resize(SCENE_WIDTH, SQUEEZED_HEIGHT);
            Assertions.assertEquals(List.of(height, height, height), layOut(root), "squeezed panels keep the header height");

            return null;
        });
    }
}
