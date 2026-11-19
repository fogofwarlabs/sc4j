package org.fogofwarlabs.sc4j.engine.pack;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.json.JsonObjectReader;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a sprite sheet definition of a pack and its PNG layers into a {@link SpriteSheet}: a grid of {@code columns} by
 * {@code rows} cells of {@code frameWidth} by {@code frameHeight} pixels, frame {@code row * columns + column}, every
 * frame the whole cell, with one anchor for every frame.
 * <p>
 * The colour picture becomes an RGBA layer. The team mask is the first channel of its picture (grey for a greyscale
 * PNG, red otherwise) and the shadow is the alpha channel of its picture. The layers store the frames one after the
 * other, each row-major.
 */
final class PackSpriteSheetUtils {
    /**
     * The field of the colour picture.
     */
    static final @NotNull String IMAGE_FIELD = "image";
    /**
     * The field of the team mask picture.
     */
    static final @NotNull String TEAM_MASK_FIELD = "teamMask";
    /**
     * The field of the shadow picture.
     */
    static final @NotNull String SHADOW_FIELD = "shadow";
    /**
     * The field of the frame width.
     */
    static final @NotNull String FRAME_WIDTH_FIELD = "frameWidth";
    /**
     * The field of the frame height.
     */
    static final @NotNull String FRAME_HEIGHT_FIELD = "frameHeight";
    /**
     * The field of the number of grid columns.
     */
    static final @NotNull String COLUMNS_FIELD = "columns";
    /**
     * The field of the number of grid rows.
     */
    static final @NotNull String ROWS_FIELD = "rows";
    /**
     * The field of the anchor object.
     */
    static final @NotNull String ANCHOR_FIELD = "anchor";
    /**
     * The field of the horizontal anchor position.
     */
    static final @NotNull String ANCHOR_X_FIELD = "x";
    /**
     * The field of the vertical anchor position.
     */
    static final @NotNull String ANCHOR_Y_FIELD = "y";

    /**
     * The shift of the alpha channel of a packed colour.
     */
    private static final int ALPHA_SHIFT = 24;
    /**
     * The shift of the red channel of a packed colour.
     */
    private static final int RED_SHIFT = 16;
    /**
     * The mask of one channel of a packed colour.
     */
    private static final int CHANNEL_MASK = 0xFF;

    /**
     * Reads a sprite sheet.
     *
     * @param source         the pack files
     * @param definitionPath the pack path of the sprite sheet definition
     * @return the sprite sheet
     * @throws IOException if a file cannot be read, a picture is not a PNG of the grid's size or a field is invalid
     */
    static @NotNull SpriteSheet read(@NotNull PackSource source, @NotNull String definitionPath) throws IOException {
        JsonObjectReader definition = JsonObjectReader.parse(source.read(definitionPath), definitionPath);
        int frameWidth = definition.getInt(FRAME_WIDTH_FIELD);
        int frameHeight = definition.getInt(FRAME_HEIGHT_FIELD);
        int columns = definition.getInt(COLUMNS_FIELD);
        int rows = definition.getInt(ROWS_FIELD);
        JsonObjectReader anchor = definition.getObject(ANCHOR_FIELD);

        if (frameWidth < 1 || frameHeight < 1 || columns < 1 || rows < 1) {
            throw Exceptions.newIOException("{0}: the frame grid must not be empty: {1}x{2} frames of {3}x{4}", definitionPath, columns, rows,
                                            frameWidth, frameHeight);
        }

        BufferedImage color = readPicture(source, definitionPath, definition.getText(IMAGE_FIELD), columns * frameWidth, rows * frameHeight);
        BufferedImage teamMask = readOptionalPicture(source, definitionPath, definition.findText(TEAM_MASK_FIELD), color);
        BufferedImage shadow = readOptionalPicture(source, definitionPath, definition.findText(SHADOW_FIELD), color);

        int sheetWidth = color.getWidth();
        int[] colorPixels = readArgbPixels(color);
        int[] shadowPixels = shadow != null ? readArgbPixels(shadow) : null;
        int[] teamMaskPixels = teamMask != null && teamMask.getRaster().getNumBands() != 1 ? readArgbPixels(teamMask) : null;
        int frameCount = columns * rows;
        int framePixels = frameWidth * frameHeight;
        List<SpriteFrame> frames = new ArrayList<>(frameCount);
        int[] colors = new int[frameCount * framePixels];
        byte[] teamMaskValues = teamMask != null ? new byte[colors.length] : null;
        byte[] shadowValues = shadow != null ? new byte[colors.length] : null;
        int[] row = new int[frameWidth];

        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            int cellX = frameIndex % columns * frameWidth;
            int cellY = frameIndex / columns * frameHeight;
            int pixelOffset = frameIndex * framePixels;

            frames.add(new SpriteFrame(0, 0, frameWidth, frameHeight, pixelOffset));

            for (int y = 0; y < frameHeight; y++) {
                int rowOffset = pixelOffset + y * frameWidth;
                int sheetOffset = (cellY + y) * sheetWidth + cellX;

                System.arraycopy(colorPixels, sheetOffset, colors, rowOffset, frameWidth);

                if (teamMaskPixels != null) {
                    for (int x = 0; x < frameWidth; x++) {
                        teamMaskValues[rowOffset + x] = (byte) (teamMaskPixels[sheetOffset + x] >>> RED_SHIFT & CHANNEL_MASK);
                    }
                } else if (teamMask != null) {
                    readMaskRow(teamMask, cellX, cellY + y, row, teamMaskValues, rowOffset);
                }

                if (shadowPixels != null) {
                    for (int x = 0; x < frameWidth; x++) {
                        shadowValues[rowOffset + x] = (byte) (shadowPixels[sheetOffset + x] >>> ALPHA_SHIFT);
                    }
                }
            }
        }

