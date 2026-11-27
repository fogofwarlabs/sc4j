package org.fogofwarlabs.sc4j.translate.service;

import org.jetbrains.annotations.NotNull;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * Splits a text into the pieces a sentence-level model translates one by one: its sentences, a sentence longer than the model's input limit
 * split at word boundaries, and a word longer than the limit at character boundaries.
 */
final class TextSplitter {
    /**
     * Splits a text into sentences whose token counts fit the limit. The pieces keep the text's characters in order, white space included.
     *
     * @param text          the text
     * @param language      the language of the text, which decides the sentence and word boundaries
     * @param tokenCounter  counts the tokens of a piece
     * @param maximumTokens the largest token count of a piece
     * @return the pieces, in text order
     */
    static @NotNull List<@NotNull String> split(@NotNull String text, @NotNull Locale language, @NotNull ToIntFunction<@NotNull String> tokenCounter,
                                                int maximumTokens) {
        List<String> pieces = new ArrayList<>();

        for (String sentence : splitAt(text, BreakIterator.getSentenceInstance(language))) {
            if (tokenCounter.applyAsInt(sentence) <= maximumTokens) {
                pieces.add(sentence);
            } else {
                splitLongSentence(sentence, language, tokenCounter, maximumTokens, pieces);
            }
        }

        return pieces;
    }

    /**
     * Splits a sentence that does not fit at word boundaries, joining words while they fit, and a word that does not fit at character
     * boundaries.
     */
    private static void splitLongSentence(@NotNull String sentence, @NotNull Locale language, @NotNull ToIntFunction<@NotNull String> tokenCounter,
                                          int maximumTokens, @NotNull List<@NotNull String> pieces) {
        StringBuilder current = new StringBuilder();

        for (String word : splitAt(sentence, BreakIterator.getWordInstance(language))) {
            List<String> parts = tokenCounter.applyAsInt(word) <= maximumTokens ? List.of(word)
                                                                                : splitAt(word, BreakIterator.getCharacterInstance(language));

            for (String part : parts) {
                if (!current.isEmpty() && tokenCounter.applyAsInt(current + part) > maximumTokens) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }

                current.append(part);
            }
        }

        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
    }

    private static @NotNull List<@NotNull String> splitAt(@NotNull String text, @NotNull BreakIterator boundaries) {
        List<String> parts = new ArrayList<>();

        boundaries.setText(text);

        for (int start = boundaries.first(), end = boundaries.next(); end != BreakIterator.DONE; start = end, end = boundaries.next()) {
            parts.add(text.substring(start, end));
        }

        return parts;
    }

    private TextSplitter() {
    }
}
