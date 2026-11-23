package org.fogofwarlabs.sc4j.engine.client.dialog;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.PointerButton;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Runs one dialog of the resource model: its live controls, the pointer, the focus, Enter, Escape and hotkeys, and draws it.
 * <p>
 * A press goes to the topmost shown, enabled control that takes presses at the pointer (an open list first) and focuses it
 * when it takes the focus; its release on the same control activates it. Keys and characters go to the focused control
 * first. Tab moves the focus through the focusable controls in drawing order; Enter activates the focused control, else the
 * default control; Escape activates the cancel control, else reports {@link DialogListener#onCancelled}; a typed hotkey
 * activates the first control with it. Activations, and each control the pointer moves onto, are reported to the {@link DialogListener}.
 * <p>
 * A mod whose dialogs follow other rules extends this class: {@link #findControlAt} and {@link #findHoverAt} choose the control a
 * press or the pointer goes to, {@link #isFocusedOnPress} whether a press moves the focus, and its own key routing can replace
 * {@link #handleKey} and {@link #handleCharacter}; the live state stays here (the pressed, hovered and focused {@link Control}s),
 * so the {@link DialogStack} and the controls work the same for every dialog. Controls are addressed as objects;
 * {@link #findControl(int)} finds one by the id its definition gives, for content that names controls by id. A subclass whose
 * factory builds one control class may narrow {@link #getControl} and {@link #findControl} to it.
 * <p>
 * Content that gives a control its behaviour after the dialog is built puts a control of its own class in the built control's
 * place with {@link #replaceControl}: the lookups, the drawing order, the parent and the live state then name the replacement.
 */
public class Dialog implements DialogLayer<Control> {
    private static boolean isPressableAt(@NotNull Control control, float x, float y) {
        return control.isShown() && control.isEnabled() && control.isPressable() && control.contains(x - control.getLeft(), y - control.getTop());
    }


    private final @NotNull DialogDefinition definition;
    private final @NotNull DialogListener listener;
    private final boolean modal;

    private final @NotNull List<Control> controls;
    private final @NotNull List<Control> allControls;
    private final @NotNull Map<DialogControl, Control> controlsByDefinition;
    private final @NotNull Map<Integer, Control> controlsById;

    private float left;
    private float top;

    private @Nullable Control hovered;
    private @Nullable Control pressed;
    private @Nullable Control focused;

    /**
     * Creates a {@link Dialog} instance at the position its definition gives and builds its controls.
     *
     * @param definition the dialog of the resource model
     * @param factory    the factory of the live controls
     * @param listener   the receiver of activations
     * @param modal      {@code true} if the dialog takes all input while open
     */
    // The factory and the built controls receive the dialog under construction by contract (ControlFactory.createControl);
    // the constructor calls no method a subclass overrides.
    @SuppressWarnings("this-escape")
    public Dialog(@NotNull DialogDefinition definition, @NotNull ControlFactory factory, @NotNull DialogListener listener, boolean modal) {
        this.definition = definition;
        this.listener   = listener;
        this.modal      = modal;

        this.controls             = new ArrayList<>();
        this.allControls          = new ArrayList<>();
        this.controlsByDefinition = new HashMap<>();
        this.controlsById         = new HashMap<>();

        this.left = definition.getX();
        this.top  = definition.getY();

        this.hovered = null;
        this.pressed = null;
        this.focused = null;

        for (DialogControl control : definition.getControls()) {
            this.controls.add(this.buildControl(factory, control, null));
        }

        // Ids name top-level controls first; a child is found by id only when no top-level control has it.
        for (Control control : this.controls) {
            this.controlsById.putIfAbsent(control.getDefinition().getId(), control);
        }

        for (Control control : this.allControls) {
            this.controlsById.putIfAbsent(control.getDefinition().getId(), control);
        }
    }

    private @NotNull Control buildControl(@NotNull ControlFactory factory, @NotNull DialogControl definition, @Nullable Control parent) {
        Control control = factory.createControl(this, definition);

        control.attach(this);

        if (parent != null) {
            parent.addChild(control);
        }

        this.allControls.add(control);
        this.controlsByDefinition.put(definition, control);

        for (DialogControl child : definition.getChildren()) {
            this.buildControl(factory, child, control);
        }

        return control;
    }

    /**
     * @return the dialog of the resource model
     */
    public final @NotNull DialogDefinition getDefinition() {
        return this.definition;
    }

    /**
     * @return the top-level controls in drawing order, unmodifiable
     */
    public final @NotNull List<Control> getControls() {
        return Collections.unmodifiableList(this.controls);
    }

    /**
     * @return every control, depth first in drawing order, unmodifiable
     */
    public final @NotNull List<Control> getAllControls() {
        return Collections.unmodifiableList(this.allControls);
    }

    /**
     * Finds the live control of a definition.
     *
     * @param definition the control's definition, one of this dialog's
     * @return the live control, or {@code null} if the definition is not one of this dialog's
     */
    public @Nullable Control getControl(@NotNull DialogControl definition) {
        return this.controlsByDefinition.get(definition);
    }

    /**
     * Finds a control by the id its definition gives, for content that names controls by id.
     *
     * @param id the id of the control's definition
     * @return the first top-level control with the id in drawing order, else the first child with it, or {@code null} if no control has it
     */
    public @Nullable Control findControl(int id) {
        return this.controlsById.get(id);
    }

    /**
     * @return the left edge of the dialog in stack coordinates
     */
    @Override
    public final float getLeft() {
        return this.left;
    }

    /**
     * @return the top edge of the dialog in stack coordinates
     */
    @Override
    public final float getTop() {
        return this.top;
    }

    /**
     * Moves the dialog.
     *
     * @param left the left edge in stack coordinates
     * @param top  the top edge in stack coordinates
     */
    public final void setPosition(float left, float top) {
        this.left = left;
        this.top  = top;
    }

    /**
     * @return {@code true} if the dialog takes all input while open, {@code false} otherwise
     */
    @Override
    public final boolean isModal() {
        return this.modal;
    }

    /**
     * @return {@code true} if a control of the dialog holds a press, {@code false} otherwise
     */
    @Override
    public final boolean hasPressedControl() {
        return this.pressed != null;
    }

    /**
     * @return the hovered control, or {@code null}
     */
    public final @Nullable Control getHovered() {
        return this.hovered;
    }

    /**
     * Sets the hovered control; a control that was not hovered before is reported to the listener ({@link DialogListener#onHovered}).
     *
     * @param control the hovered control, or {@code null} for none
     */
    @Override
    public final void setHovered(@Nullable Control control) {
        Control previous = this.hovered;

        if (previous != null) {
            previous.setHovered(false);
        }

        this.hovered = control;

        if (control != null) {
            control.setHovered(true);

            if (control != previous) {
                this.listener.onHovered(this, control);
            }
        }
    }

    /**
     * @return the control holding the press, or {@code null}
     */
    public final @Nullable Control getPressed() {
        return this.pressed;
    }

    /**
     * @return the focused control, or {@code null}
     */
    public final @Nullable Control getFocused() {
        return this.focused;
    }

    /**
     * Moves the focus.
     *
     * @param control the control that takes the focus, or {@code null} to clear it; a control that does not take the focus clears it too
     */
    public final void setFocused(@Nullable Control control) {
        Control next = control != null && control.isFocusable() ? control : null;

        if (this.focused == next) {
            return;
        }

        if (this.focused != null) {
            this.focused.setFocused(false);
        }

        this.focused = next;

        if (next != null) {
            next.setFocused(true);
        }
    }


    /**
     * Puts a control in the place of a built control of the same definition: content that turns a control into a richer one after
     * the dialog is built (a list its screen fills, a widget a mod attaches) uses it instead of a factory. The replacement takes the
     * replaced control's place in drawing order and in the lookups ({@link #getControl}, {@link #findControl(int)}), its parent and
     * children, its shown, enabled and text state, and the hover, press and focus it held (the focus only when the replacement takes
     * it); then {@link Control#onReplacing} lets the replacement take over its own state. The replaced control leaves the dialog.
     *
     * @param <T>         the class of the replacement
     * @param control     a control of this dialog
     * @param replacement the new control: of the same definition, not in a dialog and without children
     * @return the replacement, now a control of this dialog
     * @throws IllegalArgumentException if the control is not one of this dialog's, or the replacement has another definition, is in a dialog or has children
     */
    public final <T extends Control> @NotNull T replaceControl(@NotNull Control control, @NotNull T replacement) {
        DialogControl definition = control.getDefinition();

        if (control.getDialog() != this || !this.allControls.contains(control)) {
            throw Exceptions.newIllegalArgumentException("Control {0} is not one of the controls of dialog {1}.",
                                                         definition.getId(), this.definition.getName());
        }

        if (replacement.getDefinition() != definition || replacement.getDialog() != null || !replacement.getChildren().isEmpty()) {
            throw Exceptions.newIllegalArgumentException("The replacement of control {0} must have its definition, no dialog and no children.",
                                                         definition.getId());
        }

        boolean hovered = this.hovered == control;
        boolean pressed = this.pressed == control;
        boolean focused = this.focused == control;

        if (hovered) {
            this.setHovered(null);
        }

        if (pressed) {
            this.cancelPress();
        }

        if (focused) {
            this.setFocused(null);
        }

        int index = this.controls.indexOf(control);

        if (index >= 0) {
            this.controls.set(index, replacement);
        }

        this.allControls.set(this.allControls.indexOf(control), replacement);
        this.controlsByDefinition.put(definition, replacement);
        this.controlsById.replaceAll((id, value) -> value == control ? replacement : value);

        control.replaceWith(replacement);

        if (hovered) {
            this.hovered = replacement;
            replacement.setHovered(true);
        }

        if (pressed) {
            this.pressed = replacement;
            replacement.setPressed(true);
        }

        if (focused) {
            this.setFocused(replacement);
        }

        return replacement;
    }

    /**
     * Finds the control a press at a point goes to: an open list first, else the topmost shown, enabled control that takes
     * presses there.
     *
     * @param x the x relative to the dialog
     * @param y the y relative to the dialog
     * @return the control, or {@code null}
     */
    @Override
    public @Nullable Control findControlAt(float x, float y) {
        for (Control control : this.allControls) {
            if (control.isOverlayOpen() && isPressableAt(control, x, y)) {
                return control;
            }
        }

        for (int index = this.allControls.size() - 1; index >= 0; index--) {
            Control control = this.allControls.get(index);

            if (isPressableAt(control, x, y)) {
                return control;
            }
        }

        return null;
    }

    /**
     * Finds the control the pointer hovers at a point: the one a press there goes to.
     *
     * @param x the x relative to the dialog
     * @param y the y relative to the dialog
     * @return the control, or {@code null}
     */
    @Override
    public @Nullable Control findHoverAt(float x, float y) {
        return this.findControlAt(x, y);
    }

    /**
     * Presses at a point: finds the control there ({@link #findControlAt}) and presses it.
     *
     * @param x the x relative to the dialog
     * @param y the y relative to the dialog
     * @return the pressed control, or {@code null} for the dialog's empty area
     */
    public final @Nullable Control pressAt(float x, float y) {
        Control control = this.findControlAt(x, y);

        this.press(control, x, y);

        return control;
    }

    /**
     * Marks a control pressed and focuses it, or presses the empty area.
     *
     * @param control the control the press went to, or {@code null} for the dialog's empty area
     * @param x       the x of the press relative to the dialog
     * @param y       the y of the press relative to the dialog
     */
    @Override
    public final void press(@Nullable Control control, float x, float y) {
        if (this.pressed != null) {
            this.pressed.setPressed(false);
        }

        this.pressed = control;

        if (control == null) {
            return;
        }

        control.setPressed(true);

        if (this.isFocusedOnPress(control)) {
            this.setFocused(control);
        }

        control.onPressed(x - control.getLeft(), y - control.getTop());
    }

    /**
     * Checks whether a press moves the focus to the pressed control.
     *
     * @param control the pressed control
     * @return {@code true} if the control takes the focus ({@link Control#isFocusable}), {@code false} otherwise
     */
    protected boolean isFocusedOnPress(@NotNull Control control) {
        return control.isFocusable();
    }

    /**
     * Ends the held press without a release, so nothing activates.
     */
    public final void cancelPress() {
        Control control = this.pressed;

        this.pressed = null;

        if (control != null) {
            control.setPressed(false);
        }
    }

    /**
     * Moves the pointer while a control holds the press.
     *
     * @param x the x relative to the dialog
     * @param y the y relative to the dialog
     */
    public final void drag(float x, float y) {
        Control control = this.pressed;

        if (control != null) {
            control.onDragged(x - control.getLeft(), y - control.getTop());
        }
    }

    /**
     * Releases the held press; the control activates when the pointer is still on it and reports it to the listener.
     *
     * @param x the x relative to the dialog
     * @param y the y relative to the dialog
     * @return the activated control, or {@code null}
     */
    @Override
    public final @Nullable Control release(float x, float y) {
        Control control = this.pressed;

        this.pressed = null;

        if (control == null) {
            return null;
        }

        control.setPressed(false);

        if (this.findControlAt(x, y) != control || !control.onReleased(x - control.getLeft(), y - control.getTop())) {
            return null;
        }

        this.listener.onActivated(this, control);

        return control;
    }

    /**
     * Takes a key: the focused control first, then Tab, Enter and Escape.
     *
     * @param key the key
     * @return {@code true} if the dialog used the key, {@code false} otherwise
     */
    public boolean handleKey(@NotNull DialogKey key) {
        Control focused = this.focused;

        if (focused != null && focused.isShown() && focused.isEnabled() && focused.handleKey(key)) {
            return true;
        }

        return switch (key) {
            case TAB -> this.focusNext();
            case ENTER -> this.enter();
            case ESCAPE -> this.escape();
            default -> false;
        };
    }

    private boolean enter() {
        Control focused = this.focused;

        if (focused != null && focused.isShown() && focused.isEnabled() && focused.activate()) {
            this.listener.onActivated(this, focused);

            return true;
        }

        for (Control control : this.allControls) {
            if (control.isDefault() && control.isShown() && control.isEnabled()) {
                return this.activate(control);
            }
        }

        return false;
    }

    private boolean escape() {
        for (Control control : this.allControls) {
            if (control.isCancel() && control.isShown() && control.isEnabled()) {
                return this.activate(control);
            }
        }

        this.listener.onCancelled(this);

        return true;
    }

    private boolean activate(@NotNull Control control) {
        if (control.activate()) {
            this.listener.onActivated(this, control);
        }

        return true;
    }

    /**
     * Moves the focus to the next shown, enabled, focusable control in drawing order, wrapping around.
     *
     * @return {@code true} if a control took the focus, {@code false} if none takes it
     */
    public final boolean focusNext() {
        int count = this.allControls.size();
        int start = this.focused == null ? -1 : this.allControls.indexOf(this.focused);

        for (int step = 1; step <= count; step++) {
            Control control = this.allControls.get(Math.floorMod(start + step, count));

            if (control.isShown() && control.isEnabled() && control.isFocusable()) {
                this.setFocused(control);

                return true;
            }
        }

        return false;
    }

    /**
     * Takes a typed character: the focused control first, else the first shown, enabled control whose hotkey it is, compared
     * without case, activates.
     *
     * @param codePoint the character
     * @return {@code true} if the dialog used the character, {@code false} otherwise
     */
    public boolean handleCharacter(int codePoint) {
        Control focused = this.focused;

        if (focused != null && focused.isShown() && focused.isEnabled() && focused.handleCharacter(codePoint)) {
            return true;
        }

        int key = Character.toLowerCase(codePoint);

        for (Control control : this.allControls) {
            int hotkey = control.getHotkey();

            if (hotkey != Control.NO_HOTKEY && Character.toLowerCase(hotkey) == key && control.isShown() && control.isEnabled()) {
                return this.activate(control);
            }
        }

        return false;
    }

    /**
     * Takes wheel steps: the hovered control, else the focused one.
     *
     * @param steps the steps, positive toward the end
     * @return {@code true} if a control used the steps, {@code false} otherwise
     */
    public boolean handleWheel(int steps) {
        Control hovered = this.hovered;
        Control focused = this.focused;

        if (hovered != null && hovered.handleWheel(steps)) {
            return true;
        }

        return focused != null && focused.handleWheel(steps);
    }

    /**
     * Handles an input event with the left button as the pressing button: a pointer movement drags the held press or updates the hovered
     * control, a left press and release go to {@link #pressAt} and {@link #release}, a key press or repeat to {@link #handleKey}
     * ({@link DialogKey#find}), a typed character to {@link #handleCharacter} and a wheel movement to {@link #handleWheel}, one step per
     * wheel unit toward the user. Pointer positions are relative to the dialog's surface; the dialog's position is subtracted.
     *
     * @param event the event
     * @return {@code true} if the dialog used the event (a modal dialog uses every pointer press and release), {@code false} otherwise
     */
    public boolean handleInput(@NotNull InputEvent event) {
        float x = event.getX() - this.left;
        float y = event.getY() - this.top;

        return switch (event.getType()) {
            case POINTER_MOVED -> {
                if (this.pressed != null) {
                    this.drag(x, y);
                } else {
                    this.setHovered(this.findHoverAt(x, y));
                }

                yield this.pressed != null || this.hovered != null;
            }
            case BUTTON_PRESSED -> event.getButton() == PointerButton.LEFT && (this.pressAt(x, y) != null || this.modal);
            case BUTTON_RELEASED -> {
                if (event.getButton() != PointerButton.LEFT || this.pressed == null) {
                    yield this.modal;
                }

                this.release(x, y);

                yield true;
            }
            case KEY_PRESSED, KEY_REPEATED -> {
                DialogKey key = DialogKey.find(event.getKey());

                yield key != null && this.handleKey(key);
            }
            case CHARACTER_TYPED -> this.handleCharacter(event.getCodePoint());
            case WHEEL_SCROLLED -> {
                int steps = (int) -Math.round(event.getVerticalOffset());

                yield steps != 0 && this.handleWheel(steps);
            }
            case KEY_RELEASED -> false;
        };
    }

    /**
     * Draws the controls in drawing order, then the parts shown over all controls.
     *
     * @param canvas the canvas that draws, between its {@link Canvas#begin} and {@link Canvas#end}
     * @param theme  the look of the controls
     * @throws ControlDrawException if a resource a control draws cannot be read
     */
    public void draw(@NotNull Canvas canvas, @NotNull DialogTheme theme) {
        for (Control control : this.controls) {
            control.draw(canvas, theme, this.left, this.top);
        }

        for (Control control : this.allControls) {
            if (control.isShown()) {
                control.drawOverlay(canvas, theme, this.left + control.getLeft(), this.top + control.getTop());
            }
        }
    }
}