        return new SpriteSheet(frameWidth, frameHeight, anchor.getInt(ANCHOR_X_FIELD), anchor.getInt(ANCHOR_Y_FIELD), frames,
                               PixelLayer.ofRgba(colors), teamMaskValues != null ? PixelLayer.ofAlpha(teamMaskValues) : null,
                               shadowValues != null ? PixelLayer.ofAlpha(shadowValues) : null);
    }

    /**
     * Reads one row of a team mask picture: the first channel of each pixel, grey for a greyscale PNG and red otherwise.
     *
     * @param mask   the mask picture
     * @param x      the left column of the row
     * @param y      the row
     * @param row    a buffer of the row's length
     * @param values receives the mask values
     * @param offset the offset of the first value in the values
     */
    static void readMaskRow(@NotNull BufferedImage mask, int x, int y, int @NotNull [] row, byte @NotNull [] values, int offset) {
        Raster raster = mask.getRaster();

        if (raster.getNumBands() == 1) {
            raster.getSamples(x, y, row.length, 1, 0, row);

            for (int column = 0; column < row.length; column++) {
                values[offset + column] = (byte) row[column];
            }

            return;
        }

        mask.getRGB(x, y, row.length, 1, row, 0, row.length);

        for (int column = 0; column < row.length; column++) {
            values[offset + column] = (byte) (row[column] >>> RED_SHIFT & CHANNEL_MASK);
        }
    }

    /**
     * Returns the pixels of a picture as non-premultiplied ARGB values, row-major: the picture drawn once into an ARGB picture of its size.
     *
     * @param picture the picture
     * @return the pixels, {@code width * height} values
     */
    static int @NotNull [] readArgbPixels(@NotNull BufferedImage picture) {
        BufferedImage argb = new BufferedImage(picture.getWidth(), picture.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = argb.createGraphics();

        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(picture, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        return ((DataBufferInt) argb.getRaster().getDataBuffer()).getData();
    }

    private static @Nullable BufferedImage readOptionalPicture(@NotNull PackSource source, @NotNull String definitionPath, @Nullable String picture,
                                                               @NotNull BufferedImage color) throws IOException {
        return picture != null ? readPicture(source, definitionPath, picture, color.getWidth(), color.getHeight()) : null;
    }

    private static @NotNull BufferedImage readPicture(@NotNull PackSource source, @NotNull String definitionPath, @NotNull String picture,
                                                      int width, int height) throws IOException {
        String path = PackSource.resolveSibling(definitionPath, picture);
        BufferedImage image = readImage(source, definitionPath, path);

        if (image.getWidth() != width || image.getHeight() != height) {
            throw Exceptions.newIOException("{0}: the picture {1} is {2}x{3}, the frame grid needs {4}x{5}.", definitionPath, path, image.getWidth(),
                                            image.getHeight(), width, height);
        }

        return image;
    }

    /**
     * Reads a PNG picture of a pack.
     *
     * @param source         the pack files
     * @param definitionPath the pack path of the file that names the picture, for diagnostics
     * @param path           the pack path of the picture
     * @return the picture
     * @throws IOException if the file cannot be read or is not a readable PNG
     */
    static @NotNull BufferedImage readImage(@NotNull PackSource source, @NotNull String definitionPath, @NotNull String path) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source.read(path)));

        if (image == null) {
            throw Exceptions.newIOException("{0}: the picture {1} is not a readable PNG.", definitionPath, path);
        }

        return image;
    }

    private PackSpriteSheetUtils() {
    }
}
