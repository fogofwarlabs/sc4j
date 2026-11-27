package org.fogofwarlabs.sc4j.translate.service.ctranslate2;

import org.bytedeco.javacpp.BytePointer;
import org.fogofwarlabs.ctranslate2.*;
import org.fogofwarlabs.ctranslate2.global.ctranslate2.BatchType;
import org.fogofwarlabs.ctranslate2.global.ctranslate2.ComputeType;
import org.fogofwarlabs.ctranslate2.global.ctranslate2.Device;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.translate.service.model.ModelException;
import org.fogofwarlabs.sc4j.translate.service.model.SequenceModel;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Runs a model converted to CTranslate2 (a folder with {@code model.bin}, {@code config.json} and the vocabulary) on the CPU with int8
 * weights. Decoding is greedy; a batch is split into parts of at most {@link #MAXIMUM_BATCH_SIZE} sequences, which CTranslate2 groups by
 * length.
 * <p>
 * The model runs as one replica with {@code min(4, max(1, processors / 4))} threads, so that the translation leaves most processors to the
 * game.
 */
public final class CTranslate2SequenceModel implements SequenceModel {
    /**
     * The largest number of sequences decoded together.
     */
    public static final int MAXIMUM_BATCH_SIZE = 16;

    private static final int REPLICAS = 1;
    private static final int MINIMUM_THREADS = 1;
    private static final int MAXIMUM_THREADS = 4;
    private static final int PROCESSORS_PER_THREAD = 4;
    private static final int GREEDY_BEAM_SIZE = 1;

    /**
     * Loads a converted model.
     *
     * @param folder the folder of the converted model
     * @return the created {@link CTranslate2SequenceModel} instance, which the caller closes
     * @throws ModelException if the model cannot be loaded
     */
    public static @NotNull CTranslate2SequenceModel open(@NotNull Path folder) throws ModelException {
        int threads = Math.min(MAXIMUM_THREADS, Math.max(MINIMUM_THREADS, Runtime.getRuntime().availableProcessors() / PROCESSORS_PER_THREAD));

        try (ModelLoader loader = new ModelLoader(folder.toString());
             ReplicaPoolConfig config = new ReplicaPoolConfig()) {
            loader.device(Device.CPU).compute_type(ComputeType.INT8).num_replicas_per_device(REPLICAS);
            config.num_threads_per_replica(threads);

            return new CTranslate2SequenceModel(new Translator(loader, config));
        } catch (RuntimeException exception) {
            throw new ModelException(Exceptions.format("Cannot load the model {0}: {1}", folder, exception.getMessage()), exception);
        }
    }

    private static @NotNull StringVectorVector createBatch(@NotNull String @NotNull [] @NotNull [] sequences) {
        StringVectorVector batch = new StringVectorVector(sequences.length);

        for (int index = 0; index < sequences.length; index++) {
            try (StringVector tokens = new StringVector(sequences[index])) {
                batch.put(index, tokens);
            }
        }

        return batch;
    }

    private static @NotNull String @NotNull [] readTokens(@NotNull StringVector tokens) {
        String[] output = new String[(int) tokens.size()];

        for (int index = 0; index < output.length; index++) {
            try (BytePointer token = tokens.get(index)) {
                output[index] = token.getString(StandardCharsets.UTF_8);
            }
        }

        return output;
    }


    private final @NotNull Translator translator;
    private final @NotNull TranslationOptions options;

    private CTranslate2SequenceModel(@NotNull Translator translator) {
        this.translator = translator;
        this.options    = new TranslationOptions();

        this.options.beam_size(GREEDY_BEAM_SIZE).return_end_token(false);
    }

    /**
     * Generates the output sequences greedily: each output starts with its prefix and continues with the token of the highest score at each
     * step, until the end token or the length limit.
     *
     * @param sources        the input tokens of each sequence
     * @param targetPrefixes the tokens each output starts with, such as a target language token, one array per sequence
     * @param maximumLength  the largest number of output tokens of a sequence, the prefix included
     * @return the output tokens of each sequence in the order of the inputs, the prefix included, without the end token
     * @throws ModelException if the inference fails
     */
    @Override
    public @NotNull String @NotNull [] @NotNull [] generate(@NotNull String @NotNull [] @NotNull [] sources,
                                                            @NotNull String @NotNull [] @NotNull [] targetPrefixes,
                                                            int maximumLength) throws ModelException {
        if (sources.length != targetPrefixes.length) {
            throw Exceptions.newIllegalArgumentException("{0} sources have {1} target prefixes", sources.length, targetPrefixes.length);
        }

        String[][] outputs = new String[sources.length][];

        this.options.max_decoding_length(maximumLength);

        try (StringVectorVector sourceBatch = createBatch(sources);
             StringVectorVector prefixBatch = createBatch(targetPrefixes);
             TranslationResultVector results = this.translator.translate_batch(sourceBatch, prefixBatch, this.options, MAXIMUM_BATCH_SIZE,
                                                                               BatchType.Examples)) {
            for (int index = 0; index < outputs.length; index++) {
                outputs[index] = readTokens(results.get(index).output());
            }
        } catch (RuntimeException exception) {
            throw new ModelException(Exceptions.format("The inference failed: {0}", exception.getMessage()), exception);
        }

        return outputs;
    }

    /**
     * Releases the native resources.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.options::close,
                this.translator::close
        );
    }
}
