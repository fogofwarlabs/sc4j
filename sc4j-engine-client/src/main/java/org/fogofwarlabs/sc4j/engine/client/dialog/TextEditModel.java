package org.fogofwarlabs.sc4j.engine.client.dialog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Predicate;

/**
 * Edits one line of text: typing, the editing keys ({@link TextEditKey}), a selection that Shift with the caret keys and the pointer extend
 * ({@link #press(TextEditKey, boolean)}, {@link #moveCaret}, {@link #selectWord}), the clipboard operations and a bounded undo/redo history
 * ({@link TextEditOperation}), a length limit, refused characters and an owner's acceptance test. A dialog control draws the text, the
 * selection and the caret in its own look and maps the pointer to an index ({@link TextBoundaryUtils#findIndexAt}); the model holds no look.
 * <p>
 * The model works in Unicode code points: the caret and the selection ends are UTF-16 indexes of {@link #getText()} that never split a
 * surrogate pair, Backspace, Delete, Left and Right step over a whole code point, and the length limit counts code points. Typed characters
 * that follow each other form one undo step.
 */
public final class TextEditModel {
    /**
     * The length limit of a text without a limit.
     */
    public static final int UNLIMITED = Integer.MAX_VALUE;
    /**
     * The most undo steps kept.
     */
    public static final int MAXIMUM_UNDO_STEPS = 64;

    private static final int FIRST_PRINTABLE = 0x20;
    /**
     * The selection anchor without a selection.
     */
    private static final int NO_SELECTION = -1;

    /**
     * A state of the text that undo and redo return to.
     */
    private static final class Snapshot {
        private final @NotNull String text;
        private final int caret;

        private Snapshot(@NotNull String text, int caret) {
            this.text  = text;
            this.caret = caret;
        }
    }

    private final int maximumLength;
    private final @NotNull String rejected;
    /**
     * The owner's test of the text a change would leave, or {@code null}.
     */
    private @Nullable Predicate<String> acceptance;

    private final @NotNull StringBuilder text;
    private int caret;
    /**
     * The other end of the selection, or {@link #NO_SELECTION}.
     */
    private int anchor;

    private final @NotNull Deque<Snapshot> undoSteps;
    private final @NotNull Deque<Snapshot> redoSteps;
    /**
     * Whether the last change typed a character (following characters join its undo step).
     */
    private boolean typing;

    /**
     * Creates a {@link TextEditModel} instance with the caret at the end of the text.
     *
     * @param text          the initial text
     * @param maximumLength the most code points the text takes, or {@link #UNLIMITED}
     * @param rejected      the characters typing and pasting refuse
     */
    public TextEditModel(@NotNull String text, int maximumLength, @NotNull String rejected) {
        this.maximumLength = maximumLength;
        this.rejected      = rejected;
        this.acceptance    = null;

        this.text   = new StringBuilder(text);
        this.caret  = this.text.length();
        this.anchor = NO_SELECTION;

        this.undoSteps = new ArrayDeque<>();
        this.redoSteps = new ArrayDeque<>();
        this.typing    = false;
    }

    /**
     * @return the text
     */
    public @NotNull String getText() {
        return this.text.toString();
    }

    /**
     * Sets the text, moves the caret after it and forgets the selection and the undo history.
     *
     * @param text the text
     */
    public void setText(@NotNull String text) {
        this.text.setLength(0);
        this.text.append(text);

        this.caret  = this.text.length();
        this.anchor = NO_SELECTION;

        this.undoSteps.clear();
        this.redoSteps.clear();
        this.typing = false;
    }

    /**
     * @return the most code points the text takes, or {@link #UNLIMITED}
     */
    public int getMaximumLength() {
        return this.maximumLength;
    }

    /**
     * @return the caret position, a UTF-16 index of the text on a code point boundary
     */
    public int getCaret() {
        return this.caret;
    }

    /**
     * @return the first selected UTF-16 index; equal to {@link #getSelectionEnd} without a selection
     */
    public int getSelectionStart() {
        return this.anchor == NO_SELECTION ? this.caret : Math.min(this.anchor, this.caret);
    }

    /**
     * @return the UTF-16 index after the selection; equal to {@link #getSelectionStart} without a selection
     */
    public int getSelectionEnd() {
        return this.anchor == NO_SELECTION ? this.caret : Math.max(this.anchor, this.caret);
    }

    /**
     * Sets the owner's test of the whole text a change would leave; a change it refuses does not happen (typing refuses the character, pasting
     * stops at it).
     *
     * @param acceptance the test, or {@code null} for none
     */
    public void setAcceptance(@Nullable Predicate<String> acceptance) {
        this.acceptance = acceptance;
    }


