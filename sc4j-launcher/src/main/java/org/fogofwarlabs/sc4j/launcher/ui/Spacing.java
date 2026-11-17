package org.fogofwarlabs.sc4j.launcher.ui;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Lists the spacing scale of the stylesheet: 4, 8, 12, 16, 24 and 32 pixels.
 */
public enum Spacing {
    /**
     * 4 pixels.
     */
    XS,
    /**
     * 8 pixels.
     */
    S,
    /**
     * 12 pixels.
     */
    M,
    /**
     * 16 pixels.
     */
    L,
    /**
     * 24 pixels.
     */
    XL,
    /**
     * 32 pixels.
     */
    XXL;

    private static final @NotNull String STYLE_CLASS_PREFIX = "spacing-";

    /**
     * @return the style class of the spacing, such as {@code spacing-m}
     */
    public @NotNull String getStyleClass() {
        return STYLE_CLASS_PREFIX + this.name().toLowerCase(Locale.ROOT);
    }
}
