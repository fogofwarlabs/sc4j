package org.fogofwarlabs.sc4j.translate.service.llama;

import org.fogofwarlabs.llama.*;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.service.model.ModelException;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Loads GGUF models with llama.cpp: on the GPU with every layer on every GPU device, on the CPU without any. A context holds
 * {@value #CONTEXT_SIZE} tokens that up to {@value #SEQUENCE_COUNT} parallel sequences share, so that one long sequence may use most of
 * them, and runs on
 * {@code min(4, max(1, processors / 4))} threads, so that the generation leaves most processors to the game; it decodes batches of every
 * shape once after it is created, so that the first request does not wait for the GPU kernels.
 * <p>
 * The native libraries are found as llama-java finds them, through the system property {@code org.fogofwarlabs.llama.librarypath} or its
 * native jar.
 */
public final class LlamaTextGeneratorFactory implements TextGeneratorFactory {
    /**
     * The shared factory.
     */
    public static final @NotNull LlamaTextGeneratorFactory INSTANCE = new LlamaTextGeneratorFactory();

    /**
     * The number of tokens of a context, which its sequences share.
     */
    public static final int CONTEXT_SIZE = 2048;

    /**
     * The number of sequences a context generates in parallel.
     */
    public static final int SEQUENCE_COUNT = 4;

    private static final int MINIMUM_THREADS = 1;
    private static final int MAXIMUM_THREADS = 4;
    private static final int PROCESSORS_PER_THREAD = 4;

    private LlamaTextGeneratorFactory() {
    }

    /**
     * Tells whether the machine has a GPU that a model can run on.
     *
     * @return {@code true} if a GPU is present, {@code false} otherwise
     * @throws ModelException if the runtime of the models cannot be loaded
     */
    @Override
    public boolean hasGpu() throws ModelException {
        try {
            return !LlamaRuntime.getInstance().getGpuDevices().isEmpty();
        } catch (LlamaException exception) {
            throw new ModelException(Exceptions.format("Cannot load llama.cpp: {0}", exception.getMessage()), exception);
        }
    }

    /**
     * Loads a model and prepares it for generation.
     *
     * @param file the model file
     * @param gpu  whether the model runs on the GPU, else on the CPU
     * @return the loaded text generator, which the caller closes
     * @throws ModelException if the model cannot be loaded or prepared on the device
     */
    @Override
    public @NotNull TextGenerator create(@NotNull Path file, boolean gpu) throws ModelException {
        int threads = Math.min(MAXIMUM_THREADS, Math.max(MINIMUM_THREADS, Runtime.getRuntime().availableProcessors() / PROCESSORS_PER_THREAD));
        ModelOptions modelOptions = gpu ? new ModelOptions() : new ModelOptions().setGpuLayers(0).setDevices(List.of());
        ContextOptions contextOptions = new ContextOptions().setContextSize(CONTEXT_SIZE)
                                                            .setSequenceCount(SEQUENCE_COUNT)
                                                            .setUnifiedMemory(true)
                                                            .setThreadCount(threads)
                                                            .setBatchThreadCount(threads);
        LlamaModel model;

        try {
            model = LlamaModel.load(file, modelOptions);
        } catch (LlamaException exception) {
            throw new ModelException(Exceptions.format("Cannot load the model {0}: {1}", file, exception.getMessage()), exception);
        }

        LlamaContext context = null;

        try {
            context = model.createContext(contextOptions);
            context.warmUp();

            return new LlamaTextGenerator(model, context);
        } catch (LlamaException exception) {
            if (context != null) {
                context.close();
            }

            model.close();

            throw new ModelException(Exceptions.format("Cannot prepare the model {0}: {1}", file, exception.getMessage()), exception);
        }
    }
}
