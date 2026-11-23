package org.fogofwarlabs.sc4j.launcher.ui.form;

import javafx.scene.Node;
import org.jetbrains.annotations.NotNull;

/**
 * Represents the control of one generated form field, created by the {@link FieldControlFactory} of its setting type.
 */
public interface FieldControl {
    /**
     * @return the node the form places in the field, below the label
     */
    @NotNull Node getNode();

    /**
     * @return the input node that shows the {@code :invalid} state when the value fails validation
     */
    @NotNull Node getInput();

    /**
     * @return {@code true} if the control shows the setting's label itself (a check box) instead of the form showing it above the control, {@code false} otherwise
     */
    boolean isLabelled();

    /**
     * Shows the field's current value again, after the form changed it (for example a reset to the defaults).
     */
    void showValue();
}
