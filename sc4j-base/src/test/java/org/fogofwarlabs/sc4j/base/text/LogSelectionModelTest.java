package org.fogofwarlabs.sc4j.base.text;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests the selection across the lines of a read-only log and its copied text.
 */
final class LogSelectionModelTest {
    private static final @NotNull List<String> LINES = List.of("alice: gg", "bob: wp", "carol: rematch?");

    @Test
    void drag_should_select_across_lines_one_message_per_line() {
        LogSelectionModel selection = new LogSelectionModel();

        selection.press(0, 7, false);
        selection.drag(2, 5);
        selection.release();

        Assertions.assertTrue(selection.hasSelection());
        Assertions.assertFalse(selection.isDragging());
        Assertions.assertEquals("gg\nbob: wp\ncarol", selection.getSelectedText(LINES));
        Assertions.assertTrue(selection.isSelected(1));
        Assertions.assertEquals(7, selection.getSelectionStart(0));
        Assertions.assertEquals(9, selection.getSelectionEnd(0, LINES.get(0).length()));
        Assertions.assertEquals(0, selection.getSelectionStart(2));
        Assertions.assertEquals(5, selection.getSelectionEnd(2, LINES.get(2).length()));
    }

    @Test
    void drag_upward_should_select_the_same_text() {
        LogSelectionModel selection = new LogSelectionModel();

        selection.press(2, 5, false);
        selection.drag(0, 7);

        Assertions.assertEquals("gg\nbob: wp\ncarol", selection.getSelectedText(LINES));
    }

    @Test
    void press_should_end_the_selection_and_extend_it_with_shift() {
        LogSelectionModel selection = new LogSelectionModel();

        selection.press(1, 0, false);
        selection.drag(1, 3);
        selection.release();
        selection.press(0, 0, false);
        selection.release();

        Assertions.assertFalse(selection.hasSelection());
        Assertions.assertEquals("", selection.getSelectedText(LINES));

        // A Shift press extends from the last press.
        selection.press(1, 3, true);
        selection.release();

        Assertions.assertEquals("alice: gg\nbob", selection.getSelectedText(LINES));
    }

    @Test
    void drag_should_do_nothing_without_a_press() {
        LogSelectionModel selection = new LogSelectionModel();

        selection.drag(1, 3);

        Assertions.assertFalse(selection.hasSelection());
    }

    @Test
    void select_word_should_select_the_word_under_a_double_press() {
        LogSelectionModel selection = new LogSelectionModel();

        selection.selectWord(2, LINES.get(2), 9);

        Assertions.assertEquals("rematch", selection.getSelectedText(LINES));
    }

    @Test
    void remove_first_lines_should_keep_the_selected_text() {
        LogSelectionModel selection = new LogSelectionModel();

        selection.press(1, 5, false);
        selection.drag(2, 5);
        selection.removeFirstLines(1);

        Assertions.assertEquals("wp\ncarol", selection.getSelectedText(LINES.subList(1, LINES.size())));

        // An end on a dropped line goes to the start of the first line left.
        selection.removeFirstLines(1);

        Assertions.assertEquals("carol", selection.getSelectedText(LINES.subList(2, LINES.size())));

        selection.removeFirstLines(1);

        Assertions.assertFalse(selection.hasSelection());
    }

    @Test
    void clear_should_end_the_selection() {
        LogSelectionModel selection = new LogSelectionModel();

        selection.press(0, 0, false);
        selection.drag(0, 5);
        selection.clear();

        Assertions.assertFalse(selection.hasSelection());
        Assertions.assertFalse(selection.isDragging());
        Assertions.assertFalse(selection.isSelected(0));
    }
}
