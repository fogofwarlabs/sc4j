package org.fogofwarlabs.sc4j.launcher.ui;

/**
 * Lists the widths of the fixed side columns of a screen beside its growing content ({@link ComponentUtils#setColumnWidth}).
 */
public enum ColumnWidth {
    /**
     * 250 pixels, such as the channel and member lists beside a chat.
     */
    NARROW(250),
    /**
     * 360 pixels, such as a list of mods or accounts beside its details.
     */
    WIDE(360);

    private final double width;

    /**
     * Creates a {@link ColumnWidth} instance.
     *
     * @param width the width in pixels
     */
    ColumnWidth(double width) {
        this.width = width;
    }

    /**
     * @return the width in pixels
     */
    public double getWidth() {
        return this.width;
    }
}
