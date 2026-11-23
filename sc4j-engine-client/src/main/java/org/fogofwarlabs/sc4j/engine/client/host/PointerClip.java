package org.fogofwarlabs.sc4j.engine.client.host;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes the rectangle of a window's content area that a captured pointer stays in ({@link HostWindow#setPointerClip}), in framebuffer
 * pixels from the content area's top left corner; the right and bottom edges are exclusive. The window rounds it to whole window coordinates
 * and keeps it inside the content area.
 */
public final class PointerClip {
    /**
     * The index of the left edge in the window bounds of a clip ({@link #toWindowBounds}).
     */
    static final int LEFT_INDEX = 0;
    /**
     * The index of the top edge in the window bounds of a clip.
     */
    static final int TOP_INDEX = 1;
    /**
     * The index of the exclusive right edge in the window bounds of a clip.
     */
    static final int RIGHT_INDEX = 2;
    /**
     * The index of the exclusive bottom edge in the window bounds of a clip.
     */
    static final int BOTTOM_INDEX = 3;

    private static final int HASH_MULTIPLIER = 31;

    /**
     * Creates a {@link PointerClip} instance.
     *
     * @param left   the first framebuffer column inside
     * @param top    the first framebuffer row inside
     * @param right  the framebuffer column after the rectangle, at least the left edge
     * @param bottom the framebuffer row after the rectangle, at least the top edge
     * @return the created {@link PointerClip} instance
     * @throws IllegalArgumentException if an edge is not finite or the edges are reversed
     */
    public static @NotNull PointerClip of(float left, float top, float right, float bottom) {
        if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right) || !Float.isFinite(bottom) || right < left || bottom < top) {
            throw Exceptions.newIllegalArgumentException("Invalid pointer clip: {0}, {1} to {2}, {3}", left, top, right, bottom);
        }

        return new PointerClip(left, top, right, bottom);
    }

    /**
     * Tests whether a clip holds the pointer: only the captured pointer of a focused window that is not minimized or closed is clipped; otherwise
     * the clip is released and the pointer moves freely (or within the window, as {@link PointerMode#CAPTURED} keeps it).
     *
     * @param clip      the window's clip, or {@code null} for none
     * @param mode      the window's pointer mode
     * @param focused   whether the window has the input focus
     * @param minimized whether the window is minimized
     * @param closed    whether the window is closed
     * @return {@code true} if the clip applies, {@code false} if it is released
     */
    static boolean isApplied(@Nullable PointerClip clip, @NotNull PointerMode mode, boolean focused, boolean minimized, boolean closed) {
        return clip != null && mode == PointerMode.CAPTURED && focused && !minimized && !closed;
    }

    /**
     * Converts a framebuffer coordinate to a whole window coordinate inside the content area.
     *
     * @param value             the framebuffer coordinate
     * @param windowLength      the content area's length in window coordinates
     * @param framebufferLength the framebuffer's length in pixels
     * @return the rounded window coordinate, from {@code 0} to the window length
     */
    private static int toWindow(float value, int windowLength, int framebufferLength) {
        return Math.clamp(Math.round(value * windowLength / Math.max(1, framebufferLength)), 0, windowLength);
    }

    private final float left;
    private final float top;
    private final float right;
    private final float bottom;

    private PointerClip(float left, float top, float right, float bottom) {
        this.left   = left;
        this.top    = top;
        this.right  = right;
        this.bottom = bottom;
    }

    /**
     * @return the first framebuffer column inside
     */
    public float getLeft() {
        return this.left;
    }

    /**
     * @return the first framebuffer row inside
     */
    public float getTop() {
        return this.top;
    }

    /**
     * @return the framebuffer column after the rectangle
     */
    public float getRight() {
        return this.right;
    }

    /**
     * @return the framebuffer row after the rectangle
     */
    public float getBottom() {
        return this.bottom;
    }


    /**
     * Converts the clip to whole window coordinates: each edge scaled from the framebuffer to the window, rounded and kept inside the content
     * area.
     *
     * @param windowWidth       the content area's width in window coordinates
     * @param windowHeight      the content area's height in window coordinates
     * @param framebufferWidth  the framebuffer's width in pixels
     * @param framebufferHeight the framebuffer's height in pixels
     * @return {@code {left, top, right, bottom}} (the {@code *_INDEX} indexes, exclusive right and bottom), or {@code null} if the clip holds no whole window coordinate
     */
    int @Nullable [] toWindowBounds(int windowWidth, int windowHeight, int framebufferWidth, int framebufferHeight) {
        int[] bounds = {toWindow(this.left, windowWidth, framebufferWidth), toWindow(this.top, windowHeight, framebufferHeight),
                        toWindow(this.right, windowWidth, framebufferWidth), toWindow(this.bottom, windowHeight, framebufferHeight)};

        return bounds[RIGHT_INDEX] > bounds[LEFT_INDEX] && bounds[BOTTOM_INDEX] > bounds[TOP_INDEX] ? bounds : null;
    }

    /**
     * @param object the object to compare
     * @return {@code true} if the object is a {@link PointerClip} with the same edges, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof PointerClip other && Float.compare(this.left, other.left) == 0 && Float.compare(this.top, other.top) == 0 &&
               Float.compare(this.right, other.right) == 0 && Float.compare(this.bottom, other.bottom) == 0;
    }

    /**
     * @return the hash code of the edges
     */
    @Override
    public int hashCode() {
        int hash = Float.hashCode(this.left);

        hash = HASH_MULTIPLIER * hash + Float.hashCode(this.top);
        hash = HASH_MULTIPLIER * hash + Float.hashCode(this.right);

        return HASH_MULTIPLIER * hash + Float.hashCode(this.bottom);
    }

    /**
     * @return the edges, for logs
     */
    @Override
    public @NotNull String toString() {
        return "PointerClip[" + this.left + ", " + this.top + " to " + this.right + ", " + this.bottom + "]";
    }
}
