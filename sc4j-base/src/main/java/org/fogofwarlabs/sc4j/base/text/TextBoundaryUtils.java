package org.fogofwarlabs.sc4j.base.text;

import org.jetbrains.annotations.NotNull;

import java.util.function.ToDoubleFunction;

/**
 * Finds the boundaries a text selection snaps to: the code point boundary nearest a pointer position on a drawn line and the ends of the
 * word around an index. Text inputs and read-only logs ({@link LogSelectionModel}) share them; the measure of a text comes from the owner's
 * font.
 */
public final class TextBoundaryUtils {
    private static final float HALF = 2;

    /**
     * Groups the characters a word selection keeps together.
     */
    private enum CharacterClass {
        WORD,
        SPACE,
        OTHER
    }

    private TextBoundaryUtils() {
    }

    /**
     * Finds the code point boundary of a drawn line nearest a horizontal position: a position left of a character's middle falls before the
     * character, a position right of it after.
     *
     * @param text    the drawn line
     * @param x       the position from the line's left edge
     * @param measure the width of a text in the line's font
     * @return the UTF-16 index of the boundary, from {@code 0} to the text's length
     */
    public static int findIndexAt(@NotNull String text, float x, @NotNull ToDoubleFunction<String> measure) {
        if (x <= 0) {
            return 0;
        }

        int index = 0;
        float left = 0;

        while (index < text.length()) {
            int next = text.offsetByCodePoints(index, 1);
            float right = (float) measure.applyAsDouble(text.substring(0, next));

            if (x < (left + right) / HALF) {
                return index;
            }

            index = next;
            left  = right;
        }

        return text.length();
    }

    /**
     * Finds the start of the word around an index: the run of letters and digits, of white space or of other characters that holds the
     * character at the index, or the one before it at the text's end.
     *
     * @param text  the text
     * @param index a UTF-16 index of the text on a code point boundary
     * @return the UTF-16 index of the run's first character
     */
    public static int findWordStart(@NotNull String text, int index) {
        if (text.isEmpty()) {
            return 0;
        }

        int start = Math.min(index, text.length());
        CharacterClass kind = getClassAround(text, start);

        if (start == text.length()) {
            start = text.offsetByCodePoints(start, -1);
        }

        while (start > 0) {
            int previous = text.offsetByCodePoints(start, -1);

            if (getClass(text.codePointAt(previous)) != kind) {
                break;
            }

            start = previous;
        }

        return start;
    }

    /**
     * Finds the end of the word around an index ({@link #findWordStart}).
     *
     * @param text  the text
     * @param index a UTF-16 index of the text on a code point boundary
     * @return the UTF-16 index after the run's last character
     */
    public static int findWordEnd(@NotNull String text, int index) {
        if (text.isEmpty()) {
            return 0;
        }

        int end = Math.min(index, text.length());
        CharacterClass kind = getClassAround(text, end);

        while (end < text.length() && getClass(text.codePointAt(end)) == kind) {
            end = text.offsetByCodePoints(end, 1);
        }

        return end;
    }

    /**
     * Returns the class of the character at an index, or of the one before it at the text's end.
     */
    private static @NotNull CharacterClass getClassAround(@NotNull String text, int index) {
        int at = index < text.length() ? index : text.offsetByCodePoints(index, -1);

        return getClass(text.codePointAt(at));
    }

    private static @NotNull CharacterClass getClass(int codePoint) {
        if (Character.isLetterOrDigit(codePoint)) {
            return CharacterClass.WORD;
        }

        return Character.isWhitespace(codePoint) ? CharacterClass.SPACE : CharacterClass.OTHER;
    }
}
