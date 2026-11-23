package org.fogofwarlabs.sc4j.engine.client.world;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * Scrolls a camera by held keys or by the pointer at an edge of the scroll area: the engine's one camera scroll. Its {@link ScrollRate} makes
 * it stepped (one table step per update, {@link SteppedScrollRate}, updated at the caller's tick rate) or continuous (speed and time,
 * {@link ContinuousScrollRate}, updated every frame); the rules are the same for both.
 * <p>
 * Held scroll keys take precedence over the edge. The pointer within the edge width of the scroll area scrolls toward that edge while the
 * camera can still move that way, and that direction is the edge direction until the next edge test. After a key scroll the pointer must
 * move before an edge scrolls again, so a pointer resting at an edge does not take over when the keys are released. Each moving update
 * raises the acceleration step by one up to the last step of the rate ({@link ScrollRate#getStepCount()}); an update without movement resets
 * it. Every update moves the {@link CameraControl} to its position kept inside the camera's range, also when nothing scrolls. Edge and key
 * scrolling can each be turned off.
 */
public final class CameraScroll {
    /**
     * The default width of the edge band in pixels.
     */
    public static final int DEFAULT_EDGE_WIDTH = 4;
    /**
     * The edge direction while the pointer scrolls nothing.
     */
    public static final int NO_DIRECTION = -1;

    /**
     * The edge direction by {@code [vertical + 1][horizontal + 1]}: {@code 0} up, then clockwise to {@code 7} up-left.
     */
    private static final int @NotNull [][] DIRECTIONS = {{7, 0, 1}, {6, NO_DIRECTION, 2}, {5, 4, 3}};

    /**
     * Reads the scroll direction of the held keys: the diagonal keys (keypad 1, 3, 7, 9 and End, Page Down, Home, Page Up) before the arrow
     * and keypad 2, 4, 6, 8 keys.
     *
     * @param down whether a key is held
     * @return the horizontal and vertical direction pair, each {@code -1..1}
     */
    public static int @NotNull [] getKeyboardDirection(@NotNull Predicate<Key> down) {
        if (down.test(Key.KEYPAD_3) || down.test(Key.PAGE_DOWN)) {
            return new int[]{1, 1};
        }

        if (down.test(Key.KEYPAD_1) || down.test(Key.END)) {
            return new int[]{-1, 1};
        }

        if (down.test(Key.KEYPAD_7) || down.test(Key.HOME)) {
            return new int[]{-1, -1};
        }

        if (down.test(Key.KEYPAD_9) || down.test(Key.PAGE_UP)) {
            return new int[]{1, -1};
        }

        int directionY = down.test(Key.DOWN) || down.test(Key.KEYPAD_2) ? 1
                                                                        : down.test(Key.UP) || down.test(Key.KEYPAD_8) ? -1
                                                                                                                       : 0;
        int directionX = down.test(Key.RIGHT) || down.test(Key.KEYPAD_6) ? 1
                                                                         : down.test(Key.LEFT) || down.test(Key.KEYPAD_4) ? -1
                                                                                                                          : 0;

        return new int[]{directionX, directionY};
    }

    /**
     * Moves a position by a distance in a direction, kept between the smallest and the largest position; a largest position below the
     * smallest leaves only the smallest.
     *
     * @param position  the position
     * @param direction the direction {@code -1..1}
     * @param distance  the distance
     * @param minimum   the smallest position
     * @param maximum   the largest position
     * @return the moved position
     */
    static float move(float position, int direction, float distance, float minimum, float maximum) {
        return Math.clamp(direction == 0 ? position : position + direction * distance, minimum, Math.max(minimum, maximum));
    }

    private final int edgeWidth;
    private final @NotNull ScrollRate rate;

    private boolean edgeScrolling;
    private boolean keyScrolling;

    private int step;

    private boolean keyboardScrolled;
    private float lastPointerX;
    private float lastPointerY;
    private int edgeX;
    private int edgeY;

    /**
     * Creates a {@link CameraScroll} instance with edge and key scrolling on and no update yet.
     *
     * @param edgeWidth the width of the edge band of the scroll area in pixels
     * @param rate      the rate that gives the distance of an update
     * @throws IllegalArgumentException if the width is not positive
     */
    public CameraScroll(int edgeWidth, @NotNull ScrollRate rate) {
        if (edgeWidth <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid edge width {0}", edgeWidth);
        }

        this.edgeWidth = edgeWidth;
        this.rate      = rate;

        this.edgeScrolling = true;
        this.keyScrolling  = true;

        this.step = 0;

        this.keyboardScrolled = false;
        this.lastPointerX     = Float.NaN;
        this.lastPointerY     = Float.NaN;
        this.edgeX            = 0;
        this.edgeY            = 0;
    }

    /**
     * @return the width of the edge band in pixels
     */
    public int getEdgeWidth() {
        return this.edgeWidth;
    }

    /**
     * @return the rate that gives the distance of an update
     */
    public @NotNull ScrollRate getRate() {
        return this.rate;
    }

    /**
     * @return {@code true} if the pointer at an edge scrolls the camera, {@code false} otherwise
     */
    public boolean isEdgeScrolling() {
        return this.edgeScrolling;
    }

    /**
     * Sets whether the pointer at an edge scrolls the camera.
     *
     * @param edgeScrolling whether edge scrolling is on
     */
    public void setEdgeScrolling(boolean edgeScrolling) {
        this.edgeScrolling = edgeScrolling;
    }

    /**
     * @return {@code true} if held scroll keys scroll the camera, {@code false} otherwise
     */
    public boolean isKeyScrolling() {
        return this.keyScrolling;
    }

    /**
     * Sets whether held scroll keys scroll the camera.
     *
     * @param keyScrolling whether key scrolling is on
     */
    public void setKeyScrolling(boolean keyScrolling) {
        this.keyScrolling = keyScrolling;
    }

    /**
     * @return the direction of the edge the pointer scrolls toward ({@code 0} up, then clockwise to {@code 7} up-left), or {@link #NO_DIRECTION}
     */
    public int getEdgeDirection() {
        return DIRECTIONS[this.edgeY + 1][this.edgeX + 1];
    }

    /**
     * @return the horizontal direction of the edge the pointer scrolls toward: {@code -1} left, {@code 1} right, else {@code 0}
     */
    public int getEdgeX() {
        return this.edgeX;
    }

    /**
     * @return the vertical direction of the edge the pointer scrolls toward: {@code -1} up, {@code 1} down, else {@code 0}
     */
    public int getEdgeY() {
        return this.edgeY;
    }


    /**
     * Runs a scroll update: the held keys, else the edge test if the pointer is inside the scroll area, then the move.
     *
     * @param camera        the camera
     * @param keyX          the horizontal key direction {@code -1..1}
     * @param keyY          the vertical key direction {@code -1..1}
     * @param pointerX      the pointer column in the scroll area
     * @param pointerY      the pointer row in the scroll area
     * @param pointerInside whether the edge test runs (the pointer is inside the window and free to scroll)
     * @param width         the width of the scroll area
     * @param height        the height of the scroll area
     * @param milliseconds  the time of the update, for a continuous rate
     */
    public void update(@NotNull CameraControl camera,
                       int keyX, int keyY,
                       float pointerX, float pointerY,
                       boolean pointerInside,
                       float width, float height,
                       long milliseconds) {
        int directionX = this.keyScrolling ? keyX : 0;
        int directionY = this.keyScrolling ? keyY : 0;
        boolean keys = directionX != 0 || directionY != 0;

        if (keys) {
            this.keyboardScrolled = true;
        } else if (!this.edgeScrolling) {
            this.clearEdge();
        } else if (pointerInside) {
            this.testEdge(camera, pointerX, pointerY, width, height);

            directionX = this.edgeX;
            directionY = this.edgeY;
        }

        boolean moving = directionX != 0 || directionY != 0;

        this.step = moving ? Math.min(this.step + 1, this.rate.getStepCount() - 1) : 0;

        float zoom = camera.getZoom();
        float distance = keys ? this.rate.getKeyDistance(this.step, milliseconds, zoom)
                              : this.rate.getEdgeDistance(this.step, milliseconds, zoom);

        camera.moveTo(move(camera.getX(), directionX, distance, camera.getMinimumX(), camera.getMaximumX()),
                      move(camera.getY(), directionY, distance, camera.getMinimumY(), camera.getMaximumY()));
    }

    /**
     * Tests the pointer at the edges and records the edge direction: toward an edge within the edge width while the camera can still move
     * that way, nothing for a pointer that rests since a key scroll.
     *
     * @param camera the camera
     * @param x      the pointer column
     * @param y      the pointer row
     * @param width  the width of the scroll area
     * @param height the height of the scroll area
     */
    private void testEdge(@NotNull CameraControl camera, float x, float y, float width, float height) {
        this.clearEdge();

        if (this.keyboardScrolled) {
            if (x == this.lastPointerX && y == this.lastPointerY) {
                return;
            }

            this.keyboardScrolled = false;
        }

        this.lastPointerX = x;
        this.lastPointerY = y;

        float cameraX = camera.getX();
        float cameraY = camera.getY();

        boolean left = x < this.edgeWidth && cameraX > camera.getMinimumX();
        boolean right = x >= width - this.edgeWidth && cameraX < camera.getMaximumX();
        boolean up = y < this.edgeWidth && cameraY > camera.getMinimumY();
        boolean down = y >= height - this.edgeWidth && cameraY < camera.getMaximumY();

        this.edgeX = left ? -1 : right ? 1 : 0;
        this.edgeY = up ? -1 : down ? 1 : 0;
    }


    /**
     * Starts over for a new game: no acceleration, no key scroll pending, no edge direction.
     */
    public void reset() {
        this.step = 0;

        this.keyboardScrolled = false;
        this.lastPointerX     = Float.NaN;
        this.lastPointerY     = Float.NaN;
        this.clearEdge();
    }

    /**
     * Clears the edge direction, for example when the pointer left the window or a popup took it.
     */
    public void clearEdge() {
        this.edgeX = 0;
        this.edgeY = 0;
    }
}
