package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.translation.LauncherTranslation;
import org.fogofwarlabs.sc4j.launcher.translation.PresetDownload;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.fogofwarlabs.sc4j.translate.service.model.PresetFile;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationPreset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the chat translation panel of the Settings screen: the model the chat is translated with (Off or one of the presets) and, for each
 * preset, its name, download size, licence (a link) and state, with Download, Cancel while it downloads, and Delete once installed. Download
 * first shows the preset's licence and size and starts only when the user confirms; Delete asks first. The states are refreshed twice a
 * second while the panel is shown; a download goes on when the panel is left.
 */
final class TranslationPanel {
    private static final @NotNull Duration REFRESH_INTERVAL = Duration.millis(500);
    private static final @NotNull List<@NotNull String> TONE_STYLES = List.of("text-success", "text-warning", "text-danger", "text-caption");

    /**
     * Holds one entry of the model choice: Off or a preset.
     */
    private static final class PresetOption {
        private final @Nullable TranslationPreset preset;
        private final @NotNull String name;

        /**
         * Creates a {@link PresetOption} instance.
         *
         * @param preset the preset, or {@code null} for Off
         * @param name   the shown name
         */
        private PresetOption(@Nullable TranslationPreset preset, @NotNull String name) {
            this.preset = preset;
            this.name   = name;
        }

        /**
         * {@return a string representation of the object}
         *
         * @implSpec Returns the shown name.
         */
        @Override
        public @NotNull String toString() {
            return this.name;
        }
    }

    /**
     * Shows one preset: its name, size, licence and state, and its buttons.
     */
    private final class PresetRow {
        private final @NotNull TranslationPreset preset;
        private final @NotNull Label state;
        private final @NotNull ProgressBar progress;
        private final @NotNull Button download;
        private final @NotNull Button cancel;
        private final @NotNull Button delete;
        private final @NotNull VBox root;

        /**
         * Creates a {@link PresetRow} instance.
         *
         * @param preset the preset
         */
        private PresetRow(@NotNull TranslationPreset preset) {
            LauncherTexts texts = TranslationPanel.this.texts;
            Label size = ComponentUtils.createLabel(texts.format("translation.size", texts.formatSize(getTotalSize(preset))), "text-caption");
            HBox buttons;

            this.preset   = preset;
            this.state    = ComponentUtils.createLabel("", "text-caption");
            this.progress = new ProgressBar(0);
            this.download = ComponentUtils.createSmallButton(texts.get("translation.download"), ButtonRole.SECONDARY);
            this.cancel   = ComponentUtils.createSmallButton(texts.get("translation.cancel"), ButtonRole.SECONDARY);
            this.delete   = ComponentUtils.createSmallButton(texts.get("translation.delete"), ButtonRole.DANGER);

            this.progress.setMaxWidth(Double.MAX_VALUE);
            this.download.setOnAction(event -> TranslationPanel.this.openDownload(preset));
            this.cancel.setOnAction(event -> TranslationPanel.this.cancelDownload(preset));
            this.delete.setOnAction(event -> TranslationPanel.this.openDelete(preset));

            buttons = ComponentUtils.createRow(Spacing.S, this.download, this.cancel, this.delete);
            buttons.setAlignment(Pos.CENTER_LEFT);

            this.root = ComponentUtils.createColumn(Spacing.XS, ComponentUtils.createLabel(preset.getDisplayName(), "list-title"), size,
                                                    TranslationPanel.this.createLicenseRow(preset), this.state, this.progress, buttons);
            this.refresh();
        }

        /**
         * Shows the preset's state: downloading with its progress, installed, the reason of a failed download or not installed.
         */
        private void refresh() {
            LauncherTexts texts = TranslationPanel.this.texts;
            PresetDownload running = TranslationPanel.this.translation.getDownload(this.preset);
            boolean downloading = running != null && running.isRunning();
            boolean installed = !downloading && TranslationPanel.this.translation.isInstalled(this.preset);
            String failure = running == null || downloading ? null : running.getFailure();

            if (downloading) {
                long total = running.getTotalBytes();

                setText(this.state, texts.format("translation.downloading", texts.formatSize(running.getInstalledBytes()), texts.formatSize(total)),
                        Tone.ACCENT);
                this.progress.setProgress(total <= 0 ? ProgressBar.INDETERMINATE_PROGRESS : (double) running.getInstalledBytes() / total);
            } else if (installed) {
                setText(this.state, texts.get("translation.installed"), Tone.SUCCESS);
            } else if (failure != null) {
                setText(this.state, texts.format("translation.download.failed", failure), Tone.DANGER);
            } else {
                setText(this.state, texts.get("translation.missing"), Tone.NEUTRAL);
            }

            setShown(this.progress, downloading);
            setShown(this.download, !downloading && !installed);
            setShown(this.cancel, downloading);
            setShown(this.delete, installed);
        }
    }

