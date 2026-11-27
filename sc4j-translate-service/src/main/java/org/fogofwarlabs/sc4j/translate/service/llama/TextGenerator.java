package org.fogofwarlabs.sc4j.translate.service.llama;

import org.fogofwarlabs.sc4j.translate.service.model.ModelException;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Generates texts greedily from prompts with a loaded language model, several prompts as parallel sequences. It owns the native resources of
 * the model until it is closed and is used by one thread at a time.
 */
public interface TextGenerator extends AutoCloseable {
    /**
     * @return the largest number of prompts that one call of {@link #generate} takes
     */
    int getSequenceCount();

    /**
     * @return the number of tokens the sequences of one call of {@link #generate} hold together, their prompts and generated tokens
     */
    int getContextSize();

    /**
     * @return the number of tokens one sequence holds, its prompt and its generated tokens together
     */
    int getSequenceContextSize();

    /**
     * Counts the tokens of a plain text, special tokens written in it read as text.
     *
     * @param text the text
     * @return the number of tokens
     */
    int countTokens(@NotNull String text);

    /**
     * Counts the tokens of a prompt as {@link #generate} reads it, special tokens written in it read as those tokens.
     *
     * @param prompt the prompt
     * @return the number of tokens
     */
    int countPromptTokens(@NotNull String prompt);

    /**
     * Generates a text from each prompt; a sequence stops at an end-of-generation token, at the maximum number of tokens or when it is full.
     *
     * @param prompts       the prompts, at most {@link #getSequenceCount()}, with the special tokens of the model's chat format written as
     *                      text
     * @param maximumTokens the largest number of generated tokens of each sequence
     * @return the generated texts without the end-of-generation token, in the order of the prompts
     * @throws ModelException if the generation fails or a prompt does not fit a sequence
     */
    @NotNull List<@NotNull String> generate(@NotNull List<@NotNull String> prompts, int maximumTokens) throws ModelException;

    /**
     * Releases the native resources.
     */
    @Override
    void close();
}
