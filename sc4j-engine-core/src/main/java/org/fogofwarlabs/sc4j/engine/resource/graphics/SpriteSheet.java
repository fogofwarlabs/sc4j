package org.fogofwarlabs.sc4j.engine.resource.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Holds the frames of one animated picture: a frame box of a fixed size with an anchor, and per frame the rectangle of
 * stored pixels ({@link SpriteFrame}).
 * <p>
 * The anchor is the pixel of the frame box that is placed on the object's position. The pixels are in up to three layers
 * with the same frame layout:
 * <ul>
 *     <li>the colour layer, {@link PixelFormat#INDEXED_8} (drawn with a palette the content chooses) or
 *     {@link PixelFormat#RGBA_8888};</li>
 *     <li>an optional team colour mask, {@link PixelFormat#ALPHA_8}: how strongly each pixel takes the owner's colour;</li>
 *     <li>an optional shadow, {@link PixelFormat#ALPHA_8}: the opacity of a black shadow drawn before the sprite.</li>
 * </ul>
 * How a frame number of an animation maps to a frame of the sheet (directions, mirroring) is decided by the content that
 * draws it, not by the sheet.
 */
public final class SpriteSheet {
    private static void checkMaskLayer(@Nullable PixelLayer layer, @NotNull PixelLayer color, @NotNull String name) {
        if (layer == null) {
            return;
        }

        if (layer.getFormat() != PixelFormat.ALPHA_8) {
            throw Exceptions.newIllegalArgumentException("The {0} layer must be an alpha layer.", name);
        }

        if (layer.getLength() != color.getLength()) {
            throw Exceptions.newIllegalArgumentException("The {0} layer has {1} pixels, the colour layer {2}.",
                                                         name, layer.getLength(), color.getLength());
        }
    }


    private final int width;
    private final int height;
    private final int anchorX;
    private final int anchorY;

    private final @NotNull List<@NotNull SpriteFrame> frames;

    private final @NotNull PixelLayer color;
    private final @Nullable PixelLayer teamMask;
    private final @Nullable PixelLayer shadow;

    /**
     * Creates a {@link SpriteSheet} instance.
     *
     * @param width    the width of the frame box in pixels, at least {@code 1}
     * @param height   the height of the frame box in pixels, at least {@code 1}
     * @param anchorX  the horizontal position of the anchor in the frame box
     * @param anchorY  the vertical position of the anchor in the frame box
     * @param frames   the frames, copied
     * @param color    the colour layer, indexed or RGBA
     * @param teamMask the team colour mask, or {@code null}
     * @param shadow   the shadow layer, or {@code null}
     * @throws IllegalArgumentException if the box is empty, a frame leaves the box or its layers, or a layer has the wrong format or another length than the colour layer
     */
    public SpriteSheet(int width, int height, int anchorX, int anchorY,
                       @NotNull List<@NotNull SpriteFrame> frames,
                       @NotNull PixelLayer color, @Nullable PixelLayer teamMask, @Nullable PixelLayer shadow) {
        if (width < 1 || height < 1) {
            throw Exceptions.newIllegalArgumentException("The frame box must not be empty: {0}x{1}", width, height);
        }

        if (color.getFormat() == PixelFormat.ALPHA_8) {
            throw Exceptions.newIllegalArgumentException("The colour layer must be indexed or RGBA.");
        }

        checkMaskLayer(teamMask, color, "team mask");
        checkMaskLayer(shadow, color, "shadow");

        for (int index = 0; index < frames.size(); index++) {
            SpriteFrame frame = frames.get(index);

            if (frame.getX() + frame.getWidth() > width || frame.getY() + frame.getHeight() > height) {
                throw Exceptions.newIllegalArgumentException("The frame {0} leaves the {1}x{2} frame box.", index, width, height);
            }

            if ((long) frame.getPixelOffset() + (long) frame.getWidth() * frame.getHeight() > color.getLength()) {
                throw Exceptions.newIllegalArgumentException("The pixels of frame {0} leave the layers.", index);
            }
        }

        this.width   = width;
        this.height  = height;
        this.anchorX = anchorX;
        this.anchorY = anchorY;

        this.frames = List.copyOf(frames);

        this.color    = color;
        this.teamMask = teamMask;
        this.shadow   = shadow;
    }

    /**
     * @return the width of the frame box in pixels
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the height of the frame box in pixels
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the horizontal position of the anchor in the frame box
     */
    public int getAnchorX() {
        return this.anchorX;
    }

    /**
     * @return the vertical position of the anchor in the frame box
     */
    public int getAnchorY() {
        return this.anchorY;
    }

    /**
     * @return the number of frames
     */
    public int getFrameCount() {
        return this.frames.size();
    }

    /**
     * Returns one frame.
     *
     * @param frameIndex the frame index, {@code 0} up to {@link #getFrameCount()} minus one
     * @return the frame
     * @throws IndexOutOfBoundsException if the index names no frame
     */
    public @NotNull SpriteFrame getFrame(int frameIndex) {
        return this.frames.get(frameIndex);
    }

    /**
     * @return the colour layer, indexed or RGBA
     */
    public @NotNull PixelLayer getColor() {
        return this.color;
    }

    /**
     * @return the team colour mask, or {@code null} if the sheet has none
     */
    public @Nullable PixelLayer getTeamMask() {
        return this.teamMask;
    }

    /**
     * @return the shadow layer, or {@code null} if the sheet has none
     */
    public @Nullable PixelLayer getShadow() {
        return this.shadow;
    }
}
