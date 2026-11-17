package org.fogofwarlabs.sc4j.launcher.ui;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Lists the colour roles of badges, status markers and notices.
 */
public enum Tone {
    /**
     * Plain steel.
     */
    NEUTRAL,
    /**
     * The cyan accent.
     */
    ACCENT,
    /**
     * Green: ready, online.
     */
    SUCCESS,
    /**
     * Amber: needs attention.
     */
    WARNING,
    /**
     * Red: failed, unusable.
     */
    DANGER;

    /**
     * @return the style class suffix of the tone, such as {@code danger}
     */
    public @NotNull String getSuffix() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
