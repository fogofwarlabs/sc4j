package org.fogofwarlabs.sc4j.translate.service;

import org.fogofwarlabs.sc4j.translate.service.model.TranslationPreset;
import org.fogofwarlabs.sc4j.translate.service.nllb.NllbPreset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Lists the translation presets that SC4J offers for download and finds them by id.
 */
public final class TranslationPresetUtils {
    private static final @NotNull List<@NotNull TranslationPreset> PRESETS = List.of(new NllbPreset());

    /**
     * @return the offered presets in the order they are shown; unmodifiable
     */
    public static @NotNull List<@NotNull TranslationPreset> getPresets() {
        return PRESETS;
    }

    /**
     * Finds an offered preset by its id.
     *
     * @param id the preset id, such as {@code nllb-200-distilled-600m}
     * @return the preset, or {@code null} when no offered preset has the id
     */
    public static @Nullable TranslationPreset findPreset(@NotNull String id) {
        for (TranslationPreset preset : PRESETS) {
            if (preset.getId().equals(id)) {
                return preset;
            }
        }

        return null;
    }


    private TranslationPresetUtils() {
    }
}
