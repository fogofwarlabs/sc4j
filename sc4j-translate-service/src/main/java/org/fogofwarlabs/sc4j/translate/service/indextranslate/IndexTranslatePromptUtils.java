package org.fogofwarlabs.sc4j.translate.service.indextranslate;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.translate.GlossaryEntry;
import org.fogofwarlabs.sc4j.translate.TranslationContext;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationInput;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds the prompts of Index-Translate in the chat format of the model with thinking turned off, and cleans its answers.
 * <p>
 * A text without context, or with a context that gives no constraint, gets the plain translation instruction. A text whose context gives
 * constraints gets the instruction format the model was trained on for constrained translation: the kind of text as the genre (else
 * {@code 文本}), then as numbered constraints the glossary entries of the target language whose source term occurs in the text, ignoring
 * case, as one terminology constraint, and each line of the description as a note.
 */
final class IndexTranslatePromptUtils {
    private static final @NotNull String CHAT_START = "<|im_start|>user\n";
    private static final @NotNull String CHAT_END = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
    private static final @NotNull String THINK_START = "<think>";
    private static final @NotNull String THINK_END = "</think>";

    private static final @NotNull String PLAIN_START = "请将以下";
    private static final @NotNull String PLAIN_MIDDLE = "文本翻译为";
    private static final @NotNull String PLAIN_END = "，直接输出翻译结果，不要进行任何解释：\n\n";

    private static final @NotNull String CONSTRAINED_START = "请将以下";
    private static final @NotNull String CONSTRAINED_MIDDLE = "翻译成";
    private static final @NotNull String CONSTRAINED_END = "，并且严格遵循所有约束要求。";
    private static final @NotNull String DEFAULT_GENRE = "文本";
    private static final @NotNull String SOURCE_HEADING = "【源文】";
    private static final @NotNull String CONSTRAINTS_HEADING = "【约束要求】";
    private static final @NotNull String TERMINOLOGY_CONSTRAINT = "【硬性要求】专名/术语对照: ";
    private static final @NotNull String NOTE_CONSTRAINT = "【注意】";
    private static final @NotNull String CONSTRAINED_FOOTER = "只输出译文，不要有任何额外说明。";
    private static final @NotNull String TERM_ARROW = "→";
    private static final @NotNull String TERM_SEPARATOR = "、";
    private static final @NotNull String NUMBER_SEPARATOR = ". ";
    private static final @NotNull String PARAGRAPH = "\n\n";
    private static final char LINE = '\n';

    /**
     * Builds the prompt of a text: the constrained instruction when its context gives constraints and the context may be used, else the
     * plain instruction.
     *
     * @param input       the text with its languages and context
     * @param withContext whether the context may be used
     * @return the prompt in the chat format of the model
     * @throws IllegalArgumentException if the model does not have a language of the input
     */
    static @NotNull String createPrompt(@NotNull TranslationInput input, boolean withContext) {
        String sourceName = requireName(input.getSource());
        String targetName = requireName(input.getTarget());
        TranslationContext context = withContext ? input.getContext() : null;
        List<String> constraints = context == null ? List.of() : createConstraints(context, input.getText(), input.getTarget());
        StringBuilder message = new StringBuilder(CHAT_START);

        if (context == null || constraints.isEmpty()) {
            message.append(PLAIN_START).append(sourceName).append(PLAIN_MIDDLE).append(targetName).append(PLAIN_END).append(input.getText());
        } else {
            String genre = context.getTextKind().isBlank() ? DEFAULT_GENRE : context.getTextKind().strip();

            message.append(CONSTRAINED_START).append(sourceName).append(genre).append(CONSTRAINED_MIDDLE).append(targetName).append(CONSTRAINED_END);
            message.append(PARAGRAPH).append(SOURCE_HEADING).append(LINE).append(input.getText().strip());
            message.append(PARAGRAPH).append(CONSTRAINTS_HEADING);

            for (int index = 0; index < constraints.size(); index++) {
                message.append(LINE).append(index + 1).append(NUMBER_SEPARATOR).append(constraints.get(index));
            }

            message.append(PARAGRAPH).append(CONSTRAINED_FOOTER);
        }

        return message.append(CHAT_END).toString();
    }

    /**
     * Finds the glossary entries of a context for a text: the entries of the target language whose source term occurs in the text, ignoring
     * case.
     *
     * @param context the context
     * @param text    the text
     * @param target  the target language
     * @return the entries, in the order of the context
     */
    private static @NotNull List<@NotNull GlossaryEntry> findGlossaryEntries(@NotNull TranslationContext context,
                                                                             @NotNull String text, @NotNull Locale target) {
        String targetCode = IndexTranslateLanguages.findCode(target);
        String lowerText = text.toLowerCase(Locale.ROOT);
        List<GlossaryEntry> entries = new ArrayList<>();

        for (GlossaryEntry entry : context.getGlossary()) {
            if (Objects.equals(IndexTranslateLanguages.findCode(entry.getTarget()), targetCode) &&
                lowerText.contains(entry.getSourceTerm().toLowerCase(Locale.ROOT))) {
                entries.add(entry);
            }
        }

        return entries;
    }

    /**
     * Cleans a generated text: a thinking part that the model writes despite the prompt, up to its end, and the white space around the
     * translation are removed.
     *
     * @param output the generated text
     * @return the translation
     */
    static @NotNull String cleanOutput(@NotNull String output) {
        int thinkEnd = output.indexOf(THINK_END);
        String answer = (thinkEnd < 0 ? output : output.substring(thinkEnd + THINK_END.length())).strip();

        return answer.startsWith(THINK_START) ? answer.substring(THINK_START.length()).strip() : answer;
    }

    private static @NotNull List<@NotNull String> createConstraints(@NotNull TranslationContext context,
                                                                    @NotNull String text, @NotNull Locale target) {
        List<String> constraints = new ArrayList<>();
        List<GlossaryEntry> entries = findGlossaryEntries(context, text, target);

        if (!entries.isEmpty()) {
            List<String> terms = new ArrayList<>(entries.size());

            for (GlossaryEntry entry : entries) {
                terms.add(entry.getSourceTerm() + TERM_ARROW + entry.getTargetTerm());
            }

            constraints.add(TERMINOLOGY_CONSTRAINT + String.join(TERM_SEPARATOR, terms));
        }

        for (String line : context.getDescription().split(String.valueOf(LINE))) {
            if (!line.isBlank()) {
                constraints.add(NOTE_CONSTRAINT + line.strip());
            }
        }

        return constraints;
    }

    private static @NotNull String requireName(@NotNull Locale language) {
        String name = IndexTranslateLanguages.findName(language);

        if (name == null) {
            throw Exceptions.newIllegalArgumentException("Index-Translate does not support the language {0}", language);
        }

        return name;
    }

    private IndexTranslatePromptUtils() {
    }
}
