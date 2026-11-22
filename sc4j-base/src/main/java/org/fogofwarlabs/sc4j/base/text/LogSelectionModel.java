package org.fogofwarlabs.sc4j.base.text;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Selects text across the lines of a read-only log, such as a chat history: a pointer press sets both ends, a drag moves the caret end, a
 * press with Shift held extends the selection from its first end and a double press selects a word. The log keeps its lines, one message
 * each, and maps the pointer to a line and an index ({@link TextBoundaryUtils#findIndexAt}); the model keeps the two ends only. The
 * selected text ({@link #getSelectedText}) joins the selected part of each line with line feeds, one line per message.
 * <p>
 * An end is a line index of the log and a UTF-16 index of that line's text.
 */
public final class LogSelectionModel {
    /**
     * The line of an end without a selection.
     */
    private static final int NO_LINE = -1;
    private static final char LINE_SEPARATOR = '\n';

    private int anchorLine;
    private int anchorIndex;
    private int caretLine;
    private int caretIndex;
    /**
     * Whether a press holds the caret end (between {@link #press} and {@link #release}).
     */
    private boolean dragging;

    /**
     * Creates a {@link LogSelectionModel} instance without a selection.
     */
    public LogSelectionModel() {
        this.anchorLine  = NO_LINE;
        this.anchorIndex = 0;
        this.caretLine   = NO_LINE;
        this.caretIndex  = 0;
        this.dragging    = false;
    }

    /**
     * @return {@code true} if text is selected, {@code false} otherwise
     */
    public boolean hasSelection() {
        return this.anchorLine != NO_LINE && (this.anchorLine != this.caretLine || this.anchorIndex != this.caretIndex);
    }

    /**
     * @return {@code true} while a press moves the caret end, {@code false} otherwise
     */
    public boolean isDragging() {
        return this.dragging;
    }

    /**
     * @param line a line index of the log
     * @return {@code true} if text of the line is selected, {@code false} otherwise
     */
    public boolean isSelected(int line) {
        return this.hasSelection() && line >= this.getStartLine() && line <= this.getEndLine();
    }

    /**
     * @param line a selected line index of the log ({@link #isSelected})
     * @return the first selected UTF-16 index of the line: {@code 0} after the selection's first line
     */
    public int getSelectionStart(int line) {
        return line == this.getStartLine() ? this.getStartIndex() : 0;
    }

    /**
     * @param line   a selected line index of the log ({@link #isSelected})
     * @param length the UTF-16 length of the line's text
     * @return the UTF-16 index after the last selected character of the line: the length before the selection's last line
     */
    public int getSelectionEnd(int line, int length) {
        return line == this.getEndLine() ? Math.min(this.getEndIndex(), length) : length;
    }

    /**
     * Returns the selected text: the selected part of each selected line, joined by line feeds.
     *
     * @param lines the log's lines, one message each
     * @return the selected text, empty without a selection
     */
    public @NotNull String getSelectedText(@NotNull List<@NotNull String> lines) {
        if (!this.hasSelection()) {
            return "";
        }

        StringBuilder selected = new StringBuilder();
        int startLine = this.getStartLine();
        int endLine = Math.min(this.getEndLine(), lines.size() - 1);

        for (int line = startLine; line <= endLine; line++) {
            String text = lines.get(line);
            int from = Math.min(this.getSelectionStart(line), text.length());
            int to = Math.max(from, this.getSelectionEnd(line, text.length()));

            if (line > startLine) {
                selected.append(LINE_SEPARATOR);
            }

            selected.append(text, from, to);
        }

        return selected.toString();
    }


    /**
     * Presses the pointer at a position: the caret end goes there and follows the drags until the release; without extending, the other end
     * goes there too, so the selection ends.
     *
     * @param line   the line index under the pointer
     * @param index  the UTF-16 index under the pointer
     * @param extend whether the press extends the selection (Shift held)
     */
    public void press(int line, int index, boolean extend) {
        if (!extend || this.anchorLine == NO_LINE) {
            this.anchorLine  = line;
            this.anchorIndex = index;
        }

        this.caretLine  = line;
        this.caretIndex = index;
        this.dragging   = true;
    }

    /**
     * Moves the caret end while a press holds it.
     *
     * @param line  the line index under the pointer
     * @param index the UTF-16 index under the pointer
     */
    public void drag(int line, int index) {
        if (!this.dragging) {
            return;
        }

        this.caretLine  = line;
        this.caretIndex = index;
    }

    /**
     * Releases the press; the selection stays.
     */
    public void release() {
        this.dragging = false;
    }

    /**
     * Selects the word around an index of a line ({@link TextBoundaryUtils#findWordStart}), as a double press does.
     *
     * @param line  the line index
     * @param text  the line's text
     * @param index the UTF-16 index of the line's text
     */
    public void selectWord(int line, @NotNull String text, int index) {
        int target = Math.clamp(index, 0, text.length());

        this.anchorLine  = line;
        this.anchorIndex = TextBoundaryUtils.findWordStart(text, target);
        this.caretLine   = line;
        this.caretIndex  = TextBoundaryUtils.findWordEnd(text, target);
        this.dragging    = false;
    }

    /**
     * Ends the selection.
     */
    public void clear() {
        this.anchorLine  = NO_LINE;
        this.anchorIndex = 0;
        this.caretLine   = NO_LINE;
        this.caretIndex  = 0;
        this.dragging    = false;
    }

    /**
     * Follows the log dropping its oldest lines: the ends move up by the dropped lines; an end on a dropped line goes to the start of the
     * first line left, and a selection inside the dropped lines ends.
     *
     * @param count the number of dropped lines
     */
    public void removeFirstLines(int count) {
        if (this.anchorLine == NO_LINE) {
            return;
        }

        if (this.anchorLine < count && this.caretLine < count) {
            this.clear();

            return;
        }

        if (this.anchorLine < count) {
            this.anchorLine  = 0;
            this.anchorIndex = 0;
        } else {
            this.anchorLine -= count;
        }

        if (this.caretLine < count) {
            this.caretLine  = 0;
            this.caretIndex = 0;
        } else {
            this.caretLine -= count;
        }
    }

    private boolean isAnchorFirst() {
        return this.anchorLine < this.caretLine || this.anchorLine == this.caretLine && this.anchorIndex <= this.caretIndex;
    }

    private int getStartLine() {
        return this.isAnchorFirst() ? this.anchorLine : this.caretLine;
    }

    private int getStartIndex() {
        return this.isAnchorFirst() ? this.anchorIndex : this.caretIndex;
    }

    private int getEndLine() {
        return this.isAnchorFirst() ? this.caretLine : this.anchorLine;
    }

    private int getEndIndex() {
        return this.isAnchorFirst() ? this.caretIndex : this.anchorIndex;
    }
}
