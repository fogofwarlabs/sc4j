package org.fogofwarlabs.sc4j.engine.resource.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds one still picture of the interface: a screen background, an emblem, a portrait, an icon, a piece of a HUD or dialog skin, or a cursor.
 * <p>
 * The colour layer is {@link PixelFormat#RGBA_8888}, row-major from the top row. An optional team colour mask
 * ({@link PixelFormat#ALPHA_8}) says how strongly each pixel takes a player's colour, by the engine mix rule
 * {@code rgb = mix(rgb, team, mask / 255)} that sprite sheets use. The anchor is the pixel placed on a position, for example the hot spot of a
 * cursor. The borders are the widths of the edges that a sliced drawing keeps unscaled (nine-slice): the corners keep their size, the edges
 * stretch along one axis and the centre along both.
 */
public final class Picture {
    private final int width;
    private final int height;

    private final @NotNull PixelLayer color;
    private final @Nullable PixelLayer teamMask;

    private final int anchorX;
    private final int anchorY;

    private final int borderLeft;
    private final int borderTop;
    private final int borderRight;
    private final int borderBottom;

    /**
     * Creates a {@link Picture} instance without team colour mask, anchor at the top-left corner and without borders.
     *
     * @param width  the width in pixels, at least {@code 1}
     * @param height the height in pixels, at least {@code 1}
     * @param color  the colour layer, RGBA
     * @throws IllegalArgumentException if the picture is empty or the layer has the wrong format or length
     */
    public Picture(int width, int height, @NotNull PixelLayer color) {
        this(width, height, color, null, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Creates a {@link Picture} instance. A border is invalid when it is negative or the borders of its axis are wider than the picture.
     *
     * @param width        the width in pixels, at least {@code 1}
     * @param height       the height in pixels, at least {@code 1}
     * @param color        the colour layer, RGBA
     * @param teamMask     the team colour mask, or {@code null}
     * @param anchorX      the horizontal position of the anchor
     * @param anchorY      the vertical position of the anchor
     * @param borderLeft   the width of the left edge a sliced drawing keeps
     * @param borderTop    the height of the top edge a sliced drawing keeps
     * @param borderRight  the width of the right edge a sliced drawing keeps
     * @param borderBottom the height of the bottom edge a sliced drawing keeps
     * @throws IllegalArgumentException if the picture is empty, a layer has the wrong format or length, or a border is invalid
     */
    public Picture(int width, int height,
                   @NotNull PixelLayer color, @Nullable PixelLayer teamMask,
                   int anchorX, int anchorY,
                   int borderLeft, int borderTop, int borderRight, int borderBottom) {
        if (width < 1 || height < 1) {
            throw Exceptions.newIllegalArgumentException("The picture must not be empty: {0}x{1}", width, height);
        }

        if (color.getFormat() != PixelFormat.RGBA_8888 || color.getLength() != width * height) {
            throw Exceptions.newIllegalArgumentException("The colour layer must be RGBA with {0} pixels.", width * height);
        }

        if (teamMask != null && (teamMask.getFormat() != PixelFormat.ALPHA_8 || teamMask.getLength() != width * height)) {
            throw Exceptions.newIllegalArgumentException("The team mask must be an alpha layer with {0} pixels.", width * height);
        }

        if (borderLeft < 0 || borderTop < 0 || borderRight < 0 || borderBottom < 0 || borderLeft + borderRight > width ||
            borderTop + borderBottom > height) {
            throw Exceptions.newIllegalArgumentException("The borders {0}, {1}, {2}, {3} do not fit the {4}x{5} picture.",
                                                         borderLeft, borderTop, borderRight, borderBottom, width, height);
        }

        this.width  = width;
        this.height = height;

        this.color    = color;
        this.teamMask = teamMask;

        this.anchorX = anchorX;
        this.anchorY = anchorY;

        this.borderLeft   = borderLeft;
        this.borderTop    = borderTop;
        this.borderRight  = borderRight;
        this.borderBottom = borderBottom;
    }

    /**
     * @return the width in pixels
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the height in pixels
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the colour layer, RGBA
     */
    public @NotNull PixelLayer getColor() {
        return this.color;
    }

    /**
     * @return the team colour mask, or {@code null} if the picture has none
     */
    public @Nullable PixelLayer getTeamMask() {
        return this.teamMask;
    }

    /**
     * @return the horizontal position of the anchor
     */
    public int getAnchorX() {
        return this.anchorX;
    }

    /**
     * @return the vertical position of the anchor
     */
    public int getAnchorY() {
        return this.anchorY;
    }

    /**
     * @return the width of the left edge a sliced drawing keeps
     */
    public int getBorderLeft() {
        return this.borderLeft;
    }

    /**
     * @return the height of the top edge a sliced drawing keeps
     */
    public int getBorderTop() {
        return this.borderTop;
    }

    /**
     * @return the width of the right edge a sliced drawing keeps
     */
    public int getBorderRight() {
        return this.borderRight;
    }

    /**
     * @return the height of the bottom edge a sliced drawing keeps
     */
    public int getBorderBottom() {
        return this.borderBottom;
    }
}
