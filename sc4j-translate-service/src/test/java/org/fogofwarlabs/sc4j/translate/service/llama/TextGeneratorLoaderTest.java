package org.fogofwarlabs.sc4j.translate.service.llama;

import org.fogofwarlabs.sc4j.translate.service.model.ModelException;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationDevice;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the device choice of the text generator loader with a stand-in factory: the GPU when the option allows it and the machine has one,
 * the CPU otherwise, and the CPU after the GPU fails.
 */
final class TextGeneratorLoaderTest {
    private static final @NotNull Path MODEL_FILE = Path.of("model.gguf");

    /**
     * Creates generators that remember their device, records the devices asked for, and fails on the GPU when told to.
     */
    private static final class StandInFactory implements TextGeneratorFactory {
        private final boolean gpuPresent;
        private final boolean gpuFailing;
        private final @NotNull List<@NotNull Boolean> requests;
        private int gpuChecks;

        private StandInFactory(boolean gpuPresent, boolean gpuFailing) {
            this.gpuPresent = gpuPresent;
            this.gpuFailing = gpuFailing;
            this.requests   = new ArrayList<>();
            this.gpuChecks  = 0;
        }

        @Override
        public boolean hasGpu() {
            this.gpuChecks++;

            return this.gpuPresent;
        }

        @Override
        public @NotNull TextGenerator create(@NotNull Path file, boolean gpu) throws ModelException {
            this.requests.add(gpu);

            if (gpu && this.gpuFailing) {
                throw new ModelException("The stand-in GPU fails");
            }

            return new StandInGenerator(gpu);
        }
    }

    /**
     * A generator that only knows its device.
     */
    private static final class StandInGenerator implements TextGenerator {
        private final boolean gpu;

        private StandInGenerator(boolean gpu) {
            this.gpu = gpu;
        }

        @Override
        public int getSequenceCount() {
            return 1;
        }

        @Override
        public int getContextSize() {
            return 1;
        }

        @Override
        public int getSequenceContextSize() {
            return 1;
        }

        @Override
        public int countTokens(@NotNull String text) {
            return 0;
        }

        @Override
        public int countPromptTokens(@NotNull String prompt) {
            return 0;
        }

        @Override
        public @NotNull List<@NotNull String> generate(@NotNull List<@NotNull String> prompts, int maximumTokens) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    @Test
    void load_should_use_the_gpu_when_the_option_allows_it_and_the_machine_has_one() throws ModelException {
        StandInFactory factory = new StandInFactory(true, false);

        Assertions.assertTrue(((StandInGenerator) new TextGeneratorLoader(factory).load(MODEL_FILE, TranslationDevice.AUTOMATIC)).gpu);
        Assertions.assertEquals(List.of(true), factory.requests);
    }

    @Test
    void load_should_use_the_cpu_without_a_gpu_or_when_the_option_asks_for_it() throws ModelException {
        StandInFactory withoutGpu = new StandInFactory(false, false);
        StandInFactory withGpu = new StandInFactory(true, false);

        Assertions.assertFalse(((StandInGenerator) new TextGeneratorLoader(withoutGpu).load(MODEL_FILE, TranslationDevice.AUTOMATIC)).gpu);
        Assertions.assertFalse(((StandInGenerator) new TextGeneratorLoader(withGpu).load(MODEL_FILE, TranslationDevice.CPU)).gpu);
        Assertions.assertEquals(List.of(false), withoutGpu.requests);
        Assertions.assertEquals(List.of(false), withGpu.requests);
        Assertions.assertEquals(0, withGpu.gpuChecks, "the CPU option does not look for a GPU");
    }

    @Test
    void load_should_fall_back_to_the_cpu_when_the_gpu_fails() throws ModelException {
        StandInFactory factory = new StandInFactory(true, true);

        Assertions.assertFalse(((StandInGenerator) new TextGeneratorLoader(factory).load(MODEL_FILE, TranslationDevice.AUTOMATIC)).gpu);
        Assertions.assertEquals(List.of(true, false), factory.requests);
    }
}
