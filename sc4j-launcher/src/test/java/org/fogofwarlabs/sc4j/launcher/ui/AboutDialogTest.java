package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.ModManifestReader;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests the About dialog: SC4J's own notice and the Beacon.Link privacy statement, then the notices of the installed mods under their names in
 * the display language (Korean, and English when a notice has no text in it, as in Chinese), a mod without notices left out and a mod id
 * shown once; Close closes it.
 */
final class AboutDialogTest {
    private static final @NotNull String VERSION = "1.0.0";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final @NotNull String DIALOG_SELECTOR = ".modal-scrim";
    private static final @NotNull String NOTICE_MOD = """
                                                      {"format": 1, "id": "example-game", "name": {"en": "Example Game", "ko": "예제 게임"}, "version": "1.0.0", "engine": "0.1",
                                                       "entry": "org.example.ExampleMod",
                                                       "notices": [{"en": "Example Game is a trademark of Example Company.", "ko": "Example Game은 Example Company의 상표입니다."},
                                                                   {"en": "Art: Example Artist, CC BY 4.0."}]}
                                                      """;
    private static final @NotNull String PLAIN_MOD = """
                                                     {"format": 1, "id": "plain-mod", "name": {"en": "Plain Mod"}, "version": "1.0.0", "engine": "0.1", "entry": "org.example.PlainMod"}
                                                     """;

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @NotNull ModManifest parse(@NotNull String manifest) throws ManifestException {
        return ModManifestReader.parse(manifest.getBytes(StandardCharsets.UTF_8));
    }

    private static @NotNull LauncherShell openDialog(@NotNull LauncherTexts texts, @NotNull List<@NotNull ModManifest> mods,
                                                     @NotNull Path directory) {
        LauncherShell shell = new LauncherShell(texts, InstallLayout.of(directory), 1, section -> {
        }, () -> {
        }, () -> {
        });

        ThemeUtils.apply(new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT), texts.getLanguage());
        new AboutDialog(shell, texts, VERSION, 1, mods, address -> {
        }).open();
        shell.getRoot().applyCss();
        shell.getRoot().layout();

        return shell;
    }

    private static @NotNull List<@NotNull String> listSc4jTexts(@NotNull LauncherTexts texts) {
        return List.of(texts.get("about.license"), texts.get("about.fonts"), texts.get("about.privacy.title"), texts.get("about.privacy.address"),
                       texts.get("about.privacy.shared"), texts.get("about.privacy.files"));
    }

    private static @NotNull List<@NotNull String> listTextsFrom(@NotNull Node dialog, @NotNull String first) {
        List<String> texts = dialog.lookupAll(".label").stream().filter(node -> node instanceof Label && LauncherFxScenario.isShown(node))
                                   .map(node -> ((Label) node).getText()).toList();

        return texts.subList(texts.indexOf(first), texts.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ko", "zh"})
    void about_dialog_shows_the_sc4j_notice_then_the_notices_of_each_mod_under_its_name(@NotNull String languageCode,
                                                                                        @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = LauncherFxScenario.requireLanguage(languageCode);
        LauncherTexts texts = LauncherTexts.of(language);
        List<ModManifest> mods = List.of(parse(NOTICE_MOD), parse(PLAIN_MOD), parse(NOTICE_MOD));
        boolean korean = language == UiLanguage.KOREAN;
        List<String> modTexts = List.of(korean ? "예제 게임" : "Example Game",
                                        korean ? "Example Game은 Example Company의 상표입니다." : "Example Game is a trademark of Example Company.",
                                        "Art: Example Artist, CC BY 4.0.");

        LauncherFxScenario.run(() -> {
            LauncherShell shell = openDialog(texts, mods, directory);
            Node dialog = shell.getRoot().lookup(DIALOG_SELECTOR);
            List<String> shown = listTextsFrom(dialog, texts.get("about.license"));
            List<String> sc4jTexts = listSc4jTexts(texts);

            Assertions.assertEquals(sc4jTexts, shown.subList(0, sc4jTexts.size()));
            Assertions.assertEquals(modTexts, shown.subList(sc4jTexts.size(), shown.size()));

            LauncherFxScenario.findButton(shell.getRoot(), texts.get("about.close")).fire();
            Assertions.assertNull(shell.getRoot().lookup(DIALOG_SELECTOR), "Close closes the dialog");

            return null;
        });
    }

    @Test
    void about_dialog_without_mod_notices_shows_only_the_sc4j_texts(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        List<ModManifest> mods = List.of(parse(PLAIN_MOD));

        LauncherFxScenario.run(() -> {
            Node dialog = openDialog(texts, mods, directory).getRoot().lookup(DIALOG_SELECTOR);

            Assertions.assertEquals(listSc4jTexts(texts), listTextsFrom(dialog, texts.get("about.license")));
            Assertions.assertEquals(List.of(texts.get("about.privacy.title")),
                                    dialog.lookupAll(".text-heading").stream().map(node -> ((Label) node).getText()).toList(), "no mod heading");

            return null;
        });
    }
}
