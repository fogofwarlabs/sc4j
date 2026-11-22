package org.fogofwarlabs.sc4j.launcher.ui;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a web address found in a text ({@link #findLinks}): where it starts and ends in the text and the address. Only {@code http} and
 * {@code https} addresses are found, never another scheme.
 * <p>
 * An address starts with its scheme ({@code http://} or {@code https://}, in any case) where no ASCII letter or digit comes before it and runs to
 * the next white space, control character, non-ASCII character or {@code <>"}; an ASCII letter, digit or {@code [} must follow the scheme.
 * Punctuation that ends a sentence and a closing bracket without its opening one are left out of its end.
 */
public final class TextLink {
    private static final @NotNull List<@NotNull String> SCHEMES = List.of("https://", "http://");
    private static final @NotNull String STOP_CHARACTERS = "<>\"";
    private static final @NotNull String TRAILING_PUNCTUATION = ".,:;!?'*";
    private static final @NotNull String OPENING_BRACKETS = "([{";
    private static final @NotNull String CLOSING_BRACKETS = ")]}";
    private static final char IPV6_HOST_START = '[';
    private static final char ASCII_LIMIT = 0x80;

    /**
     * Finds the web addresses of a text, in order.
     *
     * @param text the text
     * @return the found addresses, empty when the text has none
     */
    public static @NotNull List<@NotNull TextLink> findLinks(@NotNull String text) {
        List<TextLink> links = new ArrayList<>();
        int index = 0;

        while (index < text.length()) {
            int schemeLength = findSchemeLength(text, index);

            if (schemeLength == 0 || index > 0 && isAsciiLetterOrDigit(text.charAt(index - 1)) || !isHostStart(text, index + schemeLength)) {
                index = text.offsetByCodePoints(index, 1);

                continue;
            }

            int end = findEnd(text, index + schemeLength);

            links.add(new TextLink(index, end, text.substring(index, end)));
            index = end;
        }

        return links;
    }

    /**
     * Checks whether a text is one web address as a whole ({@link #findLinks}).
     *
     * @param address the text
     * @return {@code true} if the text is an {@code http} or {@code https} address and nothing else, {@code false} otherwise
     */
    public static boolean isWebAddress(@NotNull String address) {
        List<TextLink> links = findLinks(address);

        return links.size() == 1 && links.getFirst().getStart() == 0 && links.getFirst().getEnd() == address.length();
    }

    private static int findSchemeLength(@NotNull String text, int index) {
        for (String scheme : SCHEMES) {
            if (text.regionMatches(true, index, scheme, 0, scheme.length())) {
                return scheme.length();
            }
        }

        return 0;
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return character < ASCII_LIMIT && Character.isLetterOrDigit(character);
    }

    private static boolean isHostStart(@NotNull String text, int index) {
        return index < text.length() && (isAsciiLetterOrDigit(text.charAt(index)) || text.charAt(index) == IPV6_HOST_START);
    }

    /**
     * Finds the end of an address: the next stop character, without the punctuation and unopened closing brackets at its end.
     */
    private static int findEnd(@NotNull String text, int hostStart) {
        int end = hostStart;

        while (end < text.length()) {
            char character = text.charAt(end);

            if (character >= ASCII_LIMIT || Character.isWhitespace(character) || Character.isISOControl(character) ||
                STOP_CHARACTERS.indexOf(character) >= 0) {
                break;
            }

            end++;
        }

        while (end > hostStart) {
            char last = text.charAt(end - 1);
            int bracket = CLOSING_BRACKETS.indexOf(last);

            if (TRAILING_PUNCTUATION.indexOf(last) >= 0 ||
                bracket >= 0 && count(text, hostStart, end, last) > count(text, hostStart, end, OPENING_BRACKETS.charAt(bracket))) {
                end--;
            } else {
                break;
            }
        }

        return end;
    }

    private static int count(@NotNull String text, int start, int end, char target) {
        int count = 0;

        for (int index = start; index < end; index++) {
            if (text.charAt(index) == target) {
                count++;
            }
        }

        return count;
    }


    private final int start;
    private final int end;
    private final @NotNull String address;

    /**
     * Creates a {@link TextLink} instance.
     *
     * @param start   the UTF-16 index of the address's first character in the text
     * @param end     the UTF-16 index after the address's last character in the text
     * @param address the address
     */
    private TextLink(int start, int end, @NotNull String address) {
        this.start   = start;
        this.end     = end;
        this.address = address;
    }

    /**
     * @return the UTF-16 index of the address's first character in the text
     */
    public int getStart() {
        return this.start;
    }

    /**
     * @return the UTF-16 index after the address's last character in the text
     */
    public int getEnd() {
        return this.end;
    }

    /**
     * @return the address, as the text writes it
     */
    public @NotNull String getAddress() {
        return this.address;
    }

    /**
     * @param index a UTF-16 index of the text
     * @return {@code true} if the character at the index belongs to the address, {@code false} otherwise
     */
    public boolean contains(int index) {
        return index >= this.start && index < this.end;
    }
}
