package org.fogofwarlabs.sc4j.launcher.ui.form;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import org.fogofwarlabs.sc4j.launcher.form.FormField;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the control of a {@code boolean} setting: a check box that carries the setting's label.
 */
public final class BooleanControlFactory implements FieldControlFactory {
    /**
     * The factory of {@code boolean} settings.
     */
    public static final @NotNull BooleanControlFactory INSTANCE = new BooleanControlFactory();

    /**
     * Holds the check box of one boolean field.
     */
    private static final class BooleanControl implements FieldControl {
        private final @NotNull FormField field;
        private final @NotNull CheckBox checkBox;

        /**
         * Creates a {@link BooleanControl} instance.
         *
         * @param field    the field
         * @param texts    the launcher texts of the display language
         * @param onChange called after a changed value was passed to the field
         */
        private BooleanControl(@NotNull FormField field, @NotNull LauncherTexts texts, @NotNull Runnable onChange) {
            this.field    = field;
            this.checkBox = new CheckBox(texts.localize(field.getDefinition().getLabel()));

            this.showValue();
            this.checkBox.selectedProperty().addListener((observable, previous, current) -> {
                field.setInput(current);
                onChange.run();
            });
        }

        /**
         * @return the node the form places in the field, below the label
         */
        @Override
        public @NotNull Node getNode() {
            return this.checkBox;
        }

        /**
         * @return the input node that shows the {@code :invalid} state when the value fails validation
         */
        @Override
        public @NotNull Node getInput() {
            return this.checkBox;
        }

        /**
         * @return {@code true} if the control shows the setting's label itself (a check box) instead of the form showing it above the control, {@code false} otherwise
         */
        @Override
        public boolean isLabelled() {
            return true;
        }

        /**
         * Shows the field's current value again, after the form changed it (for example a reset to the defaults).
         */
        @Override
        public void showValue() {
            this.checkBox.setSelected(Boolean.TRUE.equals(this.field.getValue()));
        }
    }


    /**
     * Creates a {@link BooleanControlFactory} instance.
     */
    private BooleanControlFactory() {
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
        return new BooleanControl(field, texts, onChange);
    }
}
