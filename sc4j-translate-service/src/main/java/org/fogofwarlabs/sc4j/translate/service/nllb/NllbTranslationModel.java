package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Translates with an NLLB model: the input of a text is its source language token, its SentencePiece pieces and the end token; the output
 * starts with the target language token, which is removed before the pieces are joined to text. The language tokens are the FLORES-200
 * codes, such as {@code kor_Hang}.
 */
public final class NllbTranslationModel implements TranslationModel {
    /**
     * The token that ends a sequence.
     */
    public static final @NotNull String END_TOKEN = "</s>";

    private static final int MAXIMUM_INPUT_TOKENS = 512;
    /**
     * The output length allowed per input token, and the output tokens allowed beyond that, which end a greedy decoding that repeats itself.
     */
    private static final int OUTPUT_TOKENS_PER_INPUT_TOKEN = 2;
    private static final int EXTRA_OUTPUT_TOKENS = 16;
    private static final int LANGUAGE_AND_END_TOKENS = 2;

    private static @NotNull String findLanguageToken(@NotNull Locale language) {
        String code = NllbLanguages.findCode(language);

        if (code == null) {
            throw Exceptions.newIllegalArgumentException("NLLB does not support the language {0}", language);
        }

        return code;
    }


    private final @NotNull TextTokenizer tokenizer;
    private final @NotNull SequenceModel sequenceModel;

    /**
     * Creates a {@link NllbTranslationModel} instance that owns a sequence model.
     *
     * @param tokenizer     the SentencePiece tokenizer of the model
     * @param sequenceModel the encoder and decoder, closed with this model
     */
    public NllbTranslationModel(@NotNull TextTokenizer tokenizer, @NotNull SequenceModel sequenceModel) {
        this.tokenizer     = tokenizer;
        this.sequenceModel = sequenceModel;
    }

    /**
     * @return the largest number of tokens one input may have, language and end tokens included
     */
    @Override
    public int getMaximumInputTokens() {
        return MAXIMUM_INPUT_TOKENS;
    }

    /**
     * Counts the tokens of a text as {@link #translate} feeds it to the model, language and end tokens included.
     *
     * @param text the text
     * @return the number of tokens
     */
    @Override
    public int countTokens(@NotNull String text) {
        return this.tokenizer.encode(text).length + LANGUAGE_AND_END_TOKENS;
    }

    /**
     * Translates a batch of texts that fit {@link #getMaximumInputTokens()}; longer input is cut there.
     * <p>
     * The output of a text is limited to twice its input tokens and a fixed margin, the target language token included.
     *
     * @param inputs the texts with their languages
     * @return the translated texts, in the order of the inputs
     * @throws ModelException           if the inference fails
     * @throws IllegalArgumentException if the preset does not support a language
     */
    @Override
    public @NotNull List<@NotNull String> translate(@NotNull List<@NotNull TranslationInput> inputs) throws ModelException {
        String[][] sources = new String[inputs.size()][];
        String[][] targetPrefixes = new String[inputs.size()][];
        int[] outputLimits = new int[inputs.size()];
        int maximumLength = 0;

        for (int index = 0; index < sources.length; index++) {
            TranslationInput input = inputs.get(index);
            String sourceToken = findLanguageToken(input.getSource());
            String targetToken = findLanguageToken(input.getTarget());
            String[] pieces = this.tokenizer.encode(input.getText());
            int pieceCount = Math.min(pieces.length, MAXIMUM_INPUT_TOKENS - LANGUAGE_AND_END_TOKENS);
            String[] source = new String[pieceCount + LANGUAGE_AND_END_TOKENS];

            source[0] = sourceToken;
            System.arraycopy(pieces, 0, source, 1, pieceCount);
            source[pieceCount + 1] = END_TOKEN;

            sources[index]        = source;
            targetPrefixes[index] = new String[]{targetToken};
            outputLimits[index]   = source.length * OUTPUT_TOKENS_PER_INPUT_TOKEN + EXTRA_OUTPUT_TOKENS;

            maximumLength = Math.max(maximumLength, outputLimits[index]);
        }

        String[][] outputs = this.sequenceModel.generate(sources, targetPrefixes, maximumLength);
        List<String> translations = new ArrayList<>(outputs.length);

        // The batch decodes to the longest limit; each output is cut to its own, as if it were decoded alone.
        for (int index = 0; index < outputs.length; index++) {
            String[] output = outputs[index];
            int start = output.length > 0 && output[0].equals(targetPrefixes[index][0]) ? 1 : 0;

            translations.add(this.tokenizer.decode(Arrays.copyOfRange(output, start, Math.max(start, Math.min(output.length, outputLimits[index])))));
        }

        return translations;
    }

    /**
     * Releases the native resources.
     */
    @Override
    public void close() {
        this.sequenceModel.close();
    }
}
