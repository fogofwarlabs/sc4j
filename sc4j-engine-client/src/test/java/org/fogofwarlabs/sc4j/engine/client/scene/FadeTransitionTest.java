package org.fogofwarlabs.sc4j.engine.client.scene;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the engine default fade transition: completion after its duration and the growing alpha of its overlay.
 */
final class FadeTransitionTest {
    private static final long DURATION = 200;
    private static final long START = 1000;
    private static final int BLACK = 0x000000;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    /**
     * Records the filled rectangles of a canvas of a fixed size.
     */
    private static final class RecordingCanvas implements Canvas {
        private final @NotNull List<String> fills;

        private RecordingCanvas() {
            this.fills = new ArrayList<>();
        }

        /**
         * Begins drawing over the whole window, in window pixel coordinates.
         *
         * @param width  the window framebuffer width in pixels
         * @param height the window framebuffer height in pixels
         */
        @Override
        public void begin(int width, int height) {
        }

        /**
         * @return the width of the drawing coordinates of the current drawing
         */
        @Override
        public int getWidth() {
            return WIDTH;
        }

        /**
         * @return the height of the drawing coordinates of the current drawing
         */
        @Override
        public int getHeight() {
            return HEIGHT;
        }

        /**
         * Sets the rectangle the following drawing is limited to, or lifts the limit.
         *
         * @param x      the left coordinate
         * @param y      the top coordinate
         * @param width  the width, or a negative value to draw over the whole target again
         * @param height the height
         */
        @Override
        public void setClip(int x, int y, int width, int height) {
        }

        /**
         * Uploads an image as a texture of this canvas.
         *
         * @param width  the image width in pixels
         * @param height the image height in pixels
         * @param colors the row-major pixels, top row first, packed {@code 0xAARRGGBB} with straight alpha as the resource model stores them
         * @return never
         * @throws UnsupportedOperationException always
         */
        @Override
        public @NotNull Texture uploadTexture(int width, int height, int @NotNull [] colors) {
            throw Exceptions.newUnsupportedOperationException("The recording canvas holds no textures.");
        }

        /**
         * Draws a whole texture into a rectangle.
         *
         * @param texture the texture of this canvas
         * @param x       the left coordinate
         * @param y       the top coordinate
         * @param width   the drawn width
         * @param height  the drawn height
         * @param color   the packed colour multiplied with the texture
         */
        @Override
        public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height, int color) {
        }

        /**
         * Draws part of a texture into a rectangle.
         *
         * @param texture the texture of this canvas
         * @param x       the left coordinate
         * @param y       the top coordinate
         * @param width   the drawn width
         * @param height  the drawn height
         * @param left    the left texture coordinate, {@code 0} to {@code 1}
         * @param top     the top texture coordinate
         * @param right   the right texture coordinate
         * @param bottom  the bottom texture coordinate
         * @param color   the packed colour multiplied with the texture
         */
        @Override
        public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height,
                                float left, float top, float right, float bottom,
                                int color) {
        }

        /**
         * Fills a rectangle.
         *
         * @param x      the left coordinate
         * @param y      the top coordinate
         * @param width  the width
         * @param height the height
         * @param color  the packed colour
         */
        @Override
        public void fillRectangle(float x, float y, float width, float height, int color) {
            this.fills.add((int) width + "x" + (int) height + " " + Integer.toHexString(color));
        }

        /**
         * Draws a rectangle outline inside the rectangle.
         *
         * @param x         the left coordinate
         * @param y         the top coordinate
         * @param width     the width
         * @param height    the height
         * @param thickness the line thickness
         * @param color     the packed colour
         */
        @Override
        public void drawRectangle(float x, float y, float width, float height, float thickness, int color) {
        }

        /**
         * Draws a straight line of the given thickness.
         *
         * @param sourceX      the start x
         * @param sourceY      the start y
         * @param destinationX the end x
         * @param destinationY the end y
         * @param thickness    the line thickness
         * @param color        the packed colour
         */
        @Override
        public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
        }

        /**
         * Submits the pending drawing.
         */
        @Override
        public void end() {
        }
    }

    @Test
    void update_should_complete_after_the_duration_and_draw_a_growing_overlay() {
        FadeTransition transition = new FadeTransition(DURATION, BLACK);
        RecordingCanvas canvas = new RecordingCanvas();

        transition.start(START);
        transition.draw(canvas);

        Assertions.assertFalse(transition.update(START + DURATION / 2));

        transition.draw(canvas);

        Assertions.assertTrue(transition.update(START + DURATION));

        transition.draw(canvas);

        Assertions.assertEquals(List.of("320x240 0", "320x240 7f", "320x240 ff"), canvas.fills);
    }

    @Test
    void constructor_should_reject_a_duration_that_is_not_positive() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FadeTransition(0, BLACK));
    }
}
