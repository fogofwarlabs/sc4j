package org.fogofwarlabs.sc4j.engine.client.text;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Holds colour codes as a table: the engine's default {@link TextColorHook}. A code either sets a fixed colour, switches back
 * to the colour before the last code (a restore code) or goes back to the line's colour (a line colour code).
 * <p>
 * The table is indexed by code point, so lookups do not allocate; codes are meant to be small (control characters).
 */
public final class TextColorTable implements TextColorHook {
    /**
     * What a code point of the table does.
     */
    private static final byte NOT_A_CODE = 0;
    private static final byte FIXED_COLOR = 1;
    private static final byte RESTORE = 2;
    private static final byte LINE_COLOR = 3;

    /**
     * Builds {@link TextColorTable} instances.
     */
    public static final class Builder {
        private byte @NotNull [] kinds;
        private int @NotNull [] colors;

        private Builder() {
            this.kinds  = new byte[0];
            this.colors = new int[0];
        }

        /**
         * Sets a code that switches the pen to a fixed colour.
         *
         * @param codePoint the code, {@code 0} or greater
         * @param color     the packed {@code 0xRRGGBBAA} colour of the following characters
         * @return this instance for chaining
         * @throws IllegalArgumentException if the code is negative
         */
        public @NotNull Builder setColor(int codePoint, int color) {
            this.setKind(codePoint, FIXED_COLOR);
            this.colors[codePoint] = color;

            return this;
        }

        /**
         * Sets a code that switches the pen back to the colour it had before the last code.
         *
         * @param codePoint the code, {@code 0} or greater
         * @return this instance for chaining
         * @throws IllegalArgumentException if the code is negative
         */
        public @NotNull Builder setRestoreCode(int codePoint) {
            this.setKind(codePoint, RESTORE);

            return this;
        }

        /**
         * Sets a code that switches the pen back to the colour the line started in.
         *
         * @param codePoint the code, {@code 0} or greater
         * @return this instance for chaining
         * @throws IllegalArgumentException if the code is negative
         */
        public @NotNull Builder setLineColorCode(int codePoint) {
            this.setKind(codePoint, LINE_COLOR);

            return this;
        }

        private void setKind(int codePoint, byte kind) {
            if (codePoint < 0) {
                throw Exceptions.newIllegalArgumentException("Invalid colour code {0}", codePoint);
            }

            if (codePoint >= this.kinds.length) {
                this.kinds  = Arrays.copyOf(this.kinds, codePoint + 1);
                this.colors = Arrays.copyOf(this.colors, codePoint + 1);
            }

            this.kinds[codePoint] = kind;
        }

        /**
         * Builds a {@link TextColorTable} instance from the builder's codes.
         *
         * @return the built {@link TextColorTable} instance
         */
        public @NotNull TextColorTable build() {
            return new TextColorTable(this.kinds.clone(), this.colors.clone());
        }
    }

    /**
     * Creates a {@link Builder} instance without codes.
     *
     * @return the created {@link Builder} instance
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }


    private final byte @NotNull [] kinds;
    private final int @NotNull [] colors;

    private TextColorTable(byte @NotNull [] kinds, int @NotNull [] colors) {
        this.kinds  = kinds;
        this.colors = colors;
    }

    /**
     * @param codePoint the character
     * @return {@code true} if the character is a code of the table, {@code false} otherwise
     */
    @Override
    public boolean isColorCode(int codePoint) {
        return codePoint >= 0 && codePoint < this.kinds.length && this.kinds[codePoint] != NOT_A_CODE;
    }

    /**
     * Finds the pen colour after a colour code: the code's fixed colour, the previous colour for a restore code or the line's
     * colour for a line colour code.
     *
     * @param codePoint     the colour code, one {@link #isColorCode} accepts
     * @param lineColor     the packed {@code 0xRRGGBBAA} colour the line started in, for codes that restore it
     * @param currentColor  the packed colour of the pen before the code
     * @param previousColor the packed colour of the pen before the code that set the current colour (the line's colour before the first code), for codes that switch back
     * @return the packed colour of the following characters
     */
    @Override
    public int getColor(int codePoint, int lineColor, int currentColor, int previousColor) {
        return switch (this.kinds[codePoint]) {
            case FIXED_COLOR -> this.colors[codePoint];
            case RESTORE -> previousColor;
            case LINE_COLOR -> lineColor;
            default -> currentColor;
        };
    }
}
