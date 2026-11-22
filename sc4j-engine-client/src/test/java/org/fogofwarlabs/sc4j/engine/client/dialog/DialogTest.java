package org.fogofwarlabs.sc4j.engine.client.dialog;

import org.fogofwarlabs.sc4j.engine.client.HiddenWindowScenario;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.gl.GlCanvas;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.engine.resource.dialog.ControlKind;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.engine.resource.dialog.TextAlignment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests the dialog runtime with the default controls (presses and releases, check boxes and option groups, focus, Enter, Escape
 * and hotkeys, text input, lists, combo boxes and scroll bars) and its extension points: a mod's control class, a dialog subclass
 * with its own hit test and focus rule, the id lookup, control replacement and draw failures.
 */
final class DialogTest {
    private static final int ROW_HEIGHT = 10;
    private static final int OK = 1;
    private static final int CANCEL = 2;
    private static final int CHECK = 3;
    private static final int FIRST_OPTION = 4;
    private static final int SECOND_OPTION = 5;
    private static final int INPUT = 6;
    private static final int LIST = 7;
    private static final int COMBO = 8;
    private static final int SLIDER = 9;
    private static final int PANEL = 10;
    private static final int LABEL = 11;
    private static final int FIELD_PAGE_SIZE = 64;
    private static final int MARKER_COLOR = 0x283848;
    private static final int LOWER_MARKER = 21;
    private static final int UPPER_MARKER = 22;
    private static final int MARKER_SIZE = 50;
    private static final int MARKER_OFFSET = 25;
    private static final int OVERLAP = 30;

    /**
     * Measures nothing and draws nothing: the tests look at control state only.
     */
    private static final class EmptyFont implements Font {
        @Override
        public int getLineHeight() {
            return ROW_HEIGHT;
        }

        @Override
        public @NotNull TextStyle getDefaultStyle() {
            return TextStyle.DEFAULT;
        }

        @Override
        public float measure(@NotNull CharSequence text, @NotNull TextStyle style) {
            return 0;
        }

        @Override
        public float draw(@NotNull Canvas canvas, @NotNull CharSequence text, float x, float y, int color, @NotNull TextStyle style) {
            return x;
        }
    }

    /**
     * Records the activations, the hovered controls and the cancellations.
     */
    private static final class RecordingListener implements DialogListener {
        private final @NotNull List<Integer> activated;
        private final @NotNull List<Integer> hovered;
        private int cancelled;

        private RecordingListener() {
            this.activated = new ArrayList<>();
            this.hovered   = new ArrayList<>();
            this.cancelled = 0;
        }

        @Override
        public void onActivated(@NotNull Dialog dialog, @NotNull Control control) {
            this.activated.add(control.getDefinition().getId());
        }

        @Override
        public void onHovered(@NotNull Dialog dialog, @NotNull Control control) {
            this.hovered.add(control.getDefinition().getId());
        }

        @Override
        public void onCancelled(@NotNull Dialog dialog) {
            this.cancelled++;
        }
    }

    /**
     * A mod's control: takes presses and the focus, counts its presses and fails to draw while its image is missing.
     */
    private static final class MarkerControl extends Control {
        private int presses;
        private boolean imageMissing;
        private @Nullable Control replaced;

        private MarkerControl(@NotNull DialogControl definition) {
            super(definition);

            this.presses      = 0;
            this.imageMissing = false;
            this.replaced     = null;
        }

        @Override
        public boolean isPressable() {
            return true;
        }

        @Override
        public boolean isFocusable() {
            return true;
        }

        @Override
        protected void onPressed(float x, float y) {
            this.presses++;
        }

        @Override
        protected void onReplacing(@NotNull Control replaced) {
            this.replaced = replaced;
        }

