package org.fogofwarlabs.sc4j.translate.service.indextranslate;

import org.fogofwarlabs.sc4j.translate.TranslationContext;
import org.fogofwarlabs.sc4j.translate.service.llama.TextGenerator;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationInput;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tests the Index-Translate model with a stand-in generator whose tokens are characters: the plain and the constrained prompts, the
 * glossary entries a prompt takes, the fallback to the plain prompt, the groups of parallel sequences that fit the shared context, their
 * output limits and the cleaned outputs.
 */
final class IndexTranslationModelTest {
    private static final int LARGE_SEQUENCE_SIZE = 4096;
    private static final @NotNull String CHAT_START = "<|im_start|>user\n";
    private static final @NotNull String CHAT_END = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";

    /**
     * Records the prompts and limits of each call and answers each prompt with its number in the call, its tokens are its characters. Its
     * sequences share the whole context.
     */
    private static final class StandInGenerator implements TextGenerator {
        private final int sequenceCount;
        private final int contextSize;
        private final @NotNull List<@NotNull List<@NotNull String>> calls;
        private final @NotNull List<@NotNull Integer> limits;
        private boolean closed;

        private StandInGenerator(int sequenceCount, int contextSize) {
            this.sequenceCount = sequenceCount;
            this.contextSize   = contextSize;
            this.calls         = new ArrayList<>();
            this.limits        = new ArrayList<>();
            this.closed        = false;
        }

        @Override
        public int getSequenceCount() {
            return this.sequenceCount;
        }

        @Override
        public int getContextSize() {
            return this.contextSize;
        }

        @Override
        public int getSequenceContextSize() {
            return this.contextSize;
        }

        @Override
        public int countTokens(@NotNull String text) {
            return text.length();
        }

        @Override
        public int countPromptTokens(@NotNull String prompt) {
            return prompt.length();
        }

