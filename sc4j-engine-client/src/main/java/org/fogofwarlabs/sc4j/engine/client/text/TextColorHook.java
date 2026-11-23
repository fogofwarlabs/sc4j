package org.fogofwarlabs.sc4j.engine.client.text;

import org.jetbrains.annotations.NotNull;

/**
 * Turns colour codes of a text into pen colours: a mod's text uses characters (control characters, for example) that are not
 * drawn and change the colour of the following characters.
 * <p>
 * A font keeps two colours while it draws a line: the current one and the one before the last code, so that a code can switch
 * back ({@link TextColorTable.Builder#setRestoreCode}). After each code the current colour becomes the previous one.
 */
public interface TextColorHook {
    /**
     * The hook of text without colour codes: every character is drawn in the line's colour.
     */
    @NotNull TextColorHook NONE = new TextColorHook() {
        /**
         * @param codePoint the character
         * @return {@code false} always
         */
        @Override
        public boolean isColorCode(int codePoint) {
            return false;
        }

        /**
         * Finds the pen colour after a colour code.
         *
         * @param codePoint     the colour code, one {@link #isColorCode} accepts
         * @param lineColor     the packed {@code 0xRRGGBBAA} colour the line started in, for codes that restore it
         * @param currentColor  the packed colour of the pen before the code
         * @param previousColor the packed colour of the pen before the code that set the current colour (the line's colour before the first code), for codes that switch back
         * @return the current colour, unchanged
         */
        @Override
        public int getColor(int codePoint, int lineColor, int currentColor, int previousColor) {
            return currentColor;
        }
    };

    /**
     * @param codePoint the character
     * @return {@code true} if the character is a colour code that changes the pen colour and is not drawn, {@code false} otherwise
     */
    boolean isColorCode(int codePoint);

    /**
     * Finds the pen colour after a colour code.
     *
     * @param codePoint     the colour code, one {@link #isColorCode} accepts
     * @param lineColor     the packed {@code 0xRRGGBBAA} colour the line started in, for codes that restore it
     * @param currentColor  the packed colour of the pen before the code
     * @param previousColor the packed colour of the pen before the code that set the current colour (the line's colour before the first code), for codes that switch back
     * @return the packed colour of the following characters
     */
    int getColor(int codePoint, int lineColor, int currentColor, int previousColor);
}
