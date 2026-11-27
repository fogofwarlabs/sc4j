package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.fogofwarlabs.sc4j.translate.service.model.SequenceModel;
import org.fogofwarlabs.sc4j.translate.service.model.TextTokenizer;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationInput;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Tests the NLLB translation model with a word tokenizer and a sequence model that repeats its input: the sentences it translates, the
 * sentences without letters it keeps, the input and prefix tokens of a batch, its length limit, and the outputs without the target language
 * token, cut to their own limits and joined in the way of the target language.
 */
final class NllbTranslationModelTest {
    private static final int OUTPUT_REPEATS = 30;
    private static final int MAXIMUM_SENTENCE_TOKENS = 512;
    private static final int LONG_TEXT_WORDS = 600;

    /**
     * Splits text at spaces and joins pieces with spaces, leaving out the pieces in angle brackets and the language codes.
     */
    private static final class WordTokenizer implements TextTokenizer {
        @Override
        public @NotNull String @NotNull [] encode(@NotNull String text) {
            String stripped = text.strip();

            return stripped.isEmpty() ? new String[0] : stripped.split(" +");
        }

        @Override
        public @NotNull String decode(@NotNull String @NotNull [] pieces) {
            return String.join(" ", Arrays.stream(pieces).filter(piece -> !piece.startsWith("<") && !piece.contains("_")).toList());
        }
    }

    /**
     * Answers each source with its prefix and its pieces repeated a number of times, cut to the length limit, and records what it receives.
     */
    private static final class RepeatingSequenceModel implements SequenceModel {
        private final int repeats;
        private final @NotNull List<@NotNull List<@NotNull String>> sources;
        private final @NotNull List<@NotNull List<@NotNull String>> targetPrefixes;
        private int maximumLength;
        private int calls;
        private boolean closed;

        private RepeatingSequenceModel(int repeats) {
            this.repeats        = repeats;
            this.sources        = new ArrayList<>();
            this.targetPrefixes = new ArrayList<>();
            this.maximumLength  = 0;
            this.calls          = 0;
            this.closed         = false;
        }

        @Override
        public @NotNull String @NotNull [] @NotNull [] generate(@NotNull String @NotNull [] @NotNull [] sources,
                                                                @NotNull String @NotNull [] @NotNull [] targetPrefixes,
                                                                int maximumLength) {
            String[][] outputs = new String[sources.length][];

            this.maximumLength = maximumLength;
            this.calls++;

            for (int index = 0; index < sources.length; index++) {
                List<String> output = new ArrayList<>(List.of(targetPrefixes[index]));
                List<String> pieces = Arrays.asList(sources[index]).subList(1, sources[index].length - 1);

                for (int repeat = 0; repeat < this.repeats; repeat++) {
                    output.addAll(pieces);
                }

                this.sources.add(List.of(sources[index]));
                this.targetPrefixes.add(List.of(targetPrefixes[index]));
                outputs[index] = output.subList(0, Math.min(output.size(), maximumLength)).toArray(new String[0]);
            }

            return outputs;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    @Test
    void translate_should_feed_language_and_end_tokens_and_cut_each_output_to_its_own_limit() throws Exception {
        RepeatingSequenceModel sequenceModel = new RepeatingSequenceModel(OUTPUT_REPEATS);
        int firstPieceWords = MAXIMUM_SENTENCE_TOKENS - 2;
        int secondPieceWords = LONG_TEXT_WORDS - firstPieceWords;
        List<String> shortSource = List.of("eng_Latn", "gg", "</s>");
        List<String> firstPiece = new ArrayList<>(List.of("kor_Hang"));
        List<String> secondPiece = new ArrayList<>(List.of("kor_Hang"));
        int shortLimit = shortSource.size() * 2 + 16;
        int firstLimit = MAXIMUM_SENTENCE_TOKENS * 2 + 16;
        int secondLimit = (secondPieceWords + 2) * 2 + 16;

        firstPiece.addAll(Collections.nCopies(firstPieceWords, "word"));
        firstPiece.add("</s>");
        secondPiece.addAll(Collections.nCopies(secondPieceWords, "word"));
        secondPiece.add("</s>");

        try (NllbTranslationModel model = new NllbTranslationModel(new WordTokenizer(), sequenceModel)) {
            List<String> translations = model.translate(List.of(new TranslationInput("gg", Locale.ENGLISH, Locale.KOREAN),
                                                                new TranslationInput("word ".repeat(LONG_TEXT_WORDS), Locale.KOREAN, Locale.JAPANESE)));

            Assertions.assertEquals(List.of(shortSource, firstPiece, secondPiece), sequenceModel.sources);
            Assertions.assertEquals(List.of(List.of("kor_Hang"), List.of("jpn_Jpan"), List.of("jpn_Jpan")), sequenceModel.targetPrefixes);
            Assertions.assertEquals(firstLimit, sequenceModel.maximumLength);
            Assertions.assertEquals(1, sequenceModel.calls, "the sentences of a batch are translated in one call");
            Assertions.assertEquals(String.join(" ", Collections.nCopies(shortLimit - 1, "gg")), translations.get(0));
            Assertions.assertEquals(String.join(" ", Collections.nCopies(firstLimit - 1, "word")) +
                                    String.join(" ", Collections.nCopies(secondLimit - 1, "word")), translations.get(1),
                                    "Japanese joins its pieces without a space");
            Assertions.assertEquals(shortSource.size(), model.countTokens("gg"));
            Assertions.assertEquals(Integer.MAX_VALUE, model.getMaximumInputTokens());
        }

        Assertions.assertTrue(sequenceModel.closed);
    }

    @Test
    void translate_should_translate_sentence_by_sentence_and_keep_sentences_without_letters() throws Exception {
        RepeatingSequenceModel sequenceModel = new RepeatingSequenceModel(1);

        try (NllbTranslationModel model = new NllbTranslationModel(new WordTokenizer(), sequenceModel)) {
            List<String> translations = model.translate(List.of(new TranslationInput("Good game. Want a rematch?  Sure!", Locale.ENGLISH, Locale.KOREAN),
                                                                new TranslationInput("Hello! 123! Bye!", Locale.ENGLISH, Locale.CHINESE)));

            Assertions.assertEquals(List.of(List.of("eng_Latn", "Good", "game.", "</s>"), List.of("eng_Latn", "Want", "a", "rematch?", "</s>"),
                                            List.of("eng_Latn", "Sure!", "</s>"), List.of("eng_Latn", "Hello!", "</s>"),
                                            List.of("eng_Latn", "Bye!", "</s>")), sequenceModel.sources);
            Assertions.assertEquals(List.of("Good game. Want a rematch? Sure!", "Hello!123!Bye!"), translations);
        }
    }
}
