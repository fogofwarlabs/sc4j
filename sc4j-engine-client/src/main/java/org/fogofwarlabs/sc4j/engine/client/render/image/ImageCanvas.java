package org.fogofwarlabs.sc4j.engine.client.render.image;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.QuadrilateralUtils;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws into a picture in memory: the engine's software {@link Canvas} on Java2D, for offscreen frames of a client (screenshots, checks of
 * screens) without a window. Drawing has no antialiasing and textures are sampled nearest-neighbour, so a frame is the same on every machine.
 * The picture is the framebuffer: drawing begun on an interface surface ({@link #begin(UiSurface)}) is in its logical units, scaled onto
 * the picture. A texture drawn with a colour other than opaque white is multiplied with it channel by channel, alpha included; each colour's
 * tinted copy of a texture is made once and kept with the texture.
 */
public final class ImageCanvas implements Canvas {
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;
    private static final int OPAQUE_BLACK = 0x000000FF;
    private static final int RGBA_RED_SHIFT = 24;
    private static final int RGBA_GREEN_SHIFT = 16;
    private static final int RGBA_BLUE_SHIFT = 8;
    private static final int ARGB_RED_SHIFT = 16;
    private static final int ARGB_GREEN_SHIFT = 8;
    private static final int ARGB_ALPHA_SHIFT = 24;
    private static final int CHANNEL_MASK = 0xFF;
    private static final int HALF_DIVISOR = 2;
    private static final int COORDINATES_PER_CORNER = 2;

    /**
     * Holds an uploaded picture and its tinted copies.
     */
    private static final class ImageTexture implements Texture {
        private final @NotNull BufferedImage image;
        private final @NotNull Map<@NotNull Integer, @NotNull BufferedImage> tinted;

        private ImageTexture(@NotNull BufferedImage image) {
            this.image  = image;
            this.tinted = new HashMap<>();
        }

        /**
         * @return the width in pixels
         */
        @Override
        public int getWidth() {
            return this.image.getWidth();
        }

        /**
         * @return the height in pixels
         */
        @Override
        public int getHeight() {
            return this.image.getHeight();
        }

        private @NotNull BufferedImage getImage(int color) {
            if (color == OPAQUE_WHITE) {
                return this.image;
            }

            return this.tinted.computeIfAbsent(color, this::createTinted);
        }

        private @NotNull BufferedImage createTinted(int color) {
            int width = this.image.getWidth();
            int height = this.image.getHeight();
            int[] pixels = this.image.getRGB(0, 0, width, height, null, 0, width);
            int red = color >>> RGBA_RED_SHIFT;
            int green = color >>> RGBA_GREEN_SHIFT & CHANNEL_MASK;
            int blue = color >>> RGBA_BLUE_SHIFT & CHANNEL_MASK;
            int alpha = color & CHANNEL_MASK;
            BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            for (int index = 0; index < pixels.length; index++) {
                int pixel = pixels[index];

                pixels[index] = multiply(pixel >>> ARGB_ALPHA_SHIFT, alpha) << ARGB_ALPHA_SHIFT |
                                multiply(pixel >>> ARGB_RED_SHIFT & CHANNEL_MASK, red) << ARGB_RED_SHIFT |
                                multiply(pixel >>> ARGB_GREEN_SHIFT & CHANNEL_MASK, green) << ARGB_GREEN_SHIFT |
                                multiply(pixel & CHANNEL_MASK, blue);
            }

            copy.setRGB(0, 0, width, height, pixels, 0, width);

            return copy;
        }

        /**
         * Disposes resources: drops the tinted copies; a Java2D picture needs no release.
         */
        @Override
        public void close() {
            this.tinted.clear();
        }
    }

    private static int multiply(int value, int factor) {
        return (value * factor + CHANNEL_MASK / HALF_DIVISOR) / CHANNEL_MASK;
    }

    private static @NotNull Color toColor(int color) {
        return new Color(color >>> RGBA_RED_SHIFT, color >>> RGBA_GREEN_SHIFT & CHANNEL_MASK, color >>> RGBA_BLUE_SHIFT & CHANNEL_MASK,
                         color & CHANNEL_MASK);
    }


    private final @NotNull BufferedImage image;

    private @Nullable Graphics2D graphics;
    private int width;
    private int height;
    private float scaleX;
    private float scaleY;

    /**
     * Creates an {@link ImageCanvas} instance with a picture of the given size, opaque black.
     *
     * @param width  the width in pixels
     * @param height the height in pixels
     * @throws IllegalArgumentException if a dimension is not positive
     */
    public ImageCanvas(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid image canvas size {0}x{1}", width, height);
        }

        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        this.graphics = null;
        this.width    = width;
        this.height   = height;
        this.scaleX   = 1;
        this.scaleY   = 1;

        this.begin(width, height);
        this.fillRectangle(0, 0, width, height, OPAQUE_BLACK);
        this.end();
    }

    /**
     * @return the picture drawn so far, packed {@code 0xAARRGGBB}
     */
    public @NotNull BufferedImage getImage() {
        return this.image;
    }

    /**
     * Begins drawing over the whole window, in window pixel coordinates.
     * <p>
     * The picture keeps its size; drawing outside it is cut off.
     *
     * @param width  the window framebuffer width in pixels
     * @param height the window framebuffer height in pixels
     */
    @Override
    public void begin(int width, int height) {
        this.begin(this.image.getWidth(), this.image.getHeight(), 1, 1);
    }

    /**
     * Begins drawing over the whole window in the logical units of an interface surface: the surface's logical area maps onto the whole
     * framebuffer, and {@link #getWidth} and {@link #getHeight} are its logical size.
     * <p>
     * The picture is the framebuffer; the surface's logical area maps onto the whole picture.
     *
     * @param surface the interface surface of the frame
     */
    @Override
    public void begin(@NotNull UiSurface surface) {
        this.begin(surface.getWidth(), surface.getHeight(),
                   this.image.getWidth() / (float) surface.getWidth(), this.image.getHeight() / (float) surface.getHeight());
    }

    private void begin(int width, int height, float scaleX, float scaleY) {
        Graphics2D started = this.image.createGraphics();

        this.width  = width;
        this.height = height;
        this.scaleX = scaleX;
        this.scaleY = scaleY;

        started.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        started.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        started.setComposite(AlphaComposite.SrcOver);
        this.graphics = started;
    }

    /**
     * @return the width of the drawing coordinates of the current drawing
     */
    @Override
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the height of the drawing coordinates of the current drawing
     */
    @Override
    public int getHeight() {
        return this.height;
    }

    /**
     * Returns the framebuffer pixels one drawing unit covers in the current drawing, so that a drawer can pick pictures of a matching
     * resolution.
     *
     * @return the pixels per drawing unit across
     */
    @Override
    public float getPixelScale() {
        return this.scaleX;
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
        Rectangle2D.Float clip = new Rectangle2D.Float(x * this.scaleX, y * this.scaleY, width * this.scaleX, height * this.scaleY);

        this.requireGraphics().setClip(width < 0 ? null : clip);
    }

    /**
     * Uploads an image as a texture of this canvas.
     *
     * @param width  the image width in pixels
     * @param height the image height in pixels
     * @param colors the row-major pixels, top row first, packed {@code 0xAARRGGBB} with straight alpha as the resource model stores them
     * @return the created {@link Texture} instance, closed by the caller
     * @throws IllegalArgumentException if a dimension is not positive or the pixels do not match the dimensions
     */
    @Override
    public @NotNull Texture uploadTexture(int width, int height, int @NotNull [] colors) {
        if (width <= 0 || height <= 0 || colors.length != width * height) {
            throw Exceptions.newIllegalArgumentException("Invalid texture {0}x{1} of {2} pixels", width, height, colors.length);
        }

        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        picture.setRGB(0, 0, width, height, colors, 0, width);

        return new ImageTexture(picture);
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
        this.drawTexture(texture, x, y, width, height, 0, 0, 1, 1, color);
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
        BufferedImage picture = ((ImageTexture) texture).getImage(color);

        this.requireGraphics().drawImage(picture, Math.round(x * this.scaleX), Math.round(y * this.scaleY),
                                         Math.round((x + width) * this.scaleX), Math.round((y + height) * this.scaleY),
                                         Math.round(left * picture.getWidth()), Math.round(top * picture.getHeight()),
                                         Math.round(right * picture.getWidth()), Math.round(bottom * picture.getHeight()), null);
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
        Graphics2D current = this.requireGraphics();

        current.setColor(toColor(color));
        current.fill(new Rectangle2D.Float(x * this.scaleX, y * this.scaleY, width * this.scaleX, height * this.scaleY));
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
        this.fillRectangle(x, y, width, thickness, color);
        this.fillRectangle(x, y + height - thickness, width, thickness, color);
        this.fillRectangle(x, y, thickness, height, color);
        this.fillRectangle(x + width - thickness, y, thickness, height, color);
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
        Graphics2D current = this.requireGraphics();

        current.setColor(toColor(color));
        current.setStroke(new BasicStroke(thickness * this.scaleX));
        current.draw(new Line2D.Float(sourceX * this.scaleX, sourceY * this.scaleY, destinationX * this.scaleX, destinationY * this.scaleY));
    }

    /**
     * Fills a convex quadrilateral, for example the screen outline of a diamond cell or of a footprint.
     * <p>
     * The outline fills as one Java2D polygon.
     *
     * @param corners the x and y of the four corners in order around the outline, {@value QuadrilateralUtils#CORNER_VALUES} values
     * @param color   the packed colour
     * @throws IndexOutOfBoundsException if the array is shorter than {@value QuadrilateralUtils#CORNER_VALUES}
     */
    @Override
    public void fillQuadrilateral(float @NotNull [] corners, int color) {
        Graphics2D current = this.requireGraphics();
        Path2D.Float outline = new Path2D.Float();

        outline.moveTo(corners[0] * this.scaleX, corners[1] * this.scaleY);

        for (int index = COORDINATES_PER_CORNER; index < QuadrilateralUtils.CORNER_VALUES; index += COORDINATES_PER_CORNER) {
            outline.lineTo(corners[index] * this.scaleX, corners[index + 1] * this.scaleY);
        }

        outline.closePath();
        current.setColor(toColor(color));
        current.fill(outline);
    }

    /**
     * Submits the pending drawing.
     */
    @Override
    public void end() {
        Graphics2D current = this.requireGraphics();

        this.graphics = null;
        current.dispose();
    }

    private @NotNull Graphics2D requireGraphics() {
        Graphics2D current = this.graphics;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The image canvas has not begun.");
        }

        return current;
    }
}
