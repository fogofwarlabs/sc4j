package org.fogofwarlabs.sc4j.launcher.ui.mods;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.mods.ModIndex;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.install.ModInstaller;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherShell;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tests the dialogs that change {@code mods/} offscreen, against a local stand-in for GitHub: Install mod with the catalog choice and the
 * confirmation, a refused jar, and Remove.
 */
final class ModReleaseDialogsTest {
    private static final @NotNull String MANIFEST = """
                                                    {
                                                      "format": 1,
                                                      "id": "my-mod",
                                                      "name": {"en": "My Mod"},
                                                      "version": "1.0.0",
                                                      "engine": "%s",
                                                      "entry": "org.example.MyMod"
                                                    }
                                                    """;
    private static final @NotNull String CATALOG = """
                                                   {
                                                     "format": 1,
                                                     "mods": [{"name": {"en": "My Mod", "ko": "내 모드"}, "repository": "owner/my-mod"}]
                                                   }
                                                   """;
    private static final @NotNull String REPOSITORY = "owner/my-mod";
    private static final @NotNull String JAR = "my-mod.jar";
    private static final @NotNull String TAG = "v1.0.0";
    private static final @NotNull String UNSUPPORTED_ENGINE = "99.0";
    private static final @NotNull String MESSAGE_SELECTOR = ".field-message";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final double LIST_WIDTH = 380;
    private static final double LIST_HEIGHT = 120;
    private static final @NotNull String POPUP_STYLE_CLASS = "combo-box-popup";

    /**
     * Holds the shell and the dialogs of one test.
     */
    private static final class Dialogs {
        private final @NotNull LauncherShell shell;
        private final @NotNull ModReleaseDialogs dialogs;

