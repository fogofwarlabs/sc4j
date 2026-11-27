package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tests the update parts of the Settings screen offscreen: Check now sits in the launcher panel's header, passes the typed source and waits
 * for its result; the Version field shows the running version and the result after it in one line, Update after them only while a newer
 * version is available, without changing the field's height; the update source field lines up with the language box.
 */
final class UpdateViewTest {
    private static final @NotNull String VERSION = "1.1.0";
    private static final @NotNull String SOURCE = "owner/sc4j";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final @NotNull String NEWER_VERSION = "1.2.0";
    private static final @NotNull String PROBLEM = "timeout";
    private static final double CENTER_TOLERANCE = 1;

    /**
     * Receives no change; the test only lays the screen out.
     */
    private static final class NoActions implements OptionsScreen.Actions {
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
        }

        @Override
        public void onTranslationPresetChanged(@Nullable String presetId) {
        }

        @Override
        public void onOpenWebsite(@NotNull String address) {
        }
    }

    private static @NotNull Bounds getSceneBounds(@NotNull Node node) {
        return node.localToScene(node.getLayoutBounds());
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

    private static @NotNull UpdateView createView(@NotNull LauncherTexts texts, @NotNull List<@NotNull String> checked) {
        return new UpdateView(texts, VERSION, SOURCE, new UpdateView.Actions() {
            @Override
            public void onCheck(@NotNull String source) {
                checked.add(source);
            }

            @Override
            public void onUpdate() {
            }
        });
    }

    private static @NotNull GridPane createRoot(@NotNull LauncherTexts texts, @NotNull UpdateView view, @NotNull Path directory) {
        InstallLayout install = InstallLayout.of(directory);
        LauncherShell shell = new LauncherShell(texts, install, 1, section -> {
        }, () -> {
        }, () -> {
        });
        LauncherTranslation translation = new LauncherTranslation(TranslationStorage.of(install.getUserDataDirectory()),
                                                                  TranslationPresetUtils.getPresets());

        return new OptionsScreen(shell, texts, null, false, view, List.of(), null, new LauncherNodeRunner(install), translation, new NoActions())
                .getRoot();
    }

    private static void layOut(@NotNull GridPane root) {
        root.applyCss();
        root.layout();
    }

    @Test
    void check_should_wait_for_its_result_and_update_should_show_only_when_available(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        List<String> checked = new ArrayList<>();

        LauncherFxScenario.run(() -> {
            UpdateView view = createView(texts, checked);
            GridPane root = createRoot(texts, view, directory);
            Button check = view.getCheckButton();
            String failed = texts.format("update.failed", PROBLEM);
            Button update;

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), UiLanguage.ENGLISH);
            layOut(root);

            Assertions.assertEquals(texts.get("update.check"), check.getText());
            Assertions.assertTrue(check.getStyleClass().contains("small"), "Check now is a small header button");
            Assertions.assertTrue(check.getParent().getStyleClass().contains("panel-header"), "Check now is in the panel header");
            Assertions.assertEquals(texts.get("settings.launcher"), ((Label) check.getParent().lookup(".panel-title")).getText());

            check.fire();
            view.showChecking();

            Assertions.assertEquals(List.of(SOURCE), checked);
            Assertions.assertTrue(check.isDisabled());
            Assertions.assertTrue(LauncherFxScenario.isShown(findLabel(root, texts.format("update.status", texts.get("update.checking")))));

            view.showStatus(texts.format("update.available", NEWER_VERSION), Tone.ACCENT, true);
            update = LauncherFxScenario.findButton(view.getVersionField(), texts.get("update.start"));

            Assertions.assertFalse(check.isDisabled());
            Assertions.assertTrue(LauncherFxScenario.isShown(update));

            view.showStatus(texts.get("update.current"), Tone.SUCCESS, false);

            Assertions.assertFalse(LauncherFxScenario.isShown(update));
            Assertions.assertTrue(findLabel(root, texts.format("update.status", texts.get("update.current"))).getStyleClass()
                                                                                                             .contains("text-success"));

            view.showStatus(failed, Tone.DANGER, false);

            Assertions.assertFalse(LauncherFxScenario.isShown(update));
            Assertions.assertTrue(findLabel(root, texts.format("update.status", failed)).getStyleClass().contains("text-danger"));

            return null;
        });
    }

    @Test
    void version_field_should_show_the_result_after_the_version(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);

        LauncherFxScenario.run(() -> {
            UpdateView view = createView(texts, new ArrayList<>());
            GridPane root = createRoot(texts, view, directory);
            String available = texts.format("update.status", texts.format("update.available", NEWER_VERSION));
            Bounds version;
            Bounds result;
            Bounds update;
            Bounds languageBox;
            Bounds source;
            double fieldHeight;

            ThemeUtils.apply(new Scene(root, SCENE_WIDTH, SCENE_HEIGHT), UiLanguage.ENGLISH);
            view.showStatus(texts.get("update.current"), Tone.SUCCESS, false);
            layOut(root);

            version     = getSceneBounds(findLabel(view.getVersionField(), VERSION));
            result      = getSceneBounds(findLabel(view.getVersionField(), texts.format("update.status", texts.get("update.current"))));
            fieldHeight = view.getVersionField().getHeight();

            Assertions.assertEquals(texts.get("update.version"), ((Label) view.getVersionField().getChildrenUnmodifiable().getFirst()).getText());
            Assertions.assertEquals(version.getCenterY(), result.getCenterY(), CENTER_TOLERANCE, "the result is in the version's line");
            Assertions.assertTrue(version.getMaxX() < result.getMinX(), "the result follows the version");

            view.showStatus(texts.format("update.available", NEWER_VERSION), Tone.ACCENT, true);
            layOut(root);
            result = getSceneBounds(findLabel(view.getVersionField(), available));
            update = getSceneBounds(LauncherFxScenario.findButton(view.getVersionField(), texts.get("update.start")));

            Assertions.assertEquals(result.getCenterY(), update.getCenterY(), CENTER_TOLERANCE, "Update is in the version's line");
            Assertions.assertTrue(result.getMaxX() < update.getMinX(), "Update follows the result");
            Assertions.assertEquals(fieldHeight, view.getVersionField().getHeight(), CENTER_TOLERANCE, "Update keeps the field's height");

            languageBox = getSceneBounds(root.lookup(".combo-box"));
            source      = getSceneBounds(view.getSourceField().lookup(".text-field"));

            Assertions.assertInstanceOf(TextField.class, view.getSourceField().lookup(".text-field"));
            Assertions.assertEquals(languageBox.getMinX(), source.getMinX(), CENTER_TOLERANCE, "the source field lines up with the language box");
            Assertions.assertTrue(languageBox.getMaxY() < source.getMinY(), "the source field is below the language box");
            Assertions.assertEquals(getSceneBounds(findLabel(root, texts.get("settings.language"))).getMinX(),
                                    getSceneBounds(findLabel(view.getSourceField(), texts.get("update.source"))).getMinX(), CENTER_TOLERANCE,
                                    "the source label lines up with the language label");

            return null;
        });
    }
}
