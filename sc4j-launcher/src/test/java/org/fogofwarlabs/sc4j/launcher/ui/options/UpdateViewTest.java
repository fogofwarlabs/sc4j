package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.nodes.NodePingResult;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNodeRunner;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.launcher.ui.Tone;
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
 * Tests the update part of the Settings screen offscreen: Check now passes the typed source and waits for its result, Update is shown only
 * while a newer version is available, and the version and the check result are centred on the source field.
 */
final class UpdateViewTest {
    private static final @NotNull String VERSION = "1.1.0";
    private static final @NotNull String SOURCE = "owner/sc4j";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final double CENTER_TOLERANCE = 1;

    /**
     * Receives no change; the test only lays the screen out.
     */
    private static final class NoActions implements OptionsScreen.Actions {
        @Override
        public void onLanguageChanged(@Nullable UiLanguage language) {
        }

        @Override
        public @Nullable String onNodesChanged(@NotNull List<@NotNull ConfiguredNode> nodes) {
            return null;
        }

        @Override
        public void onPingNodes(@NotNull List<@NotNull ConfiguredNode> nodes, @NotNull Consumer<@NotNull List<@NotNull NodePingResult>> results) {
        }
    }

    private static @NotNull Bounds getSceneBounds(@NotNull Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    private static @NotNull Label findLabel(@NotNull Node root, @NotNull String text) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && text.equals(label.getText())) {
                return label;
            }
        }

        throw new AssertionError("No label " + text);
    }

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko"})
    void check_should_wait_for_its_result_and_update_should_show_only_when_available(@NotNull String languageCode,
                                                                                     @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        LauncherTexts texts = LauncherTexts.of(language);
        List<String> checked = new ArrayList<>();

        LauncherFxScenario.run(() -> {
            UpdateView view = new UpdateView(texts, VERSION, SOURCE, new UpdateView.Actions() {
                @Override
                public void onCheck(@NotNull String source) {
                    checked.add(source);
                }

                @Override
                public void onUpdate() {
                }
            });
            OptionsScreen screen = new OptionsScreen(texts, null, view, List.of(), null, new LauncherNodeRunner(InstallLayout.of(directory)),
                                                     new NoActions());
            VBox root = screen.getRoot();
            Button check = LauncherFxScenario.findButton(view.getRoot(), texts.get("update.check"));
            Button update;

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), language);
            check.fire();
            view.showChecking();

            Assertions.assertEquals(List.of(SOURCE), checked);
            Assertions.assertTrue(check.isDisabled());

            view.showStatus(texts.format("update.available", "1.2.0"), Tone.ACCENT, true);
            update = LauncherFxScenario.findButton(view.getRoot(), texts.get("update.start"));

            Assertions.assertFalse(check.isDisabled());
            Assertions.assertTrue(LauncherFxScenario.isShown(update));
            root.applyCss();
            root.layout();
            LauncherFxScenario.saveSample(root, 1, "settings-update-" + language.getCode());

            view.showStatus(texts.get("update.current"), Tone.SUCCESS, false);

            Assertions.assertFalse(LauncherFxScenario.isShown(update));

            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko"})
    void version_and_result_should_center_on_the_source_field(@NotNull String languageCode, @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        LauncherTexts texts = LauncherTexts.of(language);

        LauncherFxScenario.run(() -> {
            UpdateView view = new UpdateView(texts, VERSION, SOURCE, new UpdateView.Actions() {
                @Override
                public void onCheck(@NotNull String source) {
                }

                @Override
                public void onUpdate() {
                }
            });
            OptionsScreen screen = new OptionsScreen(texts, null, view, List.of(), null, new LauncherNodeRunner(InstallLayout.of(directory)),
                                                     new NoActions());
            VBox root = screen.getRoot();

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), language);
            view.showStatus(texts.get("update.current"), Tone.SUCCESS, false);
            root.applyCss();
            root.layout();

            Bounds source = getSceneBounds(view.getRoot().lookup(".text-field"));
            Bounds check = getSceneBounds(LauncherFxScenario.findButton(view.getRoot(), texts.get("update.check")));
            Bounds version = getSceneBounds(findLabel(view.getRoot(), texts.format("update.version", VERSION)));
            Bounds result = getSceneBounds(findLabel(view.getRoot(), texts.get("update.current")));
            double stateCenter = (version.getMinY() + result.getMaxY()) / 2;

            LauncherFxScenario.saveSample(root, 1, "settings-update-current-" + language.getCode());
            Assertions.assertInstanceOf(TextField.class, view.getRoot().lookup(".text-field"));
            Assertions.assertEquals(source.getCenterY(), check.getCenterY(), CENTER_TOLERANCE, "Check now is centred on the source field");
            Assertions.assertEquals(source.getCenterY(), stateCenter, CENTER_TOLERANCE, "the version and the result are centred on the source field");
            Assertions.assertEquals(getSceneBounds(root.lookup(".combo-box")).getMinY(), source.getMinY(), CENTER_TOLERANCE,
                                    "the source field lines up with the language box");
            Assertions.assertEquals(getSceneBounds(findLabel(root, texts.get("settings.language"))).getMinY(),
                                    getSceneBounds(findLabel(view.getRoot(), texts.get("update.source"))).getMinY(), CENTER_TOLERANCE,
                                    "the source label lines up with the language label");

            return null;
        });
    }
}
