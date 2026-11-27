package org.fogofwarlabs.sc4j.translate.service.sentencepiece;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * Normalizes text the way a SentencePiece model's normalizer does, on UTF-8 bytes: the precompiled character map replaces the longest
 * matching byte sequence at each position, leading and trailing spaces are removed and runs of spaces collapsed to one, a space is put in
 * front of the text and spaces become {@code U+2581}.
 * <p>
 * The precompiled character map is a little-endian 32-bit length, a double-array trie of that many bytes over UTF-8 byte sequences whose
 * values are offsets into the replacement pool that follows, and the pool of zero-terminated replacements.
 */
final class SentencePieceNormalizer {
    /**
     * The UTF-8 bytes of {@code U+2581}, which stands for a space in pieces.
     */
    static final byte @NotNull [] SPACE_SYMBOL = {(byte) 0xE2, (byte) 0x96, (byte) 0x81};

    private static final byte SPACE = ' ';
    private static final int TRIE_SIZE_LENGTH = 4;
    private static final int TRIE_UNIT_SIZE = 4;
    private static final int LEAF_BIT = 8;
    private static final int VALUE_MASK = 0x7FFFFFFF;
    private static final int LABEL_MASK = 0x800000FF;
    private static final int OFFSET_SHIFT = 10;
    private static final int OFFSET_EXTENSION_BIT = 1 << 9;
    private static final int OFFSET_EXTENSION_SHIFT = 6;
    private static final int TWO_BYTE_LEAD = 0xE0;
    private static final int THREE_BYTE_LEAD = 0xF0;
    private static final int SINGLE_BYTE_LIMIT = 0x80;
    private static final int TWO_BYTES = 2;
    private static final int THREE_BYTES = 3;
    private static final int FOUR_BYTES = 4;
    /**
     * The first output capacity per input byte; a replacement is rarely longer than three times its input.
     */
    private static final int OUTPUT_GROWTH = 3;

    /**
     * The replacement found at one position of the input: bytes of the pool or of the input itself, and the input bytes it replaces.
     */
    private static final class Replacement {
        private byte @NotNull [] source;
        private int start;
        private int length;
        private int consumed;

        private Replacement(byte @NotNull [] source) {
            this.source   = source;
            this.start    = 0;
            this.length   = 0;
            this.consumed = 0;
        }

        private boolean isSingleSpace() {
            return this.length == 1 && this.source[this.start] == SPACE;
        }
    }


    private static int offset(int unit) {
        return (unit >>> OFFSET_SHIFT) << ((unit & OFFSET_EXTENSION_BIT) >>> OFFSET_EXTENSION_SHIFT);
    }

    /**
     * Returns the length of the UTF-8 character that starts with a byte.
     */
    static int characterLength(byte lead) {
        int value = lead & 0xFF;

        if (value < SINGLE_BYTE_LIMIT) {
            return 1;
        }

        if (value < TWO_BYTE_LEAD) {
            return TWO_BYTES;
        }

        return value < THREE_BYTE_LEAD ? THREE_BYTES : FOUR_BYTES;
    }

    private static byte @NotNull [] ensureCapacity(byte @NotNull [] output, int required) {
        return required <= output.length ? output : Arrays.copyOf(output, Math.max(required, output.length * 2));
    }


    private final int @NotNull [] trie;
    private final byte @NotNull [] pool;
    private final boolean addDummyPrefix;
    private final boolean removeExtraWhitespaces;
    private final boolean escapeWhitespaces;

    /**
     * Creates a {@link SentencePieceNormalizer} instance.
     *
     * @param characterMap           the precompiled character map, or an empty array for none
     * @param addDummyPrefix         whether a space is put in front of the text
     * @param removeExtraWhitespaces whether leading and trailing spaces are removed and runs of spaces collapsed
     * @param escapeWhitespaces      whether spaces become {@code U+2581}
     * @throws IOException if the character map is malformed
     */
    SentencePieceNormalizer(byte @NotNull [] characterMap, boolean addDummyPrefix, boolean removeExtraWhitespaces,
                            boolean escapeWhitespaces) throws IOException {
        if (characterMap.length == 0) {
            this.trie = new int[0];
            this.pool = new byte[0];
        } else {
            if (characterMap.length < TRIE_SIZE_LENGTH) {
                throw Exceptions.newIOException("The precompiled character map is shorter than its header");
            }

            int trieSize = LittleEndianUtils.readInt32(characterMap, 0);

            if (trieSize <= 0 || trieSize % TRIE_UNIT_SIZE != 0 || trieSize > characterMap.length - TRIE_SIZE_LENGTH) {
                throw Exceptions.newIOException("Invalid trie size {0} in the precompiled character map of {1} bytes", trieSize, characterMap.length);
            }

            this.trie = new int[trieSize / TRIE_UNIT_SIZE];

            for (int index = 0; index < this.trie.length; index++) {
                this.trie[index] = LittleEndianUtils.readInt32(characterMap, TRIE_SIZE_LENGTH + index * TRIE_UNIT_SIZE);
            }

            this.pool = Arrays.copyOfRange(characterMap, TRIE_SIZE_LENGTH + trieSize, characterMap.length);
        }

        this.addDummyPrefix         = addDummyPrefix;
        this.removeExtraWhitespaces = removeExtraWhitespaces;
        this.escapeWhitespaces      = escapeWhitespaces;
    }

