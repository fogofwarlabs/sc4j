package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Translates with an NLLB model, which translates sentence by sentence: a text is split into its sentences, a sentence longer than the
 * model's input limit in pieces ({@link TextSplitter#split}); the sentences of all texts of a batch are translated in one call, a sentence
 * without letters stays as it is, and the translated sentences of a text are joined in the way of the target language
 * ({@link TextSplitter#join}).
 * <p>
 * The input of a sentence is its source language token, its SentencePiece pieces and the end token; the output starts with the target
 * language token, which is removed before the pieces are joined to text. The language tokens are the FLORES-200 codes, such as
 * {@code kor_Hang}.
 */
public final class NllbTranslationModel implements TranslationModel {
    /**
     * The token that ends a sequence.
     */
    public static final @NotNull String END_TOKEN = "</s>";

    /**
     * The largest number of tokens of one sentence the model reads, language and end tokens included.
     */
    private static final int MAXIMUM_SENTENCE_TOKENS = 512;
    /**
     * The output length allowed per input token, and the output tokens allowed beyond that, which end a greedy decoding that repeats itself.
     */
    private static final int OUTPUT_TOKENS_PER_INPUT_TOKEN = 2;
    private static final int EXTRA_OUTPUT_TOKENS = 16;
    private static final int LANGUAGE_AND_END_TOKENS = 2;
    /**
     * The model input index of a sentence without letters, which stays as it is.
     */
    private static final int KEPT_SENTENCE = -1;

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
     * @return the largest number of tokens one input may have, as {@link #countTokens} counts them: no limit, since the model splits its
     *         inputs into sentences
     */
    @Override
    public int getMaximumInputTokens() {
        return Integer.MAX_VALUE;
    }

    /**
     * Counts the tokens of a text as {@link #translate} feeds it to the model, language and end tokens included, as if it were one sentence.
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
     * The texts are translated sentence by sentence; the context is not used.
     *
     * @param inputs the texts with their languages and contexts
     * @return the translated texts, in the order of the inputs
     * @throws ModelException           if the inference fails
     * @throws IllegalArgumentException if the preset does not support a language
     */
    @Override
    public @NotNull List<@NotNull String> translate(@NotNull List<@NotNull TranslationInput> inputs) throws ModelException {
        List<List<String>> sentences = new ArrayList<>(inputs.size());
        List<List<Integer>> sentenceIndexes = new ArrayList<>(inputs.size());
        List<TranslationInput> sentenceInputs = new ArrayList<>();

        for (TranslationInput input : inputs) {
            List<String> inputSentences = new ArrayList<>();
            List<Integer> inputIndexes = new ArrayList<>();

            for (String piece : TextSplitter.split(input.getText(), input.getSource(), this::countTokens, MAXIMUM_SENTENCE_TOKENS)) {
                String sentence = piece.strip();

                if (sentence.isEmpty()) {
                    continue;
                }

                inputSentences.add(sentence);

                if (TextSplitter.hasLetters(sentence)) {
                    inputIndexes.add(sentenceInputs.size());
                    sentenceInputs.add(new TranslationInput(sentence, input.getSource(), input.getTarget()));
                } else {
                    inputIndexes.add(KEPT_SENTENCE);
                }
            }

            sentences.add(inputSentences);
            sentenceIndexes.add(inputIndexes);
        }

        List<String> outputs = sentenceInputs.isEmpty() ? List.of() : this.translateSentences(sentenceInputs);
        List<String> translations = new ArrayList<>(inputs.size());

        for (int index = 0; index < inputs.size(); index++) {
            List<String> pieces = new ArrayList<>();

            for (int sentence = 0; sentence < sentences.get(index).size(); sentence++) {
                int outputIndex = sentenceIndexes.get(index).get(sentence);

                pieces.add(outputIndex == KEPT_SENTENCE ? sentences.get(index).get(sentence) : outputs.get(outputIndex));
            }

            translations.add(TextSplitter.join(pieces, inputs.get(index).getTarget()));
        }

        return translations;
    }

    /**
     * Translates a batch of sentences in one call of the sequence model; a sentence longer than {@link #MAXIMUM_SENTENCE_TOKENS} is cut
     * there. The output of a sentence is limited to twice its input tokens and a fixed margin, the target language token included.
     */
    private @NotNull List<@NotNull String> translateSentences(@NotNull List<@NotNull TranslationInput> inputs) throws ModelException {
        String[][] sources = new String[inputs.size()][];
        String[][] targetPrefixes = new String[inputs.size()][];
        int[] outputLimits = new int[inputs.size()];
        int maximumLength = 0;

        for (int index = 0; index < sources.length; index++) {
            TranslationInput input = inputs.get(index);
            String sourceToken = findLanguageToken(input.getSource());
            String targetToken = findLanguageToken(input.getTarget());
            String[] pieces = this.tokenizer.encode(input.getText());
            int pieceCount = Math.min(pieces.length, MAXIMUM_SENTENCE_TOKENS - LANGUAGE_AND_END_TOKENS);
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
