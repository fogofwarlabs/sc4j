package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Splits texts into the pieces a model translates and joins the translated pieces. {@link #split} gives the sentences of a text, a sentence
 * longer than a token limit split at word boundaries and a word longer than the limit at character boundaries; {@link #chunk} joins those
 * pieces again while they fit the limit; {@link #join} joins translated pieces in the way of their language.
 */
public final class TextSplitter {
    private static final @NotNull String SPACE = " ";
    /**
     * The languages written without spaces between sentences.
     */
    private static final @NotNull Set<@NotNull String> UNSPACED_LANGUAGES = Set.of("ja", "zh", "yue", "th", "lo", "km", "my");

    /**
     * Splits a text into sentences whose token counts fit the limit. The pieces keep the text's characters in order, white space included.
     *
     * @param text          the text
     * @param language      the language of the text, which decides the sentence and word boundaries
     * @param tokenCounter  counts the tokens of a piece
     * @param maximumTokens the largest token count of a piece
     * @return the pieces, in text order
     */
    public static @NotNull List<@NotNull String> split(@NotNull String text, @NotNull Locale language,
                                                       @NotNull ToIntFunction<@NotNull String> tokenCounter, int maximumTokens) {
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
     * Splits a text into chunks whose token counts fit the limit: the whole text when it fits, else its {@link #split} pieces, each joined
     * with the pieces after it while the joined text fits. The chunks keep the text's characters in order, white space included.
     *
     * @param text          the text
     * @param language      the language of the text, which decides the sentence and word boundaries
     * @param tokenCounter  counts the tokens of a chunk
     * @param maximumTokens the largest token count of a chunk
     * @return the chunks, in text order
     */
    public static @NotNull List<@NotNull String> chunk(@NotNull String text, @NotNull Locale language,
                                                       @NotNull ToIntFunction<@NotNull String> tokenCounter, int maximumTokens) {
        if (tokenCounter.applyAsInt(text) <= maximumTokens) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String piece : split(text, language, tokenCounter, maximumTokens)) {
            if (!current.isEmpty() && tokenCounter.applyAsInt(current + piece) > maximumTokens) {
                chunks.add(current.toString());
                current.setLength(0);
            }

            current.append(piece);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    /**
     * Joins the translated pieces of a text: the pieces that are not empty, separated by a space, or by nothing in a language written
     * without spaces between sentences.
     *
     * @param pieces   the translated pieces, in text order
     * @param language the language of the translation
     * @return the joined text
     */
    public static @NotNull String join(@NotNull List<@NotNull String> pieces, @NotNull Locale language) {
        String separator = UNSPACED_LANGUAGES.contains(language.getLanguage()) ? "" : SPACE;
        StringBuilder joined = new StringBuilder();

        for (String piece : pieces) {
            if (!joined.isEmpty() && !piece.isEmpty()) {
                joined.append(separator);
            }

            joined.append(piece);
        }

        return joined.toString();
    }

    /**
     * Tells whether a text has a letter, which makes it worth translating.
     *
     * @param text the text
     * @return {@code true} if the text has a letter, {@code false} otherwise
     */
    public static boolean hasLetters(@NotNull String text) {
        return text.codePoints().anyMatch(Character::isLetter);
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