    /**
     * Normalizes UTF-8 text.
     *
     * @param input the UTF-8 bytes of the text, valid UTF-8
     * @return the normalized UTF-8 bytes, empty when the text has only spaces
     */
    byte @NotNull [] normalize(byte @NotNull [] input) {
        Replacement replacement = new Replacement(input);
        int position = 0;

        if (this.removeExtraWhitespaces) {
            while (position < input.length) {
                this.findReplacement(input, position, replacement);

                if (!replacement.isSingleSpace()) {
                    break;
                }

                position += replacement.consumed;
            }
        }

        if (position == input.length) {
            return new byte[0];
        }

        byte[] output = new byte[(input.length - position) * OUTPUT_GROWTH + SPACE_SYMBOL.length];
        int outputLength = 0;

        if (this.addDummyPrefix) {
            output       = ensureCapacity(output, outputLength + SPACE_SYMBOL.length);
            outputLength = this.appendSpace(output, outputLength);
        }

        boolean previousSpace = this.removeExtraWhitespaces;

        while (position < input.length) {
            this.findReplacement(input, position, replacement);

            int start = replacement.start;
            int length = replacement.length;

            if (previousSpace) {
                while (length > 0 && replacement.source[start] == SPACE) {
                    start++;
                    length--;
                }
            }

            if (length > 0) {
                output = ensureCapacity(output, outputLength + length * SPACE_SYMBOL.length);

                for (int index = start; index < start + length; index++) {
                    byte value = replacement.source[index];

                    if (value == SPACE) {
                        outputLength = this.appendSpace(output, outputLength);
                    } else {
                        output[outputLength++] = value;
                    }
                }

                previousSpace = replacement.source[start + length - 1] == SPACE;
            }

            position += replacement.consumed;

            if (!this.removeExtraWhitespaces) {
                previousSpace = false;
            }
        }

        if (this.removeExtraWhitespaces) {
            byte[] space = this.escapeWhitespaces ? SPACE_SYMBOL : new byte[]{SPACE};

            while (outputLength >= space.length && Arrays.equals(output, outputLength - space.length, outputLength, space, 0, space.length)) {
                outputLength -= space.length;
            }
        }

        return Arrays.copyOf(output, outputLength);
    }

    private int appendSpace(byte @NotNull [] output, int outputLength) {
        if (!this.escapeWhitespaces) {
            output[outputLength] = SPACE;

            return outputLength + 1;
        }

        System.arraycopy(SPACE_SYMBOL, 0, output, outputLength, SPACE_SYMBOL.length);

        return outputLength + SPACE_SYMBOL.length;
    }

    /**
     * Finds the replacement at a position: the pool entry of the longest byte sequence of the trie that starts there, or else the input's
     * own character.
     */
    private void findReplacement(byte @NotNull [] input, int position, @NotNull Replacement replacement) {
        int longestLength = 0;
        int longestValue = 0;

        if (this.trie.length > 0) {
            int node = offset(this.trie[0]);

            for (int index = position; index < input.length; index++) {
                int label = input[index] & 0xFF;

                node ^= label;

                if (node < 0 || node >= this.trie.length) {
                    break;
                }

                int unit = this.trie[node];

                if ((unit & LABEL_MASK) != label) {
                    break;
                }

                node ^= offset(unit);

                if (((unit >>> LEAF_BIT) & 1) != 0 && node >= 0 && node < this.trie.length) {
                    longestLength = index - position + 1;
                    longestValue  = this.trie[node] & VALUE_MASK;
                }
            }
        }

        if (longestLength == 0) {
            replacement.source   = input;
            replacement.start    = position;
            replacement.length   = Math.min(characterLength(input[position]), input.length - position);
            replacement.consumed = replacement.length;

            return;
        }

        int end = longestValue;

        while (end < this.pool.length && this.pool[end] != 0) {
            end++;
        }

        replacement.source   = this.pool;
        replacement.start    = longestValue;
        replacement.length   = end - longestValue;
        replacement.consumed = longestLength;
    }
}