        /**
         * Creates a {@link Dialogs} instance.
         *
         * @param shell   the shell the dialogs show in
         * @param dialogs the dialogs
         */
        private Dialogs(@NotNull LauncherShell shell, @NotNull ModReleaseDialogs dialogs) {
            this.shell   = shell;
            this.dialogs = dialogs;
        }
    }

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko"})
    void install_should_list_the_catalog_confirm_the_release_and_add_the_jar(@NotNull String languageCode, @TempDir @NotNull Path directory) throws Exception {
        UiLanguage language = languageCode.equals(UiLanguage.KOREAN.getCode()) ? UiLanguage.KOREAN : UiLanguage.ENGLISH;
        LauncherTexts texts = LauncherTexts.of(language);
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        List<String> changed = new CopyOnWriteArrayList<>();

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            server.addCheckedRelease(REPOSITORY, TAG, Map.of(JAR, createJar(directory, EngineVersion.CURRENT.toString())));

            Dialogs shown = open(server, texts, language, install, changed);

            LauncherFxScenario.run(() -> {
                shown.dialogs.openInstall();

                return null;
            });
            LauncherFxScenario.await(() -> findRepositoryChoice(shown.shell).getItems().equals(List.of(REPOSITORY)), "the catalog is listed");
            LauncherFxScenario.run(() -> {
                StackPane list = createCatalogList(findRepositoryChoice(shown.shell), language);

                LauncherFxScenario.saveSample(list, 1, "mod-install-list-" + language.getCode());
                Assertions.assertTrue(hasLabel(list, language == UiLanguage.KOREAN ? "내 모드" : "My Mod"));
                findRepositoryChoice(shown.shell).getEditor().setText(REPOSITORY);
                LauncherFxScenario.saveSample(shown.shell.getRoot(), 1, "mod-install-" + language.getCode());
                LauncherFxScenario.findButton(shown.shell.getRoot(), texts.get("mods.install.next")).fire();

                return null;
            });
            LauncherFxScenario.await(() -> hasButton(shown.shell.getRoot(), texts.get("mods.install.start")), "the confirmation opens");
            LauncherFxScenario.run(() -> {
                shown.shell.getRoot().applyCss();
                shown.shell.getRoot().layout();
                LauncherFxScenario.saveSample(shown.shell.getRoot(), 1, "mod-install-confirm-" + language.getCode());
                LauncherFxScenario.findButton(shown.shell.getRoot(), texts.get("mods.install.start")).fire();

                return null;
            });
            LauncherFxScenario.await(() -> !changed.isEmpty(), "the mod is installed");

            Assertions.assertEquals(List.of(JAR), changed);
            Assertions.assertTrue(Files.isRegularFile(install.getModsDirectory().resolve(JAR)));
        }
    }

    @Test
    void install_should_show_why_a_jar_was_refused(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        List<String> changed = new CopyOnWriteArrayList<>();

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            server.addCheckedRelease(REPOSITORY, TAG, Map.of(JAR, createJar(directory, UNSUPPORTED_ENGINE)));

            Dialogs shown = open(server, texts, UiLanguage.ENGLISH, install, changed);
            String refused = texts.format("mods.install.refused", "");

            LauncherFxScenario.run(() -> {
                shown.dialogs.openInstall();
                findRepositoryChoice(shown.shell).getEditor().setText("https://github.com/" + REPOSITORY);
                LauncherFxScenario.findButton(shown.shell.getRoot(), texts.get("mods.install.next")).fire();

                return null;
            });
            LauncherFxScenario.await(() -> hasButton(shown.shell.getRoot(), texts.get("mods.install.start")), "the confirmation opens");
            LauncherFxScenario.run(() -> {
                LauncherFxScenario.findButton(shown.shell.getRoot(), texts.get("mods.install.start")).fire();

                return null;
            });
            LauncherFxScenario.await(() -> findMessage(shown.shell.getRoot()).startsWith(refused.strip()), "the refusal is shown");
            LauncherFxScenario.run(() -> {
                shown.shell.getRoot().applyCss();
                shown.shell.getRoot().layout();
                LauncherFxScenario.saveSample(shown.shell.getRoot(), 1, "mod-install-refused-en");

                return null;
            });

            Assertions.assertEquals(List.of(), changed);
            Assertions.assertFalse(Files.exists(install.getModsDirectory().resolve(JAR)));
        }
    }

    @Test
    void remove_should_delete_the_jar_after_the_confirmation(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        List<String> changed = new CopyOnWriteArrayList<>();

        Files.createDirectories(install.getModsDirectory());
        Files.write(install.getModsDirectory().resolve(JAR), createJar(directory, EngineVersion.CURRENT.toString()));

        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            Dialogs shown = open(server, texts, UiLanguage.ENGLISH, install, changed);
            ModEntry entry = ModCatalog.scan(install, InstallationRecords.NONE).findByJarName(JAR);

            Assertions.assertNotNull(entry);

            LauncherFxScenario.run(() -> {
                shown.dialogs.openRemove(entry);
                shown.shell.getRoot().applyCss();
                shown.shell.getRoot().layout();
                LauncherFxScenario.saveSample(shown.shell.getRoot(), 1, "mod-remove-en");
                LauncherFxScenario.findButton(shown.shell.getRoot(), texts.get("mods.remove")).fire();

                return null;
            });

            Assertions.assertEquals(List.of(JAR), changed);
            Assertions.assertFalse(Files.exists(install.getModsDirectory().resolve(JAR)));
        }
    }

    private static @NotNull Dialogs open(@NotNull ReleaseServerScenario server, @NotNull LauncherTexts texts, @NotNull UiLanguage language,
                                         @NotNull InstallLayout install, @NotNull List<@NotNull String> changed) throws Exception {
        ReleaseClient client = new ReleaseClient(server.getApi());
        ModIndex index = new ModIndex(client, server.addFile("mods.json", CATALOG.getBytes(StandardCharsets.UTF_8)));
        ModInstaller installer = new ModInstaller(install, client);

        return LauncherFxScenario.run(() -> {
            LauncherShell shell = new LauncherShell(texts, install, 1, section -> {
            }, () -> {
            }, () -> {
            });
            Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);
            ModReleaseDialogs dialogs = new ModReleaseDialogs(shell, texts, installer, index, () -> scanCatalog(install), changed::add);

            ThemeUtils.apply(scene, language);

            return new Dialogs(shell, dialogs);
        });
    }

    private static @NotNull ModCatalog scanCatalog(@NotNull InstallLayout install) {
        try {
            return ModCatalog.scan(install, InstallationRecords.NONE);
        } catch (IOException exception) {
            throw Exceptions.newAssertionError(exception, "The mods folder of {0} cannot be listed.", install.getRoot());
        }
    }

    @SuppressWarnings("unchecked")
    private static @NotNull ComboBox<String> findRepositoryChoice(@NotNull LauncherShell shell) {
        return Assertions.assertInstanceOf(ComboBox.class, shell.getRoot().lookup(".combo-box"));
    }

    private static @NotNull StackPane createCatalogList(@NotNull ComboBox<String> choice, @NotNull UiLanguage language) {
        ListView<String> list = new ListView<>(choice.getItems());
        StackPane popup = new StackPane(list);
        Scene scene = new Scene(popup, LIST_WIDTH, LIST_HEIGHT);

        list.setCellFactory(choice.getCellFactory());
        popup.getStyleClass().add(POPUP_STYLE_CLASS);
        ThemeUtils.apply(scene, language);
        popup.applyCss();
        popup.layout();

        return popup;
    }

    private static boolean hasLabel(@NotNull Node root, @NotNull String text) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && text.equals(label.getText())) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasButton(@NotNull Node root, @NotNull String text) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.getText().equals(text) && LauncherFxScenario.isShown(button)) {
                return true;
            }
        }

        return false;
    }

    private static @NotNull String findMessage(@NotNull Node root) {
        for (Node node : root.lookupAll(MESSAGE_SELECTOR)) {
            if (node instanceof Label label && LauncherFxScenario.isShown(label)) {
                return label.getText();
            }
        }

        return "";
    }

    private static byte @NotNull [] createJar(@NotNull Path directory, @NotNull String engine) throws IOException {
        Path jar = Files.createTempFile(directory, "my-mod", ".jar");

        TestJarUtils.write(jar, Map.of(TestJarUtils.MANIFEST_ENTRY, MANIFEST.formatted(engine).getBytes(StandardCharsets.UTF_8)));

        return Files.readAllBytes(jar);
    }
}
