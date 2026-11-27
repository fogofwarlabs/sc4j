package org.fogofwarlabs.sc4j.translate.service.llama;

import org.fogofwarlabs.llama.Generation;
import org.fogofwarlabs.llama.LlamaContext;
import org.fogofwarlabs.llama.LlamaException;
import org.fogofwarlabs.llama.LlamaModel;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.translate.service.model.ModelException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates texts with a llama.cpp model and one context of it, both closed with the generator.
 */
final class LlamaTextGenerator implements TextGenerator {
    private final @NotNull LlamaModel model;
    private final @NotNull LlamaContext context;

    /**
     * Creates a {@link LlamaTextGenerator} instance that owns a model and its context.
     *
     * @param model   the loaded model
     * @param context the context of the model
     */
    LlamaTextGenerator(@NotNull LlamaModel model, @NotNull LlamaContext context) {
        this.model   = model;
        this.context = context;
    }

    /**
     * @return the largest number of prompts that one call of {@link #generate} takes
     */
    @Override
    public int getSequenceCount() {
        return this.context.getSequenceCount();
    }

    /**
     * @return the number of tokens the sequences of one call of {@link #generate} hold together, their prompts and generated tokens
     */
    @Override
    public int getContextSize() {
        return this.context.getContextSize();
    }

    /**
     * @return the number of tokens one sequence holds, its prompt and its generated tokens together
     */
    @Override
    public int getSequenceContextSize() {
        return this.context.getSequenceContextSize();
    }

    /**
     * Counts the tokens of a plain text, special tokens written in it read as text.
     *
     * @param text the text
     * @return the number of tokens
     */
    @Override
    public int countTokens(@NotNull String text) {
        return this.model.getVocabulary().tokenize(text, false, false).length;
    }

    /**
     * Counts the tokens of a prompt as {@link #generate} reads it, special tokens written in it read as those tokens.
     *
     * @param prompt the prompt
     * @return the number of tokens
     */
    @Override
    public int countPromptTokens(@NotNull String prompt) {
        return this.model.getVocabulary().tokenize(prompt, true, true).length;
    }

    /**
     * Generates a text from each prompt; a sequence stops at an end-of-generation token, at the maximum number of tokens or when it is full.
     *
     * @param prompts       the prompts, at most {@link #getSequenceCount()}, with the special tokens of the model's chat format written as
     *                      text
     * @param maximumTokens the largest number of generated tokens of each sequence
     * @return the generated texts without the end-of-generation token, in the order of the prompts
     * @throws ModelException if the generation fails or a prompt does not fit a sequence
     */
    @Override
    public @NotNull List<@NotNull String> generate(@NotNull List<@NotNull String> prompts, int maximumTokens) throws ModelException {
        List<Generation> generations;

        try {
            generations = this.context.generate(prompts, maximumTokens);
        } catch (LlamaException | IllegalArgumentException exception) {
            throw new ModelException(Exceptions.format("The generation failed: {0}", exception.getMessage()), exception);
        }

        List<String> texts = new ArrayList<>(generations.size());

        for (Generation generation : generations) {
            texts.add(generation.getText());
        }

        return texts;
    }

    /**
     * Releases the native resources.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.context::close,
                this.model::close
        );
    }
}
