package org.fogofwarlabs.sc4j.launcher.ui.mods;

import javafx.scene.control.Label;
import org.fogofwarlabs.sc4j.launcher.catalog.ModStatus;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.ComponentUtils;
import org.fogofwarlabs.sc4j.launcher.ui.Tone;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Shows a mod status: its badge and its colour role, the same in every view of a mod.
 */
public final class ModStatusUtils {
    private static final @NotNull String STATUS_KEY_PREFIX = "status.";

    /**
     * Creates the badge of a mod status.
     *
     * @param status the mod status
     * @param texts  the launcher texts of the display language
     * @return the created badge
     */
    public static @NotNull Label createBadge(@NotNull ModStatus status, @NotNull LauncherTexts texts) {
        return ComponentUtils.createBadge(texts.get(STATUS_KEY_PREFIX + status.name().toLowerCase(Locale.ROOT)), getTone(status));
    }

    /**
     * Returns the colour role of a mod status.
     *
     * @param status the mod status
     * @return {@link Tone#SUCCESS} for ready, {@link Tone#WARNING} for a warning and {@link Tone#DANGER} for an error
     */
    public static @NotNull Tone getTone(@NotNull ModStatus status) {
        return switch (status) {
            case READY -> Tone.SUCCESS;
            case WARNING -> Tone.WARNING;
            case ERROR -> Tone.DANGER;
        };
    }


    private ModStatusUtils() {
    }
}