        @Override
        protected void drawControl(@NotNull Canvas canvas, @NotNull DialogTheme theme, float x, float y) {
            if (this.imageMissing) {
                throw ControlDrawException.create(new IOException("missing"), "Marker {0} has no image.", this.getDefinition().getId());
            }

            canvas.fillRectangle(x, y, MARKER_SIZE, MARKER_SIZE, MARKER_COLOR);
        }
    }

    /**
     * A dialog with other rules: a press goes to the first control at the pointer (the lowest), and presses never move the focus.
     */
    private static final class FirstMatchDialog extends Dialog {
        private FirstMatchDialog(@NotNull DialogDefinition definition, @NotNull DialogListener listener) {
            super(definition, (dialog, control) -> new MarkerControl(control), listener, false);
        }

        @Override
        public @Nullable Control findControlAt(float x, float y) {
            for (Control control : this.getAllControls()) {
                if (control.isInteractive() && control.contains(x - control.getLeft(), y - control.getTop())) {
                    return control;
                }
            }

            return null;
        }

        @Override
        protected boolean isFocusedOnPress(@NotNull Control control) {
            return false;
        }
    }

    private static @NotNull DialogDefinition createMarkerDefinition() {
        return new DialogDefinition("markers", 0, 0, 200, 200, List.of(
                createControl(LOWER_MARKER, ControlKind.CUSTOM, 0, 0, MARKER_SIZE, MARKER_SIZE, Map.of()),
                createControl(UPPER_MARKER, ControlKind.CUSTOM, MARKER_OFFSET, MARKER_OFFSET, MARKER_SIZE, MARKER_SIZE, Map.of())
        ));
    }

    private static @NotNull DialogControl createControl(int id, @NotNull ControlKind kind, int x, int y, int width, int height,
                                                        @NotNull Map<String, String> attributes) {
        return new DialogControl(id, kind, x, y, width, height, "text", TextAlignment.LEFT, null, attributes, List.of());
    }

    /**
     * Creates a {@link Dialog} instance of 200 by 200 at 10, 20: buttons OK (default, hotkey o) and Cancel (cancel), a check box, two option
     * buttons of one group, a text input of at most 4 characters, a list, a combo box and a slider, and a label in a panel.
     *
     * @param listener the listener of the dialog
     * @param modal    {@code true} for a modal dialog
     * @return the created {@link Dialog} instance
     */
    private static @NotNull Dialog createDialog(@NotNull RecordingListener listener, boolean modal) {
        DialogControl label = createControl(LABEL, ControlKind.LABEL, 5, 5, 20, 10, Map.of());
        DialogControl panel = new DialogControl(PANEL, ControlKind.PANEL, 150, 150, 40, 40, null, TextAlignment.LEFT, null, Map.of(), List.of(label));
        List<DialogControl> controls = List.of(
                createControl(OK, ControlKind.BUTTON, 0, 0, 50, 20, Map.of(Control.DEFAULT_ATTRIBUTE, Control.TRUE_VALUE, Control.HOTKEY_ATTRIBUTE, "O")),
                createControl(CANCEL, ControlKind.BUTTON, 60, 0, 50, 20, Map.of(Control.CANCEL_ATTRIBUTE, Control.TRUE_VALUE)),
                createControl(CHECK, ControlKind.CHECK_BOX, 0, 30, 50, 10, Map.of()),
                createControl(FIRST_OPTION, ControlKind.OPTION_BUTTON, 0, 50, 50, 10, Map.of(CheckBox.GROUP_ATTRIBUTE, "speed")),
                createControl(SECOND_OPTION, ControlKind.OPTION_BUTTON, 0, 70, 50, 10, Map.of(CheckBox.GROUP_ATTRIBUTE, "speed")),
                createControl(INPUT, ControlKind.TEXT_INPUT, 0, 90, 80, 12, Map.of(TextInput.MAXIMUM_LENGTH_ATTRIBUTE, "4")),
                createControl(LIST, ControlKind.LIST_BOX, 100, 30, 50, 20, Map.of()),
                createControl(COMBO, ControlKind.COMBO_BOX, 100, 60, 50, 10, Map.of()),
                createControl(SLIDER, ControlKind.SCROLL_BAR, 0, 120, 100, 10, Map.of()),
                panel
        );

        return new Dialog(new DialogDefinition("test", 10, 20, 200, 200, controls), new DefaultControlFactory(ROW_HEIGHT), listener, modal);
    }

