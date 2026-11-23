package org.fogofwarlabs.sc4j.engine.client.dialog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the open dialogs of a client in front-to-back input order and routes pointer and key input to them.
 * <p>
 * A pointer event goes to the first dialog, top first, with a control under the pointer, or to the first modal dialog on
 * the way; keys go to the top modal dialog, else to the top dialog. A hover update clears the hover of every other dialog.
 *
 * @param <C> the type of the dialogs' controls
 * @param <L> the type of the dialogs
 */
public final class DialogStack<C, L extends DialogLayer<C>> {
    /**
     * Names where a pointer event goes: a dialog and the control in it.
     *
     * @param <C> the type of the dialog's controls
     * @param <L> the type of the dialog
     */
    public static final class Target<C, L extends DialogLayer<C>> {
        private final @NotNull L layer;
        private final @Nullable C control;

        private Target(@NotNull L layer, @Nullable C control) {
            this.layer   = layer;
            this.control = control;
        }

        /**
         * @return the dialog that takes the input
         */
        public @NotNull L getLayer() {
            return this.layer;
        }

        /**
         * @return the control of the dialog, or {@code null} for the empty area of a modal dialog
         */
        public @Nullable C getControl() {
            return this.control;
        }
    }

    private final @NotNull List<L> layers;

    /**
     * Creates an empty {@link DialogStack} instance.
     */
    public DialogStack() {
        this.layers = new ArrayList<>();
    }

    /**
     * @return the open dialogs, top first, unmodifiable
     */
    public @NotNull List<L> getLayers() {
        return Collections.unmodifiableList(this.layers);
    }

    /**
     * @return the dialog drawn last and asked first, or {@code null} without dialogs
     */
    public @Nullable L getTop() {
        return this.layers.isEmpty() ? null : this.layers.getFirst();
    }

    /**
     * @return the top modal dialog, or {@code null} if no modal dialog is open
     */
    public @Nullable L getModal() {
        for (L layer : this.layers) {
            if (layer.isModal()) {
                return layer;
            }
        }

        return null;
    }


    /**
     * Opens a dialog over the others; a dialog already open moves to the top.
     *
     * @param layer the dialog
     */
    public void push(@NotNull L layer) {
        this.layers.remove(layer);
        this.layers.addFirst(layer);
    }

    /**
     * Closes a dialog.
     *
     * @param layer the dialog
     */
    public void remove(@NotNull L layer) {
        this.layers.remove(layer);
    }

    /**
     * Finds where a pointer event goes: the first dialog, top first, with a control under the pointer, or the first modal
     * dialog on the way.
     *
     * @param x the x in stack coordinates
     * @param y the y in stack coordinates
     * @return the dialog and control, or {@code null} when the input belongs to what lies under the dialogs
     */
    public @Nullable Target<C, L> findPointerTarget(float x, float y) {
        for (L layer : this.layers) {
            C control = layer.findControlAt(x - layer.getLeft(), y - layer.getTop());

            if (control != null || layer.isModal()) {
                return new Target<>(layer, control);
            }
        }

        return null;
    }

    /**
     * Presses the pointer: the dialog that takes it ({@link #findPointerTarget}) marks the control under it pressed.
     *
     * @param x the x in stack coordinates
     * @param y the y in stack coordinates
     * @return the dialog and the pressed control ({@code null} on a modal dialog's empty area), or {@code null} when the press belongs to what lies under the dialogs
     */
    public @Nullable Target<C, L> press(float x, float y) {
        Target<C, L> target = this.findPointerTarget(x, y);

        if (target != null) {
            L layer = target.getLayer();

            layer.press(target.getControl(), x - layer.getLeft(), y - layer.getTop());
        }

        return target;
    }

    /**
     * Releases the pointer: the first dialog that holds a press releases it.
     *
     * @param x the x in stack coordinates
     * @param y the y in stack coordinates
     * @return the dialog and the activated control ({@code null} when the pointer left the pressed control), or {@code null} when no dialog held a press
     */
    public @Nullable Target<C, L> release(float x, float y) {
        for (L layer : this.layers) {
            if (!layer.hasPressedControl()) {
                continue;
            }

            return new Target<>(layer, layer.release(x - layer.getLeft(), y - layer.getTop()));
        }

        return null;
    }

    /**
     * Sets the hovered control of every open dialog: the first dialog, top first, with a control under the pointer hovers it
     * (a modal dialog on the way takes the pointer); every other dialog hovers nothing.
     *
     * @param x the x in stack coordinates, or {@link Float#NaN} when the pointer is outside
     * @param y the y in stack coordinates
     * @return the hovered dialog and control, or {@code null}
     */
    public @Nullable Target<C, L> updateHover(float x, float y) {
        Target<C, L> target = null;

        if (!Float.isNaN(x)) {
            for (L layer : this.layers) {
                C control = layer.findHoverAt(x - layer.getLeft(), y - layer.getTop());

                if (control != null || layer.isModal()) {
                    target = new Target<>(layer, control);

                    break;
                }
            }
        }

        for (L layer : this.layers) {
            layer.setHovered(target != null && target.getLayer() == layer ? target.getControl() : null);
        }

        return target;
    }

    /**
     * Finds where a key goes: the top modal dialog, else the top dialog.
     *
     * @return the dialog, or {@code null} without dialogs
     */
    public @Nullable L findKeyTarget() {
        L modal = this.getModal();

        return modal != null ? modal : this.getTop();
    }
}
