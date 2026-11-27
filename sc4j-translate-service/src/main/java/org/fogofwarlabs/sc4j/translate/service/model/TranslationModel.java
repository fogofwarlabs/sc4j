package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Translates texts between the languages of a loaded preset, a batch of texts with their own languages and contexts in one call. It owns
 * the native resources of its models until it is closed and is used by one thread at a time.
 * <p>
 * The {@link org.fogofwarlabs.sc4j.translate.service.TranslationService} hands it whole text runs; a run with more tokens than
 * {@link #getMaximumInputTokens()} is first split at sentence boundaries ({@link TextSplitter#chunk}).
 */
public interface TranslationModel extends AutoCloseable {
    /**
     * @return the largest number of tokens one input may have, as {@link #countTokens} counts them
     */
    int getMaximumInputTokens();

    /**
     * Counts the tokens of a text as {@link #translate} feeds it to the model.
     *
     * @param text the text
     * @return the number of tokens
     */
    int countTokens(@NotNull String text);

    /**
     * Translates a batch of texts that fit {@link #getMaximumInputTokens()}; longer input is cut there.
     *
     * @param inputs the texts with their languages and contexts
     * @return the translated texts, in the order of the inputs
     * @throws ModelException           if the inference fails
     * @throws IllegalArgumentException if the preset does not support a language
     */
    @NotNull List<@NotNull String> translate(@NotNull List<@NotNull TranslationInput> inputs) throws ModelException;

    /**
     * Releases the native resources.
     */
    @Override
    void close();
}