        @Override
        public @NotNull List<@NotNull String> generate(@NotNull List<@NotNull String> prompts, int maximumTokens) {
            List<String> outputs = new ArrayList<>();

            this.calls.add(List.copyOf(prompts));
            this.limits.add(maximumTokens);

            for (int index = 0; index < prompts.size(); index++) {
                outputs.add(" answer " + this.calls.size() + "." + index + "\n");
            }

            return outputs;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private static @NotNull String translateOne(@NotNull TranslationInput input, int contextSize) throws Exception {
        StandInGenerator generator = new StandInGenerator(1, contextSize);

        try (IndexTranslationModel model = new IndexTranslationModel(generator)) {
            model.translate(List.of(input));
        }

        return generator.calls.getFirst().getFirst();
    }

    @Test
    void translate_should_use_the_plain_prompt_without_context_or_constraints() throws Exception {
        String expected = CHAT_START + "请将以下韩语文本翻译为英语，直接输出翻译结果，不要进行任何解释：\n\n안녕하세요" + CHAT_END;
        TranslationContext empty = new TranslationContext.Builder().build();
        TranslationContext kindOnly = new TranslationContext.Builder().setTextKind("聊天消息").addGlossaryEntry("성", Locale.ENGLISH, "castle").build();

        Assertions.assertEquals(expected, translateOne(new TranslationInput("안녕하세요", Locale.KOREAN, Locale.ENGLISH), LARGE_SEQUENCE_SIZE));
        Assertions.assertEquals(expected, translateOne(new TranslationInput("안녕하세요", Locale.KOREAN, Locale.ENGLISH, empty), LARGE_SEQUENCE_SIZE));
        Assertions.assertEquals(expected, translateOne(new TranslationInput("안녕하세요", Locale.KOREAN, Locale.ENGLISH, kindOnly), LARGE_SEQUENCE_SIZE),
                                "a context whose glossary has no term of the text and that has no description gives no constraint");
    }

    @Test
    void translate_should_use_the_constrained_prompt_with_the_glossary_terms_of_the_text_and_target() throws Exception {
        TranslationContext context = new TranslationContext.Builder().setTextKind("聊天消息")
                                                                     .setDescription("Short messages between players.\n\nKeep the casual tone.")
                                                                     .addGlossaryEntry("tower", Locale.KOREAN, "타워")
                                                                     .addGlossaryEntry("Wall", Locale.KOREAN, "성벽")
                                                                     .addGlossaryEntry("gate", Locale.KOREAN, "성문")
                                                                     .addGlossaryEntry("tower", Locale.GERMAN, "Turm")
                                                                     .build();
        String expected = CHAT_START + "请将以下英语聊天消息翻译成韩语，并且严格遵循所有约束要求。\n\n" +
                          "【源文】\nBuild a TOWER near the wall.\n\n" +
                          "【约束要求】\n" +
                          "1. 【硬性要求】专名/术语对照: Wall→성벽、tower→타워\n" +
                          "2. 【注意】Short messages between players.\n" +
                          "3. 【注意】Keep the casual tone.\n\n" +
                          "只输出译文，不要有任何额外说明。" + CHAT_END;

        Assertions.assertEquals(expected, translateOne(new TranslationInput("Build a TOWER near the wall.", Locale.ENGLISH, Locale.KOREA, context),
                                                       LARGE_SEQUENCE_SIZE));
    }

    @Test
    void translate_should_use_the_default_genre_and_fall_back_to_the_plain_prompt_when_the_constrained_one_does_not_fit() throws Exception {
        TranslationContext context = new TranslationContext.Builder().setDescription("A note.").build();
        TranslationInput input = new TranslationInput("Hello", Locale.ENGLISH, Locale.JAPANESE, context);
        String constrained = translateOne(input, LARGE_SEQUENCE_SIZE);
        String plain = CHAT_START + "请将以下英语文本翻译为日语，直接输出翻译结果，不要进行任何解释：\n\nHello" + CHAT_END;
        int outputLimit = "Hello".length() * 2 + 16;

        Assertions.assertTrue(constrained.startsWith(CHAT_START + "请将以下英语文本翻译成日语，并且严格遵循所有约束要求。"), constrained);
        Assertions.assertEquals(constrained, translateOne(input, constrained.length() + outputLimit));
        Assertions.assertEquals(plain, translateOne(input, constrained.length() + outputLimit - 1));
    }

    @Test
    void translate_should_generate_in_groups_of_parallel_sequences_and_clean_the_outputs() throws Exception {
        StandInGenerator generator = new StandInGenerator(2, LARGE_SEQUENCE_SIZE);
        List<TranslationInput> inputs = List.of(new TranslationInput("a", Locale.ENGLISH, Locale.KOREAN),
                                                new TranslationInput("abcdef", Locale.ENGLISH, Locale.KOREAN),
                                                new TranslationInput("abc", Locale.ENGLISH, Locale.KOREAN));
        List<String> translations;

        try (IndexTranslationModel model = new IndexTranslationModel(generator)) {
            translations = model.translate(inputs);

            Assertions.assertEquals(IndexTranslationModel.MAXIMUM_INPUT_TOKENS, model.getMaximumInputTokens());
            Assertions.assertEquals(5, model.countTokens("abcde"));
        }

        Assertions.assertEquals(List.of("answer 1.0", "answer 1.1", "answer 2.0"), translations);
        Assertions.assertEquals(List.of(2, 1), generator.calls.stream().map(List::size).toList());
        Assertions.assertEquals(List.of(6 * 2 + 16, 3 * 2 + 16), generator.limits);
        Assertions.assertTrue(generator.closed);
        Assertions.assertEquals("translation", IndexTranslatePromptUtils.cleanOutput("<think>\nthinking\n</think>\n\n translation \n"));
        Assertions.assertEquals("translation", IndexTranslatePromptUtils.cleanOutput("<think> translation"));
    }

    @Test
    void translate_should_generate_together_only_the_texts_that_fit_the_shared_context() throws Exception {
        List<TranslationInput> inputs = List.of(new TranslationInput("a".repeat(100), Locale.ENGLISH, Locale.KOREAN),
                                                new TranslationInput("b".repeat(100), Locale.ENGLISH, Locale.KOREAN),
                                                new TranslationInput("c".repeat(620), Locale.ENGLISH, Locale.KOREAN),
                                                new TranslationInput("d", Locale.ENGLISH, Locale.KOREAN));
        StandInGenerator generator = new StandInGenerator(4, 2048);

        try (IndexTranslationModel model = new IndexTranslationModel(generator)) {
            Assertions.assertEquals(4, model.translate(inputs).size());
        }

        Assertions.assertEquals(List.of(2, 1, 1), generator.calls.stream().map(List::size).toList(),
                                "the long text does not fit beside the first two, and the last one does not fit beside it");
        Assertions.assertEquals(List.of(100 * 2 + 16, 620 * 2 + 16, 18), generator.limits);
    }

    @Test
    void translate_should_refuse_a_language_the_model_does_not_have() {
        StandInGenerator generator = new StandInGenerator(1, LARGE_SEQUENCE_SIZE);

        try (IndexTranslationModel model = new IndexTranslationModel(generator)) {
            Assertions.assertThrows(IllegalArgumentException.class,
                                    () -> model.translate(List.of(new TranslationInput("hello", Locale.ENGLISH, Locale.forLanguageTag("sw")))));
        }
    }
}
