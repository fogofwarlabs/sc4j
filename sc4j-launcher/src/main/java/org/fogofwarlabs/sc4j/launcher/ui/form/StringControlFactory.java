package org.fogofwarlabs.sc4j.launcher.ui.form;

import javafx.scene.Node;
import javafx.scene.control.TextField;
import org.fogofwarlabs.sc4j.launcher.form.FormField;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.ComponentUtils;
import org.fogofwarlabs.sc4j.launcher.ui.ControlWidth;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the control of a {@code string} setting: a text field.
 */
public final class StringControlFactory implements FieldControlFactory {
    /**
     * The factory of {@code string} settings.
     */
    public static final @NotNull StringControlFactory INSTANCE = new StringControlFactory();


    /**
     * Holds the text field of one string field.
     */
    private static final class StringControl implements FieldControl {
        private final @NotNull FormField field;
        private final @NotNull TextField text;

        /**
         * Creates a {@link StringControl} instance.
         *
         * @param field    the field
         * @param onChange called after a changed value was passed to the field
         */
        private StringControl(@NotNull FormField field, @NotNull Runnable onChange) {
            this.field = field;
            this.text  = new TextField(field.getText());

            ComponentUtils.setControlWidth(this.text, ControlWidth.L);
            this.text.textProperty().addListener((observable, previous, current) -> {
                field.setInput(current);
                onChange.run();
            });
        }

        /**
         * @return the node the form places in the field, below the label
         */
        @Override
        public @NotNull Node getNode() {
            return this.text;
        }

        /**
         * @return the input node that shows the {@code :invalid} state when the value fails validation
         */
        @Override
        public @NotNull Node getInput() {
            return this.text;
        }

        /**
         * @return {@code true} if the control shows the setting's label itself (a check box) instead of the form showing it above the control, {@code false} otherwise
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
            this.text.setText(this.field.getText());
        }
    }


    /**
     * Creates a {@link StringControlFactory} instance.
     */
    private StringControlFactory() {
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
        return new StringControl(field, onChange);
    }
}
