package org.fogofwarlabs.sc4j.translate;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;

/**
 * Marks a range of a request's text that is kept verbatim, such as a colour code; only the text between protected spans is translated.
 */
public final class ProtectedSpan {
    private final int start;
    private final int end;

    /**
     * Creates a {@link ProtectedSpan} instance.
     *
     * @param start the index of the first character of the span
     * @param end   the index after the last character of the span
     * @throws IllegalArgumentException if {@code start} is negative or {@code end} is not greater than {@code start}
     */
    public ProtectedSpan(int start, int end) {
        if (start < 0 || end <= start) {
            throw Exceptions.newIllegalArgumentException("Invalid protected span: {0} to {1}", start, end);
        }

        this.start = start;
        this.end   = end;
    }

    /**
     * @return the index of the first character of the span
     */
    public int getStart() {
        return this.start;
    }

    /**
     * @return the index after the last character of the span
     */
    public int getEnd() {
        return this.end;
    }
}