    private static void setText(@NotNull Label label, @NotNull String text, @NotNull Tone tone) {
        label.setText(text);
        label.getStyleClass().removeAll(TONE_STYLES);
        label.getStyleClass().add(OptionsScreen.getToneStyle(tone));
    }

    private static void setShown(@NotNull Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static long getTotalSize(@NotNull TranslationPreset preset) {
        return preset.getFiles().stream().mapToLong(PresetFile::getSize).sum();
    }


    private final @NotNull LauncherShell shell;
    private final @NotNull LauncherTexts texts;
    private final @NotNull LauncherTranslation translation;
    private final @NotNull OptionsScreen.Actions actions;

    private final @NotNull PresetOption off;
    private final @NotNull ComboBox<PresetOption> choice;
    private final @NotNull List<@NotNull PresetRow> rows;
    private @NotNull List<@NotNull TranslationPreset> listedPresets;
    private boolean listing;
    private final @NotNull VBox root;
    private final @NotNull Timeline refresh;

    /**
     * Creates a {@link TranslationPanel} instance.
     *
     * @param shell       shows the panel's dialogs
     * @param texts       the launcher texts of the display language
     * @param translation the launcher's translation, its presets and their downloads
     * @param actions     receives the chosen preset and the licence addresses to open
     */
    TranslationPanel(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull LauncherTranslation translation,
                     @NotNull OptionsScreen.Actions actions) {
        VBox body;

        this.shell       = shell;
        this.texts       = texts;
        this.translation = translation;
        this.actions     = actions;

        this.off           = new PresetOption(null, texts.get("translation.none"));
        this.choice        = ComponentUtils.createComboBox(ControlWidth.M);
        this.rows          = new ArrayList<>();
        this.listedPresets = List.of();
        this.listing       = false;

        for (TranslationPreset preset : translation.getPresets()) {
            this.rows.add(new PresetRow(preset));
        }

        this.listChoices();
        this.choice.valueProperty().addListener((observable, previous, current) -> {
            if (current != null && !this.listing) {
                this.actions.onTranslationPresetChanged(current.preset == null ? null : current.preset.getId());
            }
        });

        body = ComponentUtils.createPanelBody(ComponentUtils.createParagraph(texts.get("translation.help"), "text-caption"),
                                              ComponentUtils.createForm(ComponentUtils.createField(texts.get("translation.model"), this.choice)));

        for (PresetRow row : this.rows) {
            body.getChildren().addAll(new Separator(), row.root);
        }

        this.root    = ComponentUtils.createPanel(texts.get("settings.translation"), body);
        this.refresh = new Timeline(new KeyFrame(REFRESH_INTERVAL, event -> this.refreshRows()));

        this.refresh.setCycleCount(Timeline.INDEFINITE);
        this.root.sceneProperty().addListener((observable, previous, current) -> {
            if (current == null) {
                this.refresh.stop();
            } else {
                this.refresh.play();
            }
        });
    }

    /**
     * @return the panel's root node
     */
    @NotNull VBox getRoot() {
        return this.root;
    }


    private @NotNull HBox createLicenseRow(@NotNull TranslationPreset preset) {
        Hyperlink license = new Hyperlink(preset.getLicenseName());
        HBox row = ComponentUtils.createRow(Spacing.S, ComponentUtils.createLabel(this.texts.get("translation.license"), "text-caption"), license);

        license.setOnAction(event -> this.actions.onOpenWebsite(preset.getLicenseAddress().toString()));
        row.setAlignment(Pos.CENTER_LEFT);

        return row;
    }

    private void refreshRows() {
        for (PresetRow row : this.rows) {
            row.refresh();
        }

        this.listChoices();
    }

    /**
     * Lists Off and the installed presets that are not downloading in the model choice, with the chosen preset selected when it is listed
     * and Off otherwise; the list is rebuilt only when the installed presets change, and rebuilding reports no choice.
     */
    private void listChoices() {
        List<TranslationPreset> installed = new ArrayList<>();

        for (TranslationPreset preset : this.translation.getPresets()) {
            PresetDownload running = this.translation.getDownload(preset);

            if ((running == null || !running.isRunning()) && this.translation.isInstalled(preset)) {
                installed.add(preset);
            }
        }

        if (installed.equals(this.listedPresets) && !this.choice.getItems().isEmpty()) {
            return;
        }

        String chosen = this.translation.getChosenPreset();
        PresetOption selected = this.off;

        this.listedPresets = List.copyOf(installed);
        this.listing       = true;

        try {
            this.choice.getItems().setAll(this.off);

            for (TranslationPreset preset : installed) {
                PresetOption option = new PresetOption(preset, preset.getDisplayName());

                this.choice.getItems().add(option);

                if (preset.getId().equals(chosen)) {
                    selected = option;
                }
            }

            this.choice.setValue(selected);
        } finally {
            this.listing = false;
        }
    }

    /**
     * Opens the Download dialog of a preset: its name, licence and size; Download starts the download and closes the dialog.
     */
    private void openDownload(@NotNull TranslationPreset preset) {
        ModalDialog dialog = new ModalDialog(this.texts.get("translation.download.title"));
        GridPane facts = ComponentUtils.createMetaGrid();
        Button cancel = ComponentUtils.createButton(this.texts.get("translation.cancel"), ButtonRole.SECONDARY);
        Button start = ComponentUtils.createButton(this.texts.get("translation.download"), ButtonRole.PRIMARY);

        ComponentUtils.addMetaRow(facts, this.texts.get("translation.model"), preset.getDisplayName());
        ComponentUtils.addMetaRow(facts, this.texts.get("translation.license"), this.createLicenseRow(preset));
        ComponentUtils.addMetaRow(facts, this.texts.get("update.size"), this.texts.formatSize(getTotalSize(preset)));
        dialog.addContent(facts, ComponentUtils.createParagraph(this.texts.get("translation.download.text"), "text-caption"));
        dialog.setButtons(cancel, start);
        cancel.setOnAction(event -> this.shell.closeDialog(dialog));
        start.setOnAction(event -> {
            this.shell.closeDialog(dialog);
            this.translation.download(preset);
            this.refreshRows();
        });

        this.shell.showDialog(dialog);
        start.requestFocus();
    }

    private void cancelDownload(@NotNull TranslationPreset preset) {
        PresetDownload running = this.translation.getDownload(preset);

        if (running != null) {
            running.cancel();
        }

        this.refreshRows();
    }

    /**
     * Opens the Delete dialog of a preset; Delete removes its files away from the user interface thread and, for the chosen preset, sets the
     * choice to Off.
     */
    private void openDelete(@NotNull TranslationPreset preset) {
        ModalDialog dialog = new ModalDialog(this.texts.get("translation.delete.title"));
        Button cancel = ComponentUtils.createButton(this.texts.get("translation.cancel"), ButtonRole.SECONDARY);
        Button delete = ComponentUtils.createButton(this.texts.get("translation.delete"), ButtonRole.DANGER);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        dialog.addContent(ComponentUtils.createParagraph(this.texts.format("translation.delete.text", preset.getDisplayName())));
        dialog.setButtons(cancel, delete);
        cancel.setOnAction(event -> {
            cancelled.set(true);
            this.shell.closeDialog(dialog);
        });
        delete.setOnAction(event -> {
            delete.setDisable(true);
            dialog.runWork(this.texts.get("translation.delete"), cancelled, progress -> {
                this.translation.delete(preset);

                return Boolean.TRUE;
            }, deleted -> {
                this.shell.closeDialog(dialog);

                if (preset.getId().equals(this.translation.getChosenPreset())) {
                    this.actions.onTranslationPresetChanged(null);
                }

                this.refreshRows();
            }, exception -> this.texts.format("translation.delete.failed", exception.getMessage()), () -> delete.setDisable(false));
        });

        this.shell.showDialog(dialog);
        cancel.requestFocus();
    }
}
