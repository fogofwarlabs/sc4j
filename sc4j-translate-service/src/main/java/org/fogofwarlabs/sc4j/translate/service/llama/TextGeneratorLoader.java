package org.fogofwarlabs.sc4j.translate.service.llama;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.translate.service.model.ModelException;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationDevice;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Loads a {@link TextGenerator} on the device an option allows: with {@link TranslationDevice#AUTOMATIC} on the GPU when the machine has
 * one, else on the CPU; with {@link TranslationDevice#CPU} on the CPU. A model that fails to load or to prepare on the GPU is loaded on the
 * CPU, and the failure is logged.
 */
public final class TextGeneratorLoader {
    private static final @NotNull Logger LOGGER = Logger.of(TextGeneratorLoader.class);

    private final @NotNull TextGeneratorFactory factory;

    /**
     * Creates a {@link TextGeneratorLoader} instance.
     *
     * @param factory the factory that loads the models
     */
    public TextGeneratorLoader(@NotNull TextGeneratorFactory factory) {
        this.factory = factory;
    }

    /**
     * Loads a model on the device the option allows, falling back to the CPU when the GPU fails.
     *
     * @param file   the model file
     * @param device the device option
     * @return the loaded text generator, which the caller closes
     * @throws ModelException if the model cannot be loaded on the CPU
     */
    public @NotNull TextGenerator load(@NotNull Path file, @NotNull TranslationDevice device) throws ModelException {
        if (device == TranslationDevice.AUTOMATIC && this.factory.hasGpu()) {
            try {
                TextGenerator generator = this.factory.create(file, true);

                LOGGER.info("TRANSLATION: {0} runs on the GPU", file.getFileName());

                return generator;
            } catch (ModelException | RuntimeException exception) {
                LOGGER.warning("TRANSLATION: {0} cannot run on the GPU, it runs on the CPU: {1}", file.getFileName(), exception.getMessage());
            }
        }

        TextGenerator generator = this.factory.create(file, false);

        LOGGER.info("TRANSLATION: {0} runs on the CPU", file.getFileName());

        return generator;
    }
}
