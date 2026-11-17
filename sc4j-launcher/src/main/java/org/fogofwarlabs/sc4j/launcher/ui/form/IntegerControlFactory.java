package org.fogofwarlabs.sc4j.launcher.ui.form;

import javafx.scene.Node;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.converter.IntegerStringConverter;
import org.fogofwarlabs.sc4j.launcher.form.FormField;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.ComponentUtils;
import org.fogofwarlabs.sc4j.launcher.ui.ControlWidth;
import org.fogofwarlabs.sc4j.manifest.settings.IntegerSetting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creates the control of an {@code integer} setting: an editable spinner whose arrows stay within the setting's {@code min}
 * and {@code max}. The typed text goes to the field as it is, so text that is not a whole number or lies outside the
 * range fails validation and shows its message.
 */
public final class IntegerControlFactory implements FieldControlFactory {
    /**
     * The factory of {@code integer} settings.
     */
    public static final @NotNull IntegerControlFactory INSTANCE = new IntegerControlFactory();


    /**
     * Holds the spinner of one integer field.
     */
    private static final class IntegerControl implements FieldControl {
        private final @NotNull FormField field;
        private final @NotNull Spinner<@NotNull Integer> spinner;

        /**
         * Creates a {@link IntegerControl} instance.
         *
         * @param field    the field
         * @param onChange called after a changed value was passed to the field
         */
        private IntegerControl(@NotNull FormField field, @NotNull Runnable onChange) {
            Integer minimum = null;
            Integer maximum = null;
            SpinnerValueFactory.IntegerSpinnerValueFactory values;

            if (field.getDefinition() instanceof IntegerSetting integer) {
                minimum = integer.getMinimum();
                maximum = integer.getMaximum();
            }

            values = new SpinnerValueFactory.IntegerSpinnerValueFactory(minimum == null ? Integer.MIN_VALUE : minimum,
                                                                        maximum == null ? Integer.MAX_VALUE : maximum);
            values.setConverter(new IntegerStringConverter() {
                /**
                 * Converts the string provided into an object defined by the specific converter.
                 *
                 * @param text the string to convert
                 * @return an object representation of the string passed in, or {@code null} when it is not a whole number
                 */
                @Override
                public @Nullable Integer fromString(@Nullable String text) {
                    try {
                        return super.fromString(text);
                    } catch (NumberFormatException exception) {
                        return null;
                    }
                }
            });

            this.field   = field;
            this.spinner = new Spinner<>(values);

            this.spinner.setEditable(true);
            ComponentUtils.setControlWidth(this.spinner, ControlWidth.S);
            this.showValue();
            this.spinner.getEditor().textProperty().addListener((observable, previous, current) -> {
                field.setInput(current);
                onChange.run();
            });
        }

        /**
         * @return the node the form places in the field, below the label
         */
        @Override
        public @NotNull Node getNode() {
            return this.spinner;
        }

        /**
         * @return the input node that shows the {@code :invalid} state when the value fails validation
         */
        @Override
        public @NotNull Node getInput() {
            return this.spinner;
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
            this.spinner.getEditor().setText(this.field.getText());
        }
    }


    /**
     * Creates a {@link IntegerControlFactory} instance.
     */
    private IntegerControlFactory() {
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
        return new IntegerControl(field, onChange);
    }
}