    private static @NotNull Control requireControl(@NotNull Dialog dialog, int id) {
        DialogControl definition = dialog.getDefinition().findControl(id);

        Assertions.assertNotNull(definition);

        Control control = dialog.getControl(definition);

        Assertions.assertNotNull(control);

        return control;
    }

    private static @Nullable Integer click(@NotNull Dialog dialog, float x, float y) {
        dialog.pressAt(x, y);

        Control control = dialog.release(x, y);

        return control == null ? null : control.getDefinition().getId();
    }

    @Test
    void release_should_activate_the_pressed_control_only_when_the_pointer_stays_on_it() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = createDialog(listener, false);

        Assertions.assertEquals(OK, click(dialog, 5, 5));

        dialog.pressAt(5, 5);

        Assertions.assertTrue(dialog.hasPressedControl());
        Assertions.assertNull(dialog.release(70, 5));
        Assertions.assertFalse(dialog.hasPressedControl());
        Assertions.assertNull(click(dialog, 190, 5));
        Assertions.assertEquals(List.of(OK), listener.activated);
    }

    @Test
    void check_boxes_toggle_and_option_buttons_clear_their_group() {
        Dialog dialog = createDialog(new RecordingListener(), false);
        CheckBox check = (CheckBox) requireControl(dialog, CHECK);
        CheckBox first = (CheckBox) requireControl(dialog, FIRST_OPTION);
        CheckBox second = (CheckBox) requireControl(dialog, SECOND_OPTION);

        click(dialog, 5, 35);

        Assertions.assertTrue(check.isChecked());

        click(dialog, 5, 35);

        Assertions.assertFalse(check.isChecked());

        click(dialog, 5, 55);
        click(dialog, 5, 75);

        Assertions.assertFalse(first.isChecked());
        Assertions.assertTrue(second.isChecked());
    }

    @Test
    void keys_move_the_focus_and_activate_default_cancel_and_hotkey_controls() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = createDialog(listener, false);

        Assertions.assertTrue(dialog.handleKey(DialogKey.TAB));
        Assertions.assertSame(requireControl(dialog, OK), dialog.getFocused());
        Assertions.assertTrue(dialog.handleKey(DialogKey.TAB));
        Assertions.assertSame(requireControl(dialog, CANCEL), dialog.getFocused());

        dialog.handleKey(DialogKey.ENTER);
        dialog.setFocused(requireControl(dialog, INPUT));
        dialog.handleKey(DialogKey.ENTER);
        dialog.handleKey(DialogKey.ESCAPE);
        dialog.setFocused(null);
        dialog.handleCharacter('o');

        Assertions.assertEquals(List.of(CANCEL, OK, CANCEL, OK), listener.activated);
        Assertions.assertEquals(0, listener.cancelled);
    }

    @Test
    void handle_input_should_route_pointer_key_character_and_wheel_events_to_the_controls() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = createDialog(listener, true);
        TextInput input = (TextInput) requireControl(dialog, INPUT);

        // The dialog lies at 10, 20: surface positions are relative to the window.
        Assertions.assertTrue(dialog.handleInput(InputEvent.createPointerMoved(15, 25)));
        Assertions.assertSame(requireControl(dialog, OK), dialog.getHovered());
        Assertions.assertTrue(dialog.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, 0, 15, 25, false)));
        Assertions.assertTrue(dialog.handleInput(InputEvent.createButtonReleased(PointerButton.LEFT, 0, 15, 25)));
        Assertions.assertFalse(dialog.handleInput(InputEvent.createButtonPressed(PointerButton.RIGHT, 0, 15, 25, false)), "right presses no control");
        Assertions.assertTrue(dialog.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, 0, 300, 300, false)),
                              "a modal dialog takes presses");

        input.setText("");
        dialog.handleInput(InputEvent.createButtonPressed(PointerButton.LEFT, 0, 15, 115, false));
        dialog.handleInput(InputEvent.createButtonReleased(PointerButton.LEFT, 0, 15, 115));
        dialog.handleInput(InputEvent.createCharacterTyped('a'));
        dialog.handleInput(InputEvent.createCharacterTyped('b'));
        dialog.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.BACKSPACE, 0));
        dialog.handleInput(InputEvent.createKey(InputEvent.Type.KEY_RELEASED, Key.BACKSPACE, 0));

        Assertions.assertEquals("a", input.getText());

        dialog.setFocused(null);

        Assertions.assertTrue(dialog.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.KEYPAD_ENTER, 0)));
        Assertions.assertFalse(dialog.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.F1, 0)));
        Assertions.assertFalse(dialog.handleInput(InputEvent.createWheelScrolled(0, 0, 15, 25)));
        Assertions.assertEquals(List.of(OK, OK), listener.activated);
    }

    @Test
    void set_hovered_should_report_each_control_the_pointer_moves_onto_once() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = createDialog(listener, false);
        Control cancel = requireControl(dialog, CANCEL);

        // The dialog lies at 10, 20: OK covers 10..60 and Cancel 70..120 across, both 20..40 down.
        dialog.handleInput(InputEvent.createPointerMoved(15, 25));
        dialog.handleInput(InputEvent.createPointerMoved(20, 30));
        dialog.handleInput(InputEvent.createPointerMoved(75, 25));
        dialog.handleInput(InputEvent.createPointerMoved(65, 25));
        dialog.handleInput(InputEvent.createPointerMoved(75, 25));
        dialog.replaceControl(cancel, new MarkerControl(cancel.getDefinition()));

        Assertions.assertEquals(List.of(OK, CANCEL, CANCEL), listener.hovered, "each move onto a control reports it once, a replacement does not");
    }

    @Test
    void text_input_edits_at_the_cursor_within_its_maximum_length() {
        Dialog dialog = createDialog(new RecordingListener(), false);
        TextInput input = (TextInput) requireControl(dialog, INPUT);

        input.setText("");
        click(dialog, 5, 95);

        for (char character : "abcde".toCharArray()) {
            Assertions.assertTrue(dialog.handleCharacter(character));
        }

        dialog.handleKey(DialogKey.LEFT);
        dialog.handleKey(DialogKey.BACKSPACE);
        dialog.handleKey(DialogKey.HOME);
        dialog.handleCharacter('x');

        Assertions.assertEquals("xabd", input.getText());
        Assertions.assertEquals(1, input.getCursor());
    }

    @Test
    void lists_combo_boxes_and_scroll_bars_select_and_set_values() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = createDialog(listener, false);
        ListBox list = (ListBox) requireControl(dialog, LIST);
        ComboBox combo = (ComboBox) requireControl(dialog, COMBO);
        ScrollBar slider = (ScrollBar) requireControl(dialog, SLIDER);

        list.setEntries(List.of("a", "b", "c"));

        Assertions.assertEquals(LIST, click(dialog, 105, 45));
        Assertions.assertEquals(1, list.getSelection());

        dialog.handleKey(DialogKey.DOWN);

        Assertions.assertEquals(2, list.getSelection());
        Assertions.assertEquals(1, list.getFirstVisibleRow());

        dialog.handleKey(DialogKey.PAGE_UP);

        Assertions.assertEquals(0, list.getSelection(), "2 is not past the two visible rows: the first row");

        combo.setEntries(List.of("slow", "fast"));

        Assertions.assertNull(click(dialog, 105, 65));
        Assertions.assertTrue(combo.isOpen());
        Assertions.assertSame(combo, dialog.findControlAt(105, 85));
        Assertions.assertEquals(COMBO, click(dialog, 105, 85));
        Assertions.assertEquals(1, combo.getSelection());
        Assertions.assertFalse(combo.isOpen());

        slider.setRange(0, 10);
        slider.setPage(3);
        dialog.pressAt(5, 125);
        dialog.drag(80, 125);

        Assertions.assertEquals(SLIDER, dialog.release(80, 125).getDefinition().getId());
        Assertions.assertEquals(9, slider.getValue(), "the dragged thumb: 11 * 75 / 90");
        Assertions.assertEquals(SLIDER, click(dialog, 30, 125));
        Assertions.assertEquals(6, slider.getValue(), "a press before the thumb pages back");
    }

    @Test
    void an_open_combo_box_takes_the_next_press_first_and_closes() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = createDialog(listener, false);
        ComboBox combo = (ComboBox) requireControl(dialog, COMBO);

        combo.setEntries(List.of("slow", "fast"));
        combo.setSelection(0);
        click(dialog, 105, 65);

        Assertions.assertTrue(combo.isOpen());
        Assertions.assertSame(combo, dialog.findControlAt(5, 5), "the open list takes the press over the OK button");
        Assertions.assertNull(click(dialog, 5, 5));
        Assertions.assertFalse(combo.isOpen());
        Assertions.assertEquals(0, combo.getSelection());
        Assertions.assertEquals(List.of(), listener.activated, "the OK button was not pressed");
    }

    @Test
    void hidden_or_disabled_controls_and_hidden_parents_take_no_presses() {
        Dialog dialog = createDialog(new RecordingListener(), false);

        requireControl(dialog, OK).setEnabled(false);
        requireControl(dialog, CANCEL).setVisible(false);
        requireControl(dialog, PANEL).setVisible(false);

        Assertions.assertNull(dialog.findControlAt(5, 5));
        Assertions.assertNull(dialog.findControlAt(65, 5));
        Assertions.assertFalse(requireControl(dialog, LABEL).isShown());
    }

    @Test
    void find_control_should_return_the_control_of_a_definition_id() {
        Dialog dialog = createDialog(new RecordingListener(), false);
        Control label = requireControl(dialog, LABEL);

        Assertions.assertSame(label, dialog.findControl(LABEL));
        Assertions.assertSame(dialog, label.getDialog());
        Assertions.assertNull(dialog.findControl(LABEL + PANEL));
    }

    @Test
    void hiding_the_pressed_control_ends_its_press() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = createDialog(listener, false);
        Control ok = requireControl(dialog, OK);

        dialog.pressAt(5, 5);
        ok.setVisible(false);

        Assertions.assertFalse(dialog.hasPressedControl());
        Assertions.assertFalse(ok.isPressed());

        ok.setVisible(true);

        Assertions.assertNull(dialog.release(5, 5));
        Assertions.assertEquals(List.of(), listener.activated);
    }

    @Test
    void mod_controls_take_presses_through_the_default_rules() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = new Dialog(createMarkerDefinition(), (owner, control) -> new MarkerControl(control), listener, false);
        MarkerControl upper = (MarkerControl) requireControl(dialog, UPPER_MARKER);

        Assertions.assertEquals(UPPER_MARKER, click(dialog, OVERLAP, OVERLAP));
        Assertions.assertEquals(1, upper.presses);
        Assertions.assertSame(upper, dialog.getFocused());
        Assertions.assertEquals(List.of(UPPER_MARKER), listener.activated);
    }

    @Test
    void dialog_subclasses_replace_the_hit_test_and_the_focus_rule() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = new FirstMatchDialog(createMarkerDefinition(), listener);
        MarkerControl lower = (MarkerControl) requireControl(dialog, LOWER_MARKER);

        Assertions.assertEquals(LOWER_MARKER, click(dialog, OVERLAP, OVERLAP));
        Assertions.assertEquals(1, lower.presses);
        Assertions.assertNull(dialog.getFocused());

        dialog.cancelPress();
        dialog.pressAt(OVERLAP, OVERLAP);
        dialog.cancelPress();

        Assertions.assertNull(dialog.release(OVERLAP, OVERLAP));
        Assertions.assertEquals(List.of(LOWER_MARKER), listener.activated);
    }

    @Test
    void replace_control_should_put_the_replacement_in_the_lookups_the_drawing_order_and_the_live_state() {
        RecordingListener listener = new RecordingListener();
        Dialog dialog = createDialog(listener, false);
        Control cancel = requireControl(dialog, CANCEL);
        MarkerControl marker = new MarkerControl(cancel.getDefinition());

        cancel.setText("Back");
        dialog.setHovered(cancel);
        dialog.setFocused(requireControl(dialog, INPUT));

        Assertions.assertSame(marker, dialog.replaceControl(cancel, marker));
        Assertions.assertSame(marker, dialog.getControl(cancel.getDefinition()));
        Assertions.assertSame(marker, dialog.findControl(CANCEL));
        Assertions.assertSame(marker, dialog.getControls().get(1));
        Assertions.assertFalse(dialog.getAllControls().contains(cancel));
        Assertions.assertSame(dialog, marker.getDialog());
        Assertions.assertNull(cancel.getDialog());
        Assertions.assertSame(cancel, marker.replaced);
        Assertions.assertEquals("Back", marker.getText());
        Assertions.assertSame(marker, dialog.getHovered());
        Assertions.assertTrue(marker.isHovered());
        Assertions.assertFalse(cancel.isHovered());

        Assertions.assertEquals(CANCEL, click(dialog, 65, 5));
        Assertions.assertEquals(1, marker.presses);
        Assertions.assertEquals(List.of(CANCEL), listener.activated);
    }

    @Test
    void replace_control_should_keep_the_parent_the_hidden_state_and_refuse_foreign_controls() {
        Dialog dialog = createDialog(new RecordingListener(), false);
        Control panel = requireControl(dialog, PANEL);
        Control label = requireControl(dialog, LABEL);
        MarkerControl marker = new MarkerControl(label.getDefinition());

        label.setVisible(false);
        dialog.replaceControl(label, marker);

        Assertions.assertSame(panel, marker.getParent());
        Assertions.assertEquals(List.of(marker), panel.getChildren());
        Assertions.assertNull(label.getParent());
        Assertions.assertFalse(marker.isVisible());
        Assertions.assertThrows(IllegalArgumentException.class, () -> dialog.replaceControl(label, new MarkerControl(label.getDefinition())));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> dialog.replaceControl(requireControl(dialog, OK), new MarkerControl(label.getDefinition())));
        Assertions.assertThrows(IllegalArgumentException.class, () -> dialog.replaceControl(requireControl(dialog, OK), marker));
    }

    @Test
    void draw_should_report_a_failed_resource_as_a_control_draw_exception() {
        HiddenWindowScenario.run(() -> {
            try (GlCanvas canvas = new GlCanvas(FIELD_PAGE_SIZE)) {
                Dialog dialog = new Dialog(createMarkerDefinition(), (owner, control) -> new MarkerControl(control), new RecordingListener(), false);
                MarkerControl upper = (MarkerControl) requireControl(dialog, UPPER_MARKER);
                DialogTheme theme = DialogTheme.createDefault(new EmptyFont());

                canvas.begin(HiddenWindowScenario.SIZE, HiddenWindowScenario.SIZE);
                dialog.draw(canvas, theme);
                upper.imageMissing = true;

                ControlDrawException exception = Assertions.assertThrows(ControlDrawException.class, () -> dialog.draw(canvas, theme));

                canvas.end();

                Assertions.assertInstanceOf(IOException.class, exception.getCause());
                Assertions.assertEquals("Marker 22 has no image.", exception.getMessage());
            }
        });
    }
}
