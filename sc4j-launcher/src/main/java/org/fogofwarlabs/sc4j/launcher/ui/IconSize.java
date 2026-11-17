package org.fogofwarlabs.sc4j.launcher.ui;

/**
 * Lists the sizes of square images: logos, mod icons and list thumbnails.
 */
public enum IconSize {
    /**
     * 16 pixels, such as the logo in the status bar.
     */
    S(16),
    /**
     * 44 pixels, such as a list thumbnail ({@link ListItem#setThumbnail}) or the logo in the app bar.
     */
    M(44),
    /**
     * 64 pixels, such as the logo of a dialog or the icon in a mod banner.
     */
    L(64),
    /**
     * 72 pixels, such as the icon on a mod's details.
     */
    XL(72);

    private final double size;

    /**
     * Creates a {@link IconSize} instance.
     *
     * @param size the width and height in pixels
     */
    IconSize(double size) {
        this.size = size;
    }

    /**
     * @return the width and height in pixels
     */
    public double getSize() {
        return this.size;
    }
}