    /**
     * Types a character: it replaces the selection, or inserts at the caret. A control character, a refused character, a character beyond the
     * length limit and a change the acceptance test refuses leave the text as it is.
     *
     * @param codePoint the character
     * @return {@code true} if the text changed, {@code false} otherwise
     */
    public boolean type(int codePoint) {
        int start = this.getSelectionStart();
        int end = this.getSelectionEnd();
        int selectedLength = this.text.codePointCount(start, end);

        if (codePoint < FIRST_PRINTABLE || this.rejected.indexOf(codePoint) >= 0 ||
            this.text.codePointCount(0, this.text.length()) - selectedLength >= this.maximumLength) {
            return false;
        }

        String inserted = Character.toString(codePoint);

        if (this.acceptance != null && !this.acceptance.test(new StringBuilder(this.text).replace(start, end, inserted).toString())) {
            return false;
        }

        if (!this.typing) {
            this.rememberStep();
        }

        this.removeSelection();
        this.text.insert(this.caret, inserted);
        this.caret += inserted.length();

        this.typing = true;

        return true;
    }

    /**
     * Applies an editing key without extending the selection ({@link #press(TextEditKey, boolean)}).
     *
     * @param key the key
     * @return {@code true} if the text changed, {@code false} otherwise
     */
    public boolean press(@NotNull TextEditKey key) {
        return this.press(key, false);
    }

    /**
     * Applies an editing key; Backspace and Delete remove the selection when there is one, Left and Right end it at its start or end. While
     * extending (Shift held), Left, Right, Home and End move the caret and keep the selection's other end, so the selection grows or shrinks
     * from where it started; Backspace and Delete ignore it.
     *
     * @param key    the key
     * @param extend whether the caret keys extend the selection
     * @return {@code true} if the text changed, {@code false} otherwise
     */
    public boolean press(@NotNull TextEditKey key, boolean extend) {
        int start = this.getSelectionStart();
        int end = this.getSelectionEnd();

        if (extend && key != TextEditKey.BACKSPACE && key != TextEditKey.DELETE) {
            int origin = this.anchor == NO_SELECTION ? this.caret : this.anchor;

            this.caret = switch (key) {
                case LEFT -> this.caret == 0 ? 0 : this.text.offsetByCodePoints(this.caret, -1);
                case RIGHT -> this.caret >= this.text.length() ? this.caret : this.text.offsetByCodePoints(this.caret, 1);
                case HOME -> 0;
                case END -> this.text.length();
                case BACKSPACE, DELETE -> this.caret;
            };

            this.anchor = origin == this.caret ? NO_SELECTION : origin;
            this.typing = false;

            return false;
        }

        if ((key == TextEditKey.BACKSPACE || key == TextEditKey.DELETE) && start < end) {
            this.rememberStep();
            this.removeSelection();

            return true;
        }

        this.anchor = NO_SELECTION;

        switch (key) {
            case BACKSPACE -> {
                if (this.caret == 0) {
                    return false;
                }

                int previous = this.text.offsetByCodePoints(this.caret, -1);

                this.rememberStep();
                this.text.delete(previous, this.caret);
                this.caret = previous;

                return true;
            }
            case DELETE -> {
                if (this.caret >= this.text.length()) {
                    return false;
                }

                this.rememberStep();
                this.text.delete(this.caret, this.text.offsetByCodePoints(this.caret, 1));

                return true;
            }
            case LEFT -> this.caret = start < end || this.caret == 0 ? start : this.text.offsetByCodePoints(this.caret, -1);
            case RIGHT -> this.caret = start < end || this.caret >= this.text.length() ? end : this.text.offsetByCodePoints(this.caret, 1);
            case HOME -> this.caret = 0;
            case END -> this.caret = this.text.length();
        }

        this.typing = false;

        return false;
    }

    /**
     * Moves the caret to an index, as a pointer press or drag does; an index inside a surrogate pair moves before the pair. Extending keeps
     * the selection's other end (the caret before the move without a selection), otherwise the selection ends.
     *
     * @param index  the UTF-16 index, clamped to the text
     * @param extend whether the move extends the selection
     */
    public void moveCaret(int index, boolean extend) {
        int origin = this.anchor == NO_SELECTION ? this.caret : this.anchor;
        int target = Math.clamp(index, 0, this.text.length());

        if (target > 0 && target < this.text.length() && Character.isLowSurrogate(this.text.charAt(target)) &&
            Character.isHighSurrogate(this.text.charAt(target - 1))) {
            target--;
        }

        this.caret  = target;
        this.anchor = extend && origin != target ? origin : NO_SELECTION;
        this.typing = false;
    }

