package org.fogofwarlabs.sc4j.translate.service.llama;

import org.fogofwarlabs.sc4j.translate.service.model.ModelException;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Loads {@link TextGenerator} instances from model files on the GPU or the CPU.
 */
public interface TextGeneratorFactory {
    /**
     * Tells whether the machine has a GPU that a model can run on.
     *
     * @return {@code true} if a GPU is present, {@code false} otherwise
     * @throws ModelException if the runtime of the models cannot be loaded
     */
    boolean hasGpu() throws ModelException;

    /**
     * Loads a model and prepares it for generation.
     *
     * @param file the model file
     * @param gpu  whether the model runs on the GPU, else on the CPU
     * @return the loaded text generator, which the caller closes
     * @throws ModelException if the model cannot be loaded or prepared on the device
     */
    @NotNull TextGenerator create(@NotNull Path file, boolean gpu) throws ModelException;
}
