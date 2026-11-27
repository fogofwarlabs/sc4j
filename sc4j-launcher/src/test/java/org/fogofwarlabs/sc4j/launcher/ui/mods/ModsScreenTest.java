package org.fogofwarlabs.sc4j.launcher.ui.mods;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.form.FormField;
import org.fogofwarlabs.sc4j.launcher.form.SettingsForm;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Tests that the Mods screen saves a mod's settings form at every change: a changed value, an invalid one included, and a reset to the
 * defaults survive creating the form again from the settings value files.
 */
final class ModsScreenTest {
    private static final @NotNull String JAR_NAME = "example.jar";
    private static final @NotNull String MANIFEST = """
                                                    {
                                                      "format": 1,
                                                      "id": "example",
                                                      "name": {"en": "Example"},
                                                      "version": "1.0.0",
                                                      "engine": "0.1",
                                                      "entry": "org.example.ExampleMod",
                                                      "settings": [
                                                        {"key": "windowed", "type": "boolean", "label": {"en": "Start in a window"}},
                                                        {"key": "player", "type": "string", "pattern": "[A-Za-z]+", "default": "Raynor", "label": {"en": "Player name"}}
                                                      ]
                                                    }
                                                    """;
    private static final @NotNull String INVALID_PLAYER = "Jim Raynor";
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;

    /**
     * Receives no action; the test only changes the settings form.
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
        public void onInstall() {
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
    }

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @NotNull ModsScreen createScreen(@NotNull InstallLayout install, @NotNull ModCatalog catalog) {
        return new ModsScreen(install, catalog, LauncherTexts.of(UiLanguage.ENGLISH), 1, new SimpleBooleanProperty(false), new NoActions());
    }

    /**
     * Creates the form of the mod again from the settings value files and returns the value of a setting.
     */
    private static @Nullable Object readSavedValue(@NotNull InstallLayout install, @NotNull ModCatalog catalog, @NotNull ModEntry entry,
                                                   @NotNull String key) {
        SettingsForm form = createScreen(install, catalog).getForm(entry);

        Assertions.assertNotNull(form);

        FormField field = form.findField(key);

        Assertions.assertNotNull(field);

        return field.getValue();
    }

    @Test
    void changed_settings_are_saved_at_once_and_read_by_a_new_form(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        InstallLayout install = InstallLayout.of(directory);

        Files.createDirectories(install.getModsDirectory());
        TestJarUtils.write(install.getModsDirectory().resolve(JAR_NAME), Map.of(TestJarUtils.MANIFEST_ENTRY, MANIFEST.getBytes(StandardCharsets.UTF_8)));

        ModCatalog catalog = ModCatalog.scan(install, InstallationRecords.NONE);
        ModEntry entry = catalog.findByJarName(JAR_NAME);

        Assertions.assertNotNull(entry);

        LauncherFxScenario.run(() -> {
            ModsScreen screen = createScreen(install, catalog);
            HBox root = screen.getRoot();
            Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);

            screen.select(entry);
            ThemeUtils.apply(scene, UiLanguage.ENGLISH);
            root.applyCss();
            root.layout();

            CheckBox windowed = (CheckBox) root.lookup(".check-box");
            TextField player = (TextField) root.lookup(".text-field");

            Assertions.assertNotNull(windowed);
            Assertions.assertNotNull(player);

            windowed.fire();
            player.setText(INVALID_PLAYER);

            Assertions.assertEquals(Boolean.TRUE, readSavedValue(install, catalog, entry, "windowed"));
            Assertions.assertEquals(INVALID_PLAYER, readSavedValue(install, catalog, entry, "player"), "an invalid value is saved as entered");

            LauncherFxScenario.findButton(root, texts.get("detail.reset")).fire();

            Assertions.assertEquals(Boolean.FALSE, readSavedValue(install, catalog, entry, "windowed"), "a reset to the defaults is saved");
            Assertions.assertEquals("Raynor", readSavedValue(install, catalog, entry, "player"));

            return null;
        });
    }
}
