package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.fogofwarlabs.sc4j.translate.service.model.SequenceModel;
import org.fogofwarlabs.sc4j.translate.service.model.TextTokenizer;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationInput;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Tests the NLLB translation model with a word tokenizer and a sequence model that repeats its input: the input and prefix tokens of a
 * batch, its length limit, and the outputs without the target language token, cut to their own limits.
 */
final class NllbTranslationModelTest {
    private static final int OUTPUT_REPEATS = 30;
    private static final int MAXIMUM_INPUT_TOKENS = 512;
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
     * Answers each source with its prefix and its pieces repeated {@link #OUTPUT_REPEATS} times, cut to the length limit, and records what
     * it receives.
     */
    private static final class RepeatingSequenceModel implements SequenceModel {
        private final @NotNull List<@NotNull List<@NotNull String>> sources;
        private final @NotNull List<@NotNull List<@NotNull String>> targetPrefixes;
        private int maximumLength;
        private boolean closed;

        private RepeatingSequenceModel() {
            this.sources        = new ArrayList<>();
            this.targetPrefixes = new ArrayList<>();
            this.maximumLength  = 0;
            this.closed         = false;
        }

        @Override
        public @NotNull String @NotNull [] @NotNull [] generate(@NotNull String @NotNull [] @NotNull [] sources,
                                                                @NotNull String @NotNull [] @NotNull [] targetPrefixes,
                                                                int maximumLength) {
            String[][] outputs = new String[sources.length][];

            this.maximumLength = maximumLength;

            for (int index = 0; index < sources.length; index++) {
                List<String> output = new ArrayList<>(List.of(targetPrefixes[index]));
                List<String> pieces = Arrays.asList(sources[index]).subList(1, sources[index].length - 1);

                for (int repeat = 0; repeat < OUTPUT_REPEATS; repeat++) {
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
        RepeatingSequenceModel sequenceModel = new RepeatingSequenceModel();
        List<String> shortSource = List.of("eng_Latn", "gg", "</s>");
        List<String> longSource = new ArrayList<>(List.of("kor_Hang"));
        int shortLimit = shortSource.size() * 2 + 16;
        int longLimit = MAXIMUM_INPUT_TOKENS * 2 + 16;

        longSource.addAll(Collections.nCopies(MAXIMUM_INPUT_TOKENS - 2, "word"));
        longSource.add("</s>");

        try (NllbTranslationModel model = new NllbTranslationModel(new WordTokenizer(), sequenceModel)) {
            List<String> translations = model.translate(List.of(new TranslationInput("gg", Locale.ENGLISH, Locale.KOREAN),
                                                                new TranslationInput("word ".repeat(LONG_TEXT_WORDS), Locale.KOREAN, Locale.JAPANESE)));

            Assertions.assertEquals(List.of(shortSource, longSource), sequenceModel.sources);
            Assertions.assertEquals(List.of(List.of("kor_Hang"), List.of("jpn_Jpan")), sequenceModel.targetPrefixes);
            Assertions.assertEquals(longLimit, sequenceModel.maximumLength);
            Assertions.assertEquals(String.join(" ", Collections.nCopies(shortLimit - 1, "gg")), translations.get(0));
            Assertions.assertEquals(String.join(" ", Collections.nCopies(longLimit - 1, "word")), translations.get(1));
            Assertions.assertEquals(shortSource.size(), model.countTokens("gg"));
        }

        Assertions.assertTrue(sequenceModel.closed);
    }
}
