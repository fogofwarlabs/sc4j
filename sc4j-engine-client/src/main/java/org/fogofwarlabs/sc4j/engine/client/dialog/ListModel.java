package org.fogofwarlabs.sc4j.engine.client.dialog;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Holds the items of a list, the selected item and the first shown row, and moves them as a list control does: a selected row is
 * scrolled into view, the navigation keys move the selection and the wheel scrolls or moves it. A list control draws the rows in its
 * own look; the model holds no look.
 * <p>
 * The number of shown rows is the caller's: it depends on the look (row height, scroll bar), so the methods that scroll take it.
 * The first shown row stays between {@code 0} and the item count less the shown rows.
 *
 * @param <T> the type of the items
 */
public final class ListModel<T> {
    /**
     * The selection of a list without a selected item.
     */
    public static final int NO_SELECTION = -1;

    private final @NotNull List<T> items;

    private int selection;
    private int firstRow;

    /**
     * Creates an empty {@link ListModel} instance.
     */
    public ListModel() {
        this.items = new ArrayList<>();

        this.selection = NO_SELECTION;
        this.firstRow  = 0;
    }

    /**
     * @return the number of items
     */
    public int getCount() {
        return this.items.size();
    }

    /**
     * @param index the item index
     * @return the item
     * @throws IndexOutOfBoundsException if the index is not an item's
     */
    public @NotNull T getItem(int index) {
        return this.items.get(index);
    }

    /**
     * @return the items in order, unmodifiable
     */
    public @NotNull List<T> getItems() {
        return Collections.unmodifiableList(this.items);
    }

    /**
     * Sets the items, clears the selection and shows the first row.
     *
     * @param items the items in order, copied
     */
    public void setItems(@NotNull Collection<? extends T> items) {
        this.items.clear();
        this.items.addAll(items);

        this.selection = NO_SELECTION;
        this.firstRow  = 0;
    }

    /**
     * Appends an item.
     *
     * @param item the item
     */
    public void addItem(@NotNull T item) {
        this.items.add(item);
    }

    /**
     * Inserts an item; the selection stays on its item.
     *
     * @param index the index of the new item
     * @param item  the item
     * @throws IndexOutOfBoundsException if the index is outside {@code 0} to the item count
     */
    public void insertItem(int index, @NotNull T item) {
        this.items.add(index, item);

        if (this.selection >= index) {
            this.selection++;
        }
    }

    /**
     * Removes an item; a selection past the new end moves to the last item, and an empty list has none.
     *
     * @param index the item index
     * @throws IndexOutOfBoundsException if the index is not an item's
     */
    public void removeItem(int index) {
        this.items.remove(index);

        if (this.items.isEmpty()) {
            this.selection = NO_SELECTION;
        } else if (this.selection >= this.items.size()) {
            this.selection = this.items.size() - 1;
        }
    }

    /**
     * @return the selected item's index, or {@link #NO_SELECTION}
     */
    public int getSelection() {
        return this.selection;
    }

    /**
     * @return the index of the item in the first shown row
     */
    public int getFirstRow() {
        return this.firstRow;
    }


    /**
     * Selects an item and scrolls it into view: above the shown rows it becomes the first row, below them the last.
     *
     * @param index the item index; an index that is not an item's clears the selection
     * @param rows  the number of shown rows
     */
    public void select(int index, int rows) {
        this.selection = index < 0 || index >= this.items.size() ? NO_SELECTION : index;

        if (this.selection != NO_SELECTION && this.selection < this.firstRow) {
            this.firstRow = this.selection;
        } else if (this.selection != NO_SELECTION && rows > 0 && this.selection >= this.firstRow + rows) {
            this.firstRow = this.selection - rows + 1;
        }

        this.scrollTo(this.firstRow, rows);
    }

    /**
     * Shows a row first without changing the selection.
     *
     * @param first the item index of the first shown row, clamped so that the shown rows hold items
     * @param rows  the number of shown rows
     */
    public void scrollTo(int first, int rows) {
        this.firstRow = Math.clamp(first, 0, Math.max(0, this.items.size() - Math.max(rows, 1)));
    }

    /**
     * Selects the row under a pointer.
     *
     * @param offset    the pointer's offset below the top of the first shown row
     * @param rowHeight the height of a row
     * @param rows      the number of shown rows
     * @return {@code true} if a row holds the offset and is now selected, {@code false} otherwise
     */
    public boolean selectAt(float offset, float rowHeight, int rows) {
        int index = this.firstRow + (int) (offset / rowHeight);

        if (offset < 0 || index >= this.items.size()) {
            return false;
        }

        this.select(index, rows);

        return true;
    }

    /**
     * Moves the selection by one item and keeps it in view; without a selection the first item is selected.
     *
     * @param down {@code true} for the next item, {@code false} for the previous one
     * @param rows the number of shown rows
     */
    public void step(boolean down, int rows) {
        if (this.items.isEmpty()) {
            return;
        }

        int index = this.selection == NO_SELECTION ? 0 : this.selection + (down ? 1 : -1);

        this.select(Math.clamp(index, 0, this.items.size() - 1), rows);
    }

    /**
     * Moves the selection for a navigation key and keeps it in view. Page Up and Page Down move by the shown rows less one and stop at
     * the first and the last item; Home and End go to them; Up and Down move by one item. Without a selection only Home and End select an
     * item.
     *
     * @param key  {@link DialogKey#UP}, {@link DialogKey#DOWN}, {@link DialogKey#HOME}, {@link DialogKey#END}, {@link DialogKey#PAGE_UP} or {@link DialogKey#PAGE_DOWN}
     * @param rows the number of shown rows
     * @throws IllegalArgumentException if the key is not a navigation key
     */
    public void moveSelection(@NotNull DialogKey key, int rows) {
        int selected = this.selection;
        int last = this.items.size() - 1;
        int index;

        switch (key) {
            case PAGE_UP -> {
                if (selected <= 0) {
                    return;
                }

                index = rows < selected ? selected - rows + 1 : 0;
            }
            case PAGE_DOWN -> {
                if (selected == NO_SELECTION || last <= selected) {
                    return;
                }

                index = selected + rows < last ? selected + rows - 1 : last;
            }
            case END -> {
                if (selected == last) {
                    return;
                }

                index = last;
            }
            case HOME -> {
                if (selected == 0) {
                    return;
                }

                index = 0;
            }
            case UP -> {
                if (selected <= 0) {
                    return;
                }

                index = selected - 1;
            }
            case DOWN -> {
                if (selected == NO_SELECTION || last <= selected) {
                    return;
                }

                index = selected + 1;
            }
            default -> throw Exceptions.newIllegalArgumentException("{0} is not a list navigation key.", key);
        }

        this.select(index, rows);
    }

    /**
     * Turns the wheel one notch: a list with a scroll bar scrolls one row; one without moves the selection by the shown rows less one,
     * as Page Up and Page Down do, and does nothing without a selection.
     *
     * @param down       {@code true} for a notch toward the user
     * @param rows       the number of shown rows
     * @param scrollable {@code true} if the list has a scroll bar
     */
    public void scrollByWheel(boolean down, int rows, boolean scrollable) {
        if (scrollable) {
            this.scrollTo(this.firstRow + (down ? 1 : -1), rows);
        } else if (this.selection != NO_SELECTION) {
            this.moveSelection(down ? DialogKey.PAGE_DOWN : DialogKey.PAGE_UP, rows);
        }
    }
}
