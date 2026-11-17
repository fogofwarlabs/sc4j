package org.fogofwarlabs.sc4j.launcher.ui.options;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows the update parts of the Settings screen's launcher panel: Check now for the panel header, the Version field (the running version, the
 * result of the last check after it and Update when a newer version is available) and the update source field.
 */
public final class UpdateView {

    /**
     * Receives the actions of the update part.
     */
    public interface Actions {
        /**
         * Called when the user checks for a newer version.
         *
         * @param source the update source as typed
         */
        void onCheck(@NotNull String source);

        /**
         * Called when the user updates to the newer version.
         */
        void onUpdate();
    }


    private final @NotNull LauncherTexts texts;
    private final @NotNull TextField source;
    private final @NotNull Button check;
    private final @NotNull Label status;
    private final @NotNull Button update;
    private final @NotNull VBox versionField;
    private final @NotNull VBox sourceField;

    /**
     * Creates a {@link UpdateView} instance.
     *
     * @param texts   the launcher texts of the display language
     * @param version the running launcher's version
     * @param source  the update source {@code owner/name}
     * @param actions receives the actions
     */
    public UpdateView(@NotNull LauncherTexts texts, @NotNull String version, @NotNull String source, @NotNull Actions actions) {
        HBox versionLine;

        this.texts  = texts;
        this.source = new TextField(source);
        this.check  = ComponentUtils.createSmallButton(texts.get("update.check"), ButtonRole.SECONDARY);
        this.status = ComponentUtils.createLabel("");
        this.update = ComponentUtils.createButton(texts.get("update.start"), ButtonRole.PRIMARY);

        ComponentUtils.setControlWidth(this.source, ControlWidth.L);
        this.check.setOnAction(event -> actions.onCheck(this.source.getText()));
        this.update.setOnAction(event -> actions.onUpdate());

        versionLine = ComponentUtils.createRow(Spacing.XS, ComponentUtils.createLabel(version), this.status);

        this.versionField = ComponentUtils.createField(texts.get("update.version"), ComponentUtils.createRow(Spacing.M, versionLine, this.update));
        this.sourceField  = ComponentUtils.createField(texts.get("update.source"), this.source);

        this.showStatus(null, Tone.NEUTRAL, false);
    }

    /**
     * @return Check now, the trailing button of the launcher panel's header
     */
    public @NotNull Button getCheckButton() {
        return this.check;
    }

    /**
     * @return the Version field: the running version, the result of the last check and Update
     */
    public @NotNull VBox getVersionField() {
        return this.versionField;
    }

    /**
     * @return the update source field
     */
    public @NotNull VBox getSourceField() {
        return this.sourceField;
    }

    /**
     * Shows that a check runs; Check now is disabled until its result is shown.
     */
    public void showChecking() {
        this.showStatus(this.texts.get("update.checking"), Tone.ACCENT, false);
        this.check.setDisable(true);
    }

    /**
     * Shows the result of a check after the running version and enables Check now again.
     *
     * @param text      the result, or {@code null} to show none
     * @param tone      the result's colour role
     * @param updatable whether a newer version is available, which shows Update
     */
    public void showStatus(@Nullable String text, @NotNull Tone tone, boolean updatable) {
        this.check.setDisable(false);
        this.status.setText(text == null ? "" : this.texts.format("update.status", text));
        this.status.getStyleClass().setAll("label");

        if (tone != Tone.NEUTRAL && tone != Tone.ACCENT) {
            this.status.getStyleClass().add(OptionsScreen.getToneStyle(tone));
        }
        this.status.setVisible(text != null);
        this.status.setManaged(text != null);
        // Update keeps its room while hidden, so the field keeps its height.
        this.update.setVisible(updatable);
    }
}
