package org.fogofwarlabs.sc4j.base.text;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads and cuts zero-terminated UTF-8 byte strings in buffers of a fixed size: names, chat texts and the file names of
 * commands.
 * <p>
 * A buffer of a size holds at most {@code size - 1} bytes and the terminator, whether the text is cut to new bytes
 * ({@link #cutText}) or put into a buffer inside a larger array ({@link #putText}). The cut works on the bytes and does not
 * look at UTF-8 character boundaries, so a character split by it keeps its first bytes. The cut bytes are what a buffer, a
 * command or a file holds; a {@link String} read from them ({@link #readText}) serves display and comparison only, as it
 * holds a split character as U+FFFD. A whole-character cut ({@link #cutWholeCharacters}, {@link #putWholeCharacters}) ends
 * the text before a last character that the byte cut split.
 * <p>
 * Names shown in chat lines are copied without their control bytes ({@link #cutTextWithoutControlBytes},
 * {@link #copyPrintableText}); a removed byte does not count toward the bytes kept.
 */
public final class TerminatedStringUtils {
    /**
     * The first byte that is not a control byte.
     */
    private static final int FIRST_PRINTABLE = 0x20;
    /**
     * The tab, a control byte that {@link #cutTextWithoutControlBytes} keeps.
     */
    private static final int TAB = 0x09;
    /**
     * The line feed, a control byte that {@link #cutTextWithoutControlBytes} keeps.
     */
    private static final int LINE_FEED = 0x0A;
    /**
     * The carriage return, a control byte that {@link #cutTextWithoutControlBytes} keeps.
     */
    private static final int CARRIAGE_RETURN = 0x0D;

    /**
     * The first UTF-8 continuation byte; the bytes below it are single-byte characters.
     */
    private static final int FIRST_CONTINUATION_BYTE = 0x80;
    /**
     * The last of the continuation bytes and the lead bytes {@code 0xc0} and {@code 0xc1}, which start no character of a
     * whole-character cut ({@link #putWholeCharacters}).
     */
    private static final int LAST_OVERLONG_LEAD_BYTE = 0xC1;
    /**
     * The first byte above the lead bytes of four-byte sequences.
     */
    private static final int FIRST_INVALID_LEAD_BYTE = 0xF5;
    /**
     * The mask and value of a lead byte of a two-byte sequence.
     */
    private static final int TWO_BYTE_LEAD_MASK = 0xE0;
    private static final int TWO_BYTE_LEAD = 0xC0;
    /**
     * The mask and value of a lead byte of a three-byte sequence.
     */
    private static final int THREE_BYTE_LEAD_MASK = 0xF0;
    private static final int THREE_BYTE_LEAD = 0xE0;
    /**
     * The mask and value of a lead byte of a four-byte sequence.
     */
    private static final int FOUR_BYTE_LEAD_MASK = 0xF8;
    private static final int FOUR_BYTE_LEAD = 0xF0;

    /**
     * Reads the length of a zero-terminated byte string whose terminator must come within a limit and the array. A control byte
     * ({@code 0x01}-{@code 0x1f}) before the terminator makes the string invalid.
     *
     * @param text   the bytes
     * @param offset the offset of the string
     * @param limit  the most bytes searched for the terminator, terminator included
     * @return the length with the terminator, or {@code 0} for an invalid string or no terminator within the limit and the array
     */
    public static int getTerminatedLength(byte @NotNull [] text, int offset, int limit) {
        int available = Math.min(text.length - offset, limit);

        for (int index = 0; index < available; index++) {
            int value = Byte.toUnsignedInt(text[offset + index]);

            if (value == 0) {
                return index + 1;
            }

            if (value < FIRST_PRINTABLE) {
                return 0;
            }
        }

        return 0;
    }

    /**
     * Reads the length of a zero-terminated byte string in a buffer.
     *
     * @param text the buffer
     * @return the bytes before the first terminator, or the buffer length without one
     */
    public static int getTextLength(byte @NotNull [] text) {
        return getTextLength(text, 0);
    }

    /**
     * Reads the length of a zero-terminated byte string that starts at an offset of a buffer. The search runs to the end of
     * the buffer, past any record the string starts in.
     *
     * @param text   the buffer
     * @param offset the offset of the string
     * @return the bytes from the offset before the first terminator, or up to the buffer end without one
     */
    public static int getTextLength(byte @NotNull [] text, int offset) {
        int end = offset;

        while (end < text.length && text[end] != 0) {
            end++;
        }

        return end - offset;
    }

    /**
     * Reads the length of a zero-terminated byte string that starts at an offset of a buffer and ends at its terminator, the
     * buffer end or a limit.
     *
     * @param text   the buffer
     * @param offset the offset of the string
     * @param limit  the most bytes searched for the terminator
     * @return the bytes from the offset before the first terminator, at most the limit and up to the buffer end
     */
    public static int getTextLength(byte @NotNull [] text, int offset, int limit) {
        int end = offset;

        while (end < text.length && end < offset + limit && text[end] != 0) {
            end++;
        }

        return end - offset;
    }

    /**
     * Cuts the UTF-8 bytes of a text as a buffer of a size holds them ({@link #cutText(byte[], int)}).
     *
     * @param text the text
     * @param size the buffer size, terminator included
     * @return the cut bytes without the terminator
     */
    public static byte @NotNull [] cutText(@NotNull String text, int size) {
        return cutText(text.getBytes(StandardCharsets.UTF_8), size);
    }

    /**
     * Cuts the bytes of a text as a buffer of a size holds them: its first {@code size - 1} bytes at most. Cutting the cut
     * bytes again gives the same bytes.
     *
     * @param text the bytes of the text, without terminator
     * @param size the buffer size, terminator included
     * @return the cut bytes without the terminator
     */
    public static byte @NotNull [] cutText(byte @NotNull [] text, int size) {
        return Arrays.copyOf(text, getCutLength(text.length, size));
    }

    /**
     * Puts the bytes of a text into a buffer of a size inside an array: its first {@code size - 1} bytes at most, as
     * {@link #cutText(byte[], int)} keeps them, and the terminator after them. The buffer bytes after the terminator stay as
     * they are.
     *
     * @param text         the bytes holding the text
     * @param offset       the offset of the text
     * @param length       the length of the text, without terminator
     * @param buffer       the array holding the buffer
     * @param bufferOffset the offset of the buffer
     * @param size         the buffer size, terminator included
     */
    public static void putText(byte @NotNull [] text, int offset, int length, byte @NotNull [] buffer, int bufferOffset, int size) {
        int count = getCutLength(length, size);

        System.arraycopy(text, offset, buffer, bufferOffset, count);
        buffer[bufferOffset + count] = 0;
    }

    private static int getCutLength(int length, int size) {
        return Math.min(length, size - 1);
    }

    /**
     * Cuts the UTF-8 bytes of a text to whole characters as a buffer of a size holds them ({@link #putWholeCharacters}).
     *
     * @param text the bytes of the text, without terminator
     * @param size the buffer size, terminator included, at least {@code 1}
     * @return the bytes before the terminator
     */
    public static byte @NotNull [] cutWholeCharacters(byte @NotNull [] text, int size) {
        byte[] buffer = new byte[size];

        putWholeCharacters(text, 0, text.length, buffer, 0, size);

        return Arrays.copyOf(buffer, getTextLength(buffer));
    }

    /**
     * Puts the bytes of a UTF-8 text into a buffer of a size inside an array as {@link #putText} does, then ends the text
     * before its last character when the cut split that character: the character's lead byte becomes the terminator and its
     * bytes after the lead byte stay in the buffer.
     * <p>
     * The last character starts at the last byte that is neither a continuation byte ({@code 0x80}-{@code 0xbf}), a lead byte
     * {@code 0xc0} or {@code 0xc1} nor a byte from {@code 0xf5} on. A lead byte that starts no sequence, and a text whose bytes
     * are all such bytes, stay as they are.
     *
     * @param text         the bytes holding the text
     * @param offset       the offset of the text
     * @param length       the length of the text, without terminator
     * @param buffer       the array holding the buffer
     * @param bufferOffset the offset of the buffer
     * @param size         the buffer size, terminator included, at least {@code 1}
     */
    public static void putWholeCharacters(byte @NotNull [] text, int offset, int length, byte @NotNull [] buffer, int bufferOffset, int size) {
        putText(text, offset, length, buffer, bufferOffset, size);

        int end = bufferOffset + getCutLength(length, size);
        int lead = end - 1;

        while (lead >= bufferOffset && isOutsideSequenceStart(Byte.toUnsignedInt(buffer[lead]))) {
            lead--;
        }

        if (lead >= bufferOffset && lead + getSequenceLength(Byte.toUnsignedInt(buffer[lead])) > end) {
            buffer[lead] = 0;
        }
    }

    private static boolean isOutsideSequenceStart(int value) {
        return value >= FIRST_CONTINUATION_BYTE && value <= LAST_OVERLONG_LEAD_BYTE || value >= FIRST_INVALID_LEAD_BYTE;
    }

    private static int getSequenceLength(int value) {
        if (value < FIRST_CONTINUATION_BYTE) {
            return 1;
        }

        if ((value & TWO_BYTE_LEAD_MASK) == TWO_BYTE_LEAD && value > LAST_OVERLONG_LEAD_BYTE) {
            return 2;
        }

        if ((value & THREE_BYTE_LEAD_MASK) == THREE_BYTE_LEAD) {
            return 3;
        }

        if ((value & FOUR_BYTE_LEAD_MASK) == FOUR_BYTE_LEAD && value < FIRST_INVALID_LEAD_BYTE) {
            return 4;
        }

        return 0;
    }

    /**
     * Cuts a zero-terminated text without its control bytes as a buffer of a size holds it: the bytes before the first
     * terminator except the control bytes {@code 0x01}-{@code 0x1f} other than tab, line feed and carriage return, at most
     * {@code size - 1} of them.
     *
     * @param text the bytes, with or without terminator
     * @param size the buffer size, terminator included
     * @return the kept bytes without the terminator; empty for a size below {@code 2}
     */
    public static byte @NotNull [] cutTextWithoutControlBytes(byte @NotNull [] text, int size) {
        byte[] kept = new byte[Math.max(0, size - 1)];
        int count = 0;

        for (int index = 0; index < text.length && text[index] != 0 && count < kept.length; index++) {
            int value = Byte.toUnsignedInt(text[index]);

            if (value >= FIRST_PRINTABLE || value == TAB || value == LINE_FEED || value == CARRIAGE_RETURN) {
                kept[count++] = text[index];
            }
        }

        return Arrays.copyOf(kept, count);
    }

    /**
     * Copies the printable bytes of a zero-terminated text: the bytes before the first terminator that are not below
     * {@code 0x20}, at most a limit of them. Unlike {@link #cutTextWithoutControlBytes}, the limit leaves no byte for a
     * terminator.
     *
     * @param text  the bytes, with or without terminator
     * @param limit the most bytes kept
     * @return the kept bytes without a terminator
     */
    public static byte @NotNull [] copyPrintableText(byte @NotNull [] text, int limit) {
        byte[] kept = new byte[Math.max(0, limit)];
        int count = 0;

        for (int index = 0; index < text.length && text[index] != 0 && count < kept.length; index++) {
            if (Byte.toUnsignedInt(text[index]) >= FIRST_PRINTABLE) {
                kept[count++] = text[index];
            }
        }

        return Arrays.copyOf(kept, count);
    }

    /**
     * Reads the text of a zero-terminated byte string for display and comparison: a split or malformed UTF-8 sequence reads
     * as U+FFFD.
     *
     * @param text the bytes, with or without terminator
     * @return the text of the bytes before the first terminator
     */
    public static @NotNull String readText(byte @NotNull [] text) {
        return new String(text, 0, getTextLength(text), StandardCharsets.UTF_8);
    }

    private TerminatedStringUtils() {
    }
}
