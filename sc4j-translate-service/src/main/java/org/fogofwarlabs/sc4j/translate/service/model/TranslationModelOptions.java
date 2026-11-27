package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

/**
 * Holds the options a {@link TranslationPreset} creates its translation model with; a preset applies the options its models support.
 */
public final class TranslationModelOptions {
    /**
     * The options on {@link TranslationDevice#AUTOMATIC}.
     */
    public static final @NotNull TranslationModelOptions DEFAULT = new TranslationModelOptions(TranslationDevice.AUTOMATIC);

    private final @NotNull TranslationDevice device;

    /**
     * Creates a {@link TranslationModelOptions} instance.
     *
     * @param device the device the model runs on
     */
    public TranslationModelOptions(@NotNull TranslationDevice device) {
        this.device = device;
    }

    /**
     * @return the device the model runs on
     */
    public @NotNull TranslationDevice getDevice() {
        return this.device;
    }
}