    /**
     * Selects the word around an index ({@link TextBoundaryUtils#findWordStart}), as a double press does; the caret goes to the word's end.
     *
     * @param index the UTF-16 index, clamped to the text
     */
    public void selectWord(int index) {
        String current = this.text.toString();
        int target = Math.clamp(index, 0, current.length());
        int start = TextBoundaryUtils.findWordStart(current, target);

        this.caret  = TextBoundaryUtils.findWordEnd(current, target);
        this.anchor = start == this.caret ? NO_SELECTION : start;
        this.typing = false;
    }

    /**
     * Applies an edit operation.
     * <ul>
     * <li>{@link TextEditOperation#SELECT_ALL}: the whole text is selected, the caret at its end.</li>
     * <li>{@link TextEditOperation#COPY} and {@link TextEditOperation#CUT}: the selection goes to the clipboard; a cut removes it.</li>
     * <li>{@link TextEditOperation#PASTE}: the clipboard text replaces the selection, character by character under the rules of typing;
     * refused characters (line breaks and other control characters, the refused set) are skipped, and the paste stops at the length limit or
     * where the acceptance test refuses.</li>
     * <li>{@link TextEditOperation#UNDO} and {@link TextEditOperation#REDO}: the text and the caret go back to the state before the last change,
     * or forward again; consecutive typed characters are one step.</li>
     * </ul>
     *
     * @param operation the operation
     * @param clipboard the clipboard
     * @return {@code true} if the text changed, {@code false} otherwise
     */
    public boolean apply(@NotNull TextEditOperation operation, @NotNull TextClipboard clipboard) {
        int start = this.getSelectionStart();
        int end = this.getSelectionEnd();

        switch (operation) {
            case SELECT_ALL -> {
                this.anchor = 0;
                this.caret  = this.text.length();
                this.typing = false;

                return false;
            }
            case COPY, CUT -> {
                if (start == end) {
                    return false;
                }

                clipboard.write(this.text.substring(start, end));

                if (operation == TextEditOperation.COPY) {
                    return false;
                }

                this.rememberStep();
                this.removeSelection();

                return true;
            }
            case PASTE -> {
                String pasted = clipboard.read();

                return pasted != null && this.paste(pasted);
            }
            case UNDO -> {
                return this.restoreStep(this.undoSteps, this.redoSteps);
            }
            case REDO -> {
                return this.restoreStep(this.redoSteps, this.undoSteps);
            }
        }

        return false;
    }

    private boolean paste(@NotNull String pasted) {
        Snapshot before = new Snapshot(this.text.toString(), this.caret);
        boolean changed = this.getSelectionStart() < this.getSelectionEnd();

        this.removeSelection();

        int length = this.text.codePointCount(0, this.text.length());

        for (int codePoint : pasted.codePoints().toArray()) {
            if (codePoint < FIRST_PRINTABLE || this.rejected.indexOf(codePoint) >= 0) {
                continue;
            }

            String inserted = Character.toString(codePoint);

            if (length + 1 > this.maximumLength ||
                this.acceptance != null && !this.acceptance.test(new StringBuilder(this.text).insert(this.caret, inserted).toString())) {
                break;
            }

            this.text.insert(this.caret, inserted);

            this.caret += inserted.length();
            length++;
            changed = true;
        }

        if (changed) {
            this.pushStep(this.undoSteps, before);
        }

        this.typing = false;

        return changed;
    }

    private void removeSelection() {
        int start = this.getSelectionStart();
        int end = this.getSelectionEnd();

        this.anchor = NO_SELECTION;

        if (start == end) {
            return;
        }

        this.text.delete(start, end);

        this.caret = start;
    }

    /**
     * Keeps the state before a change as an undo step; a new change drops the redo steps.
     */
    private void rememberStep() {
        this.pushStep(this.undoSteps, new Snapshot(this.text.toString(), this.caret));

        this.typing = false;
    }

    private void pushStep(@NotNull Deque<Snapshot> steps, @NotNull Snapshot step) {
        if (steps == this.undoSteps) {
            this.redoSteps.clear();
        }

        steps.push(step);

        if (steps.size() > MAXIMUM_UNDO_STEPS) {
            steps.removeLast();
        }
    }

    private boolean restoreStep(@NotNull Deque<Snapshot> from, @NotNull Deque<Snapshot> to) {
        Snapshot step = from.poll();

        if (step == null) {
            return false;
        }

        to.push(new Snapshot(this.text.toString(), this.caret));
        this.text.setLength(0);
        this.text.append(step.text);

        this.caret  = Math.min(step.caret, this.text.length());
        this.anchor = NO_SELECTION;
        this.typing = false;

        return true;
    }
}
