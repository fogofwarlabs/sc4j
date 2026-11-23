package org.fogofwarlabs.sc4j.engine.client.skin;

import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.PictureRenderer;
import org.fogofwarlabs.sc4j.engine.client.render.SliceFill;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a box as a picture sliced by its borders (nine-slice, {@link PictureRenderer#drawSliced}): the corners keep their size and the edges
 * and the centre stretch to the rectangle, or repeat at the picture's own size ({@link SliceFill#TILE}). A box may lay a fill picture under
 * the sliced one, repeated at its own size over the whole rectangle in a grid of tiles from the canvas origin
 * ({@link PictureRenderer#drawTiled}), for example a seamless texture under a frame with an open centre: boxes side by side with the same fill
 * show one continuous texture, and each box shows its own part of it; with {@link SliceFill#TILE} neither picture is scaled at any rectangle
 * size.
 */
public final class PictureBoxStyle implements BoxStyle {
    private final @NotNull PictureRenderer renderer;
    private final @Nullable Picture fill;
    private final @NotNull Picture picture;
    private final @NotNull SliceFill sliceFill;
    private final int color;

    /**
     * Creates a {@link PictureBoxStyle} instance that draws the picture as it is, its edges and centre stretched.
     *
     * @param renderer the renderer that draws the picture, on the canvas the box is drawn on
     * @param picture  the picture with its borders
     */
    public PictureBoxStyle(@NotNull PictureRenderer renderer, @NotNull Picture picture) {
        this(renderer, picture, PictureRenderer.OPAQUE_WHITE);
    }

    /**
     * Creates a {@link PictureBoxStyle} instance whose edges and centre stretch.
     *
     * @param renderer the renderer that draws the picture, on the canvas the box is drawn on
     * @param picture  the picture with its borders
     * @param color    the packed {@code 0xRRGGBBAA} colour multiplied with the picture, {@link PictureRenderer#OPAQUE_WHITE} for none
     */
    public PictureBoxStyle(@NotNull PictureRenderer renderer, @NotNull Picture picture, int color) {
        this(renderer, null, picture, SliceFill.STRETCH, color);
    }

    /**
     * Creates a {@link PictureBoxStyle} instance.
     *
     * @param renderer  the renderer that draws the pictures, on the canvas the box is drawn on
     * @param fill      the seamless picture repeated from the canvas origin over the whole rectangle under the sliced picture, or {@code null} for none
     * @param picture   the picture with its borders
     * @param sliceFill how the edges and the centre of the sliced picture fill their parts
     * @param color     the packed {@code 0xRRGGBBAA} colour multiplied with the pictures, {@link PictureRenderer#OPAQUE_WHITE} for none
     */
    public PictureBoxStyle(@NotNull PictureRenderer renderer, @Nullable Picture fill, @NotNull Picture picture, @NotNull SliceFill sliceFill,
                           int color) {
        this.renderer  = renderer;
        this.fill      = fill;
        this.picture   = picture;
        this.sliceFill = sliceFill;
        this.color     = color;
    }

    /**
     * @return the picture repeated under the sliced picture, or {@code null} for none
     */
    public @Nullable Picture getFill() {
        return this.fill;
    }

    /**
     * @return the picture with its borders
     */
    public @NotNull Picture getPicture() {
        return this.picture;
    }

    /**
     * @return how the edges and the centre of the sliced picture fill their parts
     */
    public @NotNull SliceFill getSliceFill() {
        return this.sliceFill;
    }


    /**
     * Draws the box: the fill repeated over the rectangle from the canvas origin, if any, then the sliced picture over it.
     *
     * @param canvas the canvas, between its begin and end
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width
     * @param height the height
     * @throws IllegalArgumentException if the canvas is not the canvas of the renderer
     */
    @Override
    public void draw(@NotNull Canvas canvas, float x, float y, float width, float height) {
        Picture fillPicture = this.fill;

        if (fillPicture != null) {
            this.renderer.drawTiled(canvas, fillPicture, x, y, width, height, 0, 0, this.color);
        }

        this.renderer.drawSliced(canvas, this.picture, x, y, width, height, this.sliceFill, this.color);
    }
}
