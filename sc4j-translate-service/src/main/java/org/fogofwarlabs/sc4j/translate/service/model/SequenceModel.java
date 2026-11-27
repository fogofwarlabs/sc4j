package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

/**
 * Generates output token sequences from input token sequences with an encoder and a decoder, a batch of sequences in one call. Tokens are
 * the strings of the model's vocabulary. It owns native resources until it is closed and is used by one thread at a time.
 */
public interface SequenceModel extends AutoCloseable {
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
    @NotNull String @NotNull [] @NotNull [] generate(@NotNull String @NotNull [] @NotNull [] sources,
                                                     @NotNull String @NotNull [] @NotNull [] targetPrefixes,
                                                     int maximumLength) throws ModelException;

    /**
     * Releases the native resources.
     */
    @Override
    void close();
}
