package org.fogofwarlabs.sc4j.launcher.ui.form;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import org.fogofwarlabs.sc4j.launcher.form.FormField;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.ComponentUtils;
import org.fogofwarlabs.sc4j.launcher.ui.ControlWidth;
import org.fogofwarlabs.sc4j.manifest.settings.ChoiceSetting;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the control of a {@code choice} setting: a combo box with the setting's {@code options} in manifest order.
 */
public final class ChoiceControlFactory implements FieldControlFactory {
    /**
     * The factory of {@code choice} settings.
     */
    public static final @NotNull ChoiceControlFactory INSTANCE = new ChoiceControlFactory();


    /**
     * Holds the combo box of one choice field.
     */
    private static final class ChoiceControl implements FieldControl {
        private final @NotNull FormField field;
        private final @NotNull ComboBox<String> comboBox;

        /**
         * Creates a {@link ChoiceControl} instance.
         *
         * @param field    the field
         * @param texts    the launcher texts of the display language
         * @param onChange called after a changed value was passed to the field
         */
        private ChoiceControl(@NotNull FormField field, @NotNull LauncherTexts texts, @NotNull Runnable onChange) {
            this.field    = field;
            this.comboBox = ComponentUtils.comboBox(ControlWidth.M);

            if (field.getDefinition() instanceof ChoiceSetting choice) {
                this.comboBox.getItems().setAll(choice.getOptions());
            }

            this.comboBox.setPromptText(texts.get("form.choose.option"));
            this.showValue();
            this.comboBox.valueProperty().addListener((observable, previous, current) -> {
                field.setInput(current);
                onChange.run();
            });
        }

        /**
         * @return the node the form places in the field, below the label
         */
        @Override
        public @NotNull Node getNode() {
            return this.comboBox;
        }

        /**
         * @return the input node that shows the {@code :invalid} state when the value fails validation
         */
        @Override
        public @NotNull Node getInput() {
            return this.comboBox;
        }

        /**
         * @return {@code true} if the control shows the setting's label itself (a check box) instead of the form showing it above
         * the control, {@code false} otherwise
         */
        @Override
        public boolean isLabelled() {
            return false;
        }

        /**
         * Shows the field's current value again, after the form changed it (for example a reset to the defaults).
         */
        @Override
        public void showValue() {
            Object value = this.field.getValue();

            this.comboBox.setValue(value == null ? null : String.valueOf(value));
        }
    }


    /**
     * Creates a {@link ChoiceControlFactory} instance.
     */
    private ChoiceControlFactory() {
    }

    /**
     * Creates the control of a field.
     *
     * @param field    the field, whose setting has this factory's type
     * @param texts    the launcher texts of the display language
     * @param onChange called after the control passed a changed value to the field
     * @return the created control, showing the field's current value
     */
    @Override
    public @NotNull FieldControl create(@NotNull FormField field, @NotNull LauncherTexts texts, @NotNull Runnable onChange) {
        return new ChoiceControl(field, texts, onChange);
    }
}
