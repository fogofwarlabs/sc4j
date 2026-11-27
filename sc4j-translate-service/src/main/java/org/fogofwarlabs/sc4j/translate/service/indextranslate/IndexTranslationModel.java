package org.fogofwarlabs.sc4j.translate.service.indextranslate;

import org.fogofwarlabs.sc4j.translate.service.llama.TextGenerator;
import org.fogofwarlabs.sc4j.translate.service.model.ModelException;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationInput;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationModel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates with Index-Translate: each text gets a prompt of its own (the constrained instruction when its context gives constraints, else
 * the plain one) and the texts of a batch are generated greedily as parallel sequences. The output of a text is limited to twice its tokens
 * and a fixed margin. A constrained prompt that does not leave a sequence room for that output falls back to the plain prompt.
 * <p>
 * The sequences of one generation share the context of the generator: the texts go in batch order, as many at once as the generator takes
 * and as fit the context together with their prompts and output limits; a text that does not fit alone is generated alone.
 */
public final class IndexTranslationModel implements TranslationModel {
    /**
     * The largest number of tokens of one input; the service splits longer texts at sentence boundaries. With its output limit and a prompt
     * it fits the shared context of {@link org.fogofwarlabs.sc4j.translate.service.llama.LlamaTextGeneratorFactory#CONTEXT_SIZE} tokens.
     */
    public static final int MAXIMUM_INPUT_TOKENS = 512;

    private static final int OUTPUT_TOKENS_PER_INPUT_TOKEN = 2;
    private static final int EXTRA_OUTPUT_TOKENS = 16;

    private final @NotNull TextGenerator generator;

    /**
     * Creates a {@link IndexTranslationModel} instance that owns a text generator.
     *
     * @param generator the generator of the loaded model, closed with this model
     */
    public IndexTranslationModel(@NotNull TextGenerator generator) {
        this.generator = generator;
    }

    /**
     * @return the largest number of tokens one input may have, as {@link #countTokens} counts them
     */
    @Override
    public int getMaximumInputTokens() {
        return MAXIMUM_INPUT_TOKENS;
    }

    /**
     * Counts the tokens of a text as {@link #translate} feeds it to the model, the prompt around it not included.
     *
     * @param text the text
     * @return the number of tokens
     */
    @Override
    public int countTokens(@NotNull String text) {
        return this.generator.countTokens(text);
    }

    /**
     * Translates a batch of texts that fit {@link #getMaximumInputTokens()}; a longer input whose prompt does not fit a sequence fails.
     *
     * @param inputs the texts with their languages and contexts
     * @return the translated texts, in the order of the inputs
     * @throws ModelException           if the inference fails
     * @throws IllegalArgumentException if the preset does not support a language
     */
    @Override
    public @NotNull List<@NotNull String> translate(@NotNull List<@NotNull TranslationInput> inputs) throws ModelException {
        List<String> prompts = new ArrayList<>(inputs.size());
        int[] outputLimits = new int[inputs.size()];
        int[] sequenceSizes = new int[inputs.size()];

        for (int index = 0; index < inputs.size(); index++) {
            TranslationInput input = inputs.get(index);
            int outputLimit = this.countTokens(input.getText()) * OUTPUT_TOKENS_PER_INPUT_TOKEN + EXTRA_OUTPUT_TOKENS;
            String prompt = IndexTranslatePromptUtils.createPrompt(input, true);
            int promptTokens = this.generator.countPromptTokens(prompt);

            if (input.getContext() != null && promptTokens + outputLimit > this.generator.getSequenceContextSize()) {
                prompt       = IndexTranslatePromptUtils.createPrompt(input, false);
                promptTokens = this.generator.countPromptTokens(prompt);
            }

            prompts.add(prompt);
            outputLimits[index]  = outputLimit;
            sequenceSizes[index] = promptTokens + outputLimit;
        }

        List<String> translations = new ArrayList<>(inputs.size());
        int start = 0;

        while (start < prompts.size()) {
            int end = start + 1;
            int maximumTokens = outputLimits[start];
            int size = sequenceSizes[start];

            while (end < prompts.size() && end - start < this.generator.getSequenceCount() &&
                   size + sequenceSizes[end] <= this.generator.getContextSize()) {
                maximumTokens = Math.max(maximumTokens, outputLimits[end]);
                size += sequenceSizes[end];
                end++;
            }

            for (String output : this.generator.generate(prompts.subList(start, end), maximumTokens)) {
                translations.add(IndexTranslatePromptUtils.cleanOutput(output));
            }

            start = end;
        }

        return translations;
    }

    /**
     * Releases the native resources.
     */
    @Override
    public void close() {
        this.generator.close();
    }
}
