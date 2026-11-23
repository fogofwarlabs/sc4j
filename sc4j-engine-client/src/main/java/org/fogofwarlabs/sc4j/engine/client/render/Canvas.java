package org.fogofwarlabs.sc4j.engine.client.render;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.jetbrains.annotations.NotNull;

/**
 * Draws the 2D interface: textures, filled and outlined rectangles and lines, in drawing coordinates with a top-left origin.
 * Drawing happens between {@link #begin} and {@link #end}; the canvas may batch it until then. Draw colours are packed
 * {@code 0xRRGGBBAA} with straight alpha and blend over what is drawn below.
 */
public interface Canvas {
    /**
     * Begins drawing over the whole window, in window pixel coordinates.
     *
     * @param width  the window framebuffer width in pixels
     * @param height the window framebuffer height in pixels
     */
    void begin(int width, int height);

    /**
     * Begins drawing over the whole window in the logical units of an interface surface: the surface's logical area maps onto the whole
     * framebuffer, and {@link #getWidth} and {@link #getHeight} are its logical size.
     * <p>
     * The default supports only a surface whose logical units are framebuffer pixels and begins as {@link #begin(int, int)}; a canvas that
     * scales overrides it.
     *
     * @param surface the interface surface of the frame
     * @throws UnsupportedOperationException if the canvas cannot scale and the surface's units are not framebuffer pixels
     */
    default void begin(@NotNull UiSurface surface) {
        if (!surface.isPixelExact()) {
            throw Exceptions.newUnsupportedOperationException("The canvas {0} does not draw in scaled units: {1}", this.getClass().getSimpleName(),
                                                              surface);
        }

        this.begin(surface.getFramebufferWidth(), surface.getFramebufferHeight());
    }

    /**
     * @return the width of the drawing coordinates of the current drawing
     */
    int getWidth();

    /**
     * @return the height of the drawing coordinates of the current drawing
     */
    int getHeight();

    /**
     * Returns the framebuffer pixels one drawing unit covers in the current drawing, so that a drawer can pick pictures of a matching
     * resolution. The default is {@code 1}.
     *
     * @return the pixels per drawing unit across
     */
    default float getPixelScale() {
        return 1;
    }

    /**
     * Sets the rectangle the following drawing is limited to, or lifts the limit.
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width, or a negative value to draw over the whole target again
     * @param height the height
     */
    void setClip(int x, int y, int width, int height);

    /**
     * Uploads an image as a texture of this canvas.
     *
     * @param width  the image width in pixels
     * @param height the image height in pixels
     * @param colors the row-major pixels, top row first, packed {@code 0xAARRGGBB} with straight alpha as the resource model stores them
     * @return the created {@link Texture} instance, closed by the caller
     * @throws IllegalArgumentException if a dimension is not positive or the pixels do not match the dimensions
     */
    @NotNull Texture uploadTexture(int width, int height, int @NotNull [] colors);

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
    void drawTexture(@NotNull Texture texture, float x, float y, float width, float height, int color);

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
    void drawTexture(@NotNull Texture texture, float x, float y, float width, float height,
                     float left, float top, float right, float bottom,
                     int color);

    /**
     * Fills a rectangle.
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width
     * @param height the height
     * @param color  the packed colour
     */
    void fillRectangle(float x, float y, float width, float height, int color);

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
    void drawRectangle(float x, float y, float width, float height, float thickness, int color);

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
    void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color);

    /**
     * Fills a convex quadrilateral, for example the screen outline of a diamond cell or of a footprint.
     * <p>
     * An axis-aligned rectangle fills as {@link #fillRectangle}. The default implementation fills other outlines row by row with one-pixel high
     * rectangles; a canvas that draws triangles overrides it.
     *
     * @param corners the x and y of the four corners in order around the outline, {@value QuadrilateralUtils#CORNER_VALUES} values
     * @param color   the packed colour
     * @throws IndexOutOfBoundsException if the array is shorter than {@value QuadrilateralUtils#CORNER_VALUES}
     */
    default void fillQuadrilateral(float @NotNull [] corners, int color) {
        if (QuadrilateralUtils.isRectangle(corners)) {
            this.fillRectangle(QuadrilateralUtils.getLeft(corners), QuadrilateralUtils.getTop(corners),
                               QuadrilateralUtils.getWidth(corners), QuadrilateralUtils.getHeight(corners), color);
        } else {
            QuadrilateralUtils.fillRows(this, corners, color);
        }
    }

    /**
     * Draws the outline of a quadrilateral: an axis-aligned rectangle inside the rectangle as {@link #drawRectangle}, other outlines as four
     * lines of the given thickness along the edges ({@link #drawLine}).
     *
     * @param corners   the x and y of the four corners in order around the outline, {@value QuadrilateralUtils#CORNER_VALUES} values
     * @param thickness the line thickness
     * @param color     the packed colour
     * @throws IndexOutOfBoundsException if the array is shorter than {@value QuadrilateralUtils#CORNER_VALUES}
     */
    default void drawQuadrilateral(float @NotNull [] corners, float thickness, int color) {
        if (QuadrilateralUtils.isRectangle(corners)) {
            this.drawRectangle(QuadrilateralUtils.getLeft(corners), QuadrilateralUtils.getTop(corners),
                               QuadrilateralUtils.getWidth(corners), QuadrilateralUtils.getHeight(corners), thickness, color);

            return;
        }

        for (int index = 0; index < QuadrilateralUtils.CORNER_VALUES; index += 2) {
            int next = (index + 2) % QuadrilateralUtils.CORNER_VALUES;

            this.drawLine(corners[index], corners[index + 1], corners[next], corners[next + 1], thickness, color);
        }
    }

    /**
     * Submits the pending drawing.
     */
    void end();
}
