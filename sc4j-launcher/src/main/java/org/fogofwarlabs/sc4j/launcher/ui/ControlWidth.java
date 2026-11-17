package org.fogofwarlabs.sc4j.launcher.ui;

/**
 * Lists the widths of input controls in forms and dialogs ({@link ComponentUtils#setControlWidth}, {@link ComponentUtils#comboBox}).
 */
public enum ControlWidth {
    /**
     * 120 pixels, such as a number or a port.
     */
    S(120),
    /**
     * 240 pixels, such as a choice.
     */
    M(240),
    /**
     * 360 pixels, such as a text, a path or a repository.
     */
    L(360);

    private final double width;

    /**
     * Creates a {@link ControlWidth} instance.
     *
     * @param width the width in pixels
     */
    ControlWidth(double width) {
        this.width = width;
    }

    /**
     * @return the width in pixels
     */
    public double getWidth() {
        return this.width;
    }
}
