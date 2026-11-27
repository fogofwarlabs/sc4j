package org.fogofwarlabs.sc4j.launcher.ui.mods;

import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.form.FormField;
import org.fogofwarlabs.sc4j.launcher.form.SettingsForm;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.ComponentUtils;
import org.fogofwarlabs.sc4j.launcher.ui.Spacing;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValueStore;
import org.fogofwarlabs.sc4j.manifest.settings.value.SettingValuesException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Shows the Mods screen: the mod list on the left and the selected mod's details on the right. The settings form of each
 * mod is created once from its saved values and kept while the screen lives, so edits survive switching between mods. Every change of a
 * form, invalid values and a reset to the defaults included, is saved at once to the mod's settings value file and the shared settings
 * value file ({@link SettingValueStore#of}); a failed save is logged and the form keeps the values. The field of a shared setting is created
 * once per shared name and used by the forms of every mod that declares it. The details show the newer release of a mod when the launcher
 * has found one ({@link #setUpdates}).
 */
public final class ModsScreen {
    private static final @NotNull Logger LOGGER = Logger.of(ModsScreen.class);

    /**
     * Receives the actions of the Mods screen.
     */
    public interface Actions extends ModDetailView.Actions {
        /**
         * Called when the user selects a mod.
         *
         * @param entry the mod
         */
        void onSelect(@NotNull ModEntry entry);

        /**
         * Called when the user opens the mods folder.
         */
        void onOpenModsFolder();

        /**
         * Called when the user asks to scan the mods folder again.
         */
        void onRefresh();

        /**
         * Called when the user installs a mod.
         */
        void onInstall();
    }


    private final @NotNull InstallLayout install;
    private final @NotNull LauncherTexts texts;
    private final @NotNull ObservableBooleanValue running;
    private final @NotNull Actions actions;
    private final @NotNull ModArt art;
    private final @NotNull Map<@NotNull String, @NotNull SettingsForm> forms;
    private final @NotNull Map<@NotNull String, @NotNull FormField> sharedFields;
    private final @NotNull ModListPanel listPanel;
    private final @NotNull StackPane detailArea;
    private final @NotNull HBox root;

    private @NotNull Map<@NotNull String, @NotNull String> updates;
    private @Nullable ModEntry shown;

    /**
     * Creates a {@link ModsScreen} instance.
     *
     * @param install    the install, for the settings value files
     * @param catalog    the scanned mods
     * @param texts      the launcher texts of the display language
     * @param imageScale the screen pixels per logical unit the images are decoded for
     * @param running    {@code true} while a run is in progress, which keeps Run disabled
     * @param actions    receives the actions
     */
    public ModsScreen(@NotNull InstallLayout install, @NotNull ModCatalog catalog, @NotNull LauncherTexts texts, double imageScale,
                      @NotNull ObservableBooleanValue running, @NotNull Actions actions) {
        VBox prompt = ComponentUtils.createPanel(ComponentUtils.createPanelBody(ComponentUtils.createParagraph(texts.get("mods.select"), "text-caption")));

        this.install      = install;
        this.texts        = texts;
        this.running      = running;
        this.actions      = actions;
        this.art          = new ModArt(imageScale);
        this.forms        = new HashMap<>();
        this.sharedFields = new HashMap<>();
        this.listPanel    = new ModListPanel(catalog, texts, this.art, this::showDetail, actions::onOpenModsFolder, actions::onRefresh,
                                             actions::onInstall);
        this.detailArea   = new StackPane(prompt);
        this.root         = ComponentUtils.createRow(Spacing.L, this.listPanel.getRoot(), this.detailArea);

        this.updates = Map.of();
        this.shown   = null;

        this.detailArea.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(this.detailArea, Priority.ALWAYS);
        this.root.setAlignment(Pos.TOP_LEFT);
    }

    /**
     * @return the screen's root node
     */
    public @NotNull HBox getRoot() {
        return this.root;
    }

    /**
     * Selects a mod of the list and shows its details.
     *
     * @param entry the mod, an entry of the shown catalog
     */
    public void select(@NotNull ModEntry entry) {
        this.listPanel.select(entry);
    }

    /**
     * Sets the newer releases of the listed mods and shows the selected mod's details again with them.
     *
     * @param updates the tag of the newer release of each such mod by its jar file name
     */
    public void setUpdates(@NotNull Map<@NotNull String, @NotNull String> updates) {
        ModEntry selected = this.shown;

        this.updates = Map.copyOf(updates);

        if (selected != null) {
            this.showDetail(selected);
        }
    }

    /**
     * Returns the settings form of a mod, created from its saved values on first use.
     *
     * @param entry the mod
     * @return the form, or {@code null} for a mod without a valid manifest
     */
    public @Nullable SettingsForm getForm(@NotNull ModEntry entry) {
        ModManifest manifest = entry.getManifest();

        if (manifest == null) {
            return null;
        }

        return this.forms.computeIfAbsent(entry.getJarName(), jarName -> SettingsForm.create(manifest.getSettings(), this.readSavedValues(manifest),
                                                                                             this.sharedFields));
    }

    private @NotNull Map<@NotNull String, ?> readSavedValues(@NotNull ModManifest manifest) {
        try {
            return SettingValueStore.of(this.install, manifest).read();
        } catch (IOException | SettingValuesException exception) {
            // The catalog lists the unreadable file as a settings issue; the form starts from the defaults.
            return Map.of();
        }
    }

    /**
     * Saves the values of a mod's settings form, as the user entered them.
     */
    private void saveForm(@NotNull ModEntry entry) {
        ModManifest manifest = entry.getManifest();
        SettingsForm form = this.forms.get(entry.getJarName());

        if (manifest == null || form == null) {
            return;
        }

        try {
            form.save(SettingValueStore.of(this.install, manifest));
        } catch (IOException | SettingValuesException exception) {
            LOGGER.warning("The settings of {0} cannot be saved: {1}", manifest.getId(), exception.getMessage());
        }
    }

    private void showDetail(@NotNull ModEntry entry) {
        SettingsForm form = this.getForm(entry);
        ModDetailView view = new ModDetailView(entry, this.updates.get(entry.getJarName()), form, this.texts, this.art, this.running,
                                               () -> this.saveForm(entry), this.actions);

        this.shown = entry;
        this.detailArea.getChildren().setAll(view.getRoot());
        this.actions.onSelect(entry);
    }
}
