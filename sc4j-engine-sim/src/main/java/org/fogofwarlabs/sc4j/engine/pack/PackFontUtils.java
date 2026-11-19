package org.fogofwarlabs.sc4j.engine.pack;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.json.JsonObjectReader;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.text.FontFace;
import org.fogofwarlabs.sc4j.engine.resource.text.FontGlyph;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads a font definition of a pack: a PNG glyph sheet of equal cells, one character per cell in the order of the definition's
 * {@code characters}, into a {@link FontFace} whose coverage is the alpha channel of the sheet.
 * <p>
 * Each glyph is the bounding box of the pixels of its cell that are not fully transparent. The glyph starts at the pen position, its
 * top keeps its distance from the cell top, and the pen advances by its width plus the spacing; a cell without such pixels draws
 * nothing and advances by the space advance.
 */
final class PackFontUtils {
    /**
     * The field of the glyph sheet.
     */
    private static final @NotNull String IMAGE_FIELD = "image";
    /**
     * The field of the cell width.
     */
    private static final @NotNull String CELL_WIDTH_FIELD = "cellWidth";
    /**
     * The field of the cell height.
     */
    private static final @NotNull String CELL_HEIGHT_FIELD = "cellHeight";
    /**
     * The field of the characters in cell order.
     */
    private static final @NotNull String CHARACTERS_FIELD = "characters";
    /**
     * The field of the line height, by default the cell height.
     */
    private static final @NotNull String LINE_HEIGHT_FIELD = "lineHeight";
    /**
     * The field of the distance between two glyphs, by default {@value #DEFAULT_SPACING}.
     */
    private static final @NotNull String SPACING_FIELD = "spacing";
    /**
     * The field of the advance of an empty cell, by default half the cell width.
     */
    private static final @NotNull String SPACE_ADVANCE_FIELD = "spaceAdvance";

    /**
     * The default distance between two glyphs in pixels.
     */
    private static final int DEFAULT_SPACING = 1;
    /**
     * The shift of the alpha channel of a packed colour.
     */
    private static final int ALPHA_SHIFT = 24;

    /**
     * Reads a font face.
     *
     * @param source         the pack files
     * @param definitionPath the pack path of the font definition
     * @return the font face
     * @throws IOException if a file cannot be read, the sheet is not a PNG of whole cells or too small for the characters, a character is
     *                     listed twice or a field is invalid
     */
    static @NotNull FontFace read(@NotNull PackSource source, @NotNull String definitionPath) throws IOException {
        JsonObjectReader definition = JsonObjectReader.parse(source.read(definitionPath), definitionPath);
        int cellWidth = definition.getInt(CELL_WIDTH_FIELD);
        int cellHeight = definition.getInt(CELL_HEIGHT_FIELD);
        int[] characters = definition.getText(CHARACTERS_FIELD).codePoints().toArray();
        int lineHeight = definition.getInt(LINE_HEIGHT_FIELD, cellHeight);
        int spacing = definition.getInt(SPACING_FIELD, DEFAULT_SPACING);
        int spaceAdvance = definition.getInt(SPACE_ADVANCE_FIELD, cellWidth / 2);

        if (cellWidth < 1 || cellHeight < 1 || lineHeight < 1) {
            throw Exceptions.newIOException("{0}: the cells and the line must not be empty: {1}x{2}, line {3}", definitionPath, cellWidth, cellHeight,
                                            lineHeight);
        }

        String path = PackSource.resolveSibling(definitionPath, definition.getText(IMAGE_FIELD));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source.read(path)));

        if (image == null) {
            throw Exceptions.newIOException("{0}: the picture {1} is not a readable PNG.", definitionPath, path);
        }

        int columns = image.getWidth() / cellWidth;

        if (image.getWidth() % cellWidth != 0 || image.getHeight() % cellHeight != 0 ||
            characters.length > columns * (image.getHeight() / cellHeight)) {
            throw Exceptions.newIOException("{0}: the picture {1} is {2}x{3}, not whole {4}x{5} cells for {6} characters.", definitionPath, path,
                                            image.getWidth(), image.getHeight(), cellWidth, cellHeight, characters.length);
        }

        int[] colors = PackSpriteSheetUtils.readArgbPixels(image);
        byte[] coverage = new byte[colors.length];
        List<FontGlyph> glyphs = new ArrayList<>(characters.length);

        for (int index = 0; index < colors.length; index++) {
            coverage[index] = (byte) (colors[index] >>> ALPHA_SHIFT);
        }

        for (int cell = 0; cell < characters.length; cell++) {
            glyphs.add(createGlyph(colors, image.getWidth(), characters[cell], cell % columns * cellWidth, cell / columns * cellHeight, cellWidth,
                                   cellHeight, spacing, spaceAdvance));
        }

        glyphs.sort(Comparator.comparingInt(FontGlyph::getCodePoint));

        for (int index = 1; index < glyphs.size(); index++) {
            if (glyphs.get(index).getCodePoint() == glyphs.get(index - 1).getCodePoint()) {
                throw Exceptions.newIOException("{0}: the character {1} is listed twice.", definition.getLocation(CHARACTERS_FIELD),
                                                Character.toString(glyphs.get(index).getCodePoint()));
            }
        }

        return new FontFace(lineHeight, image.getWidth(), PixelLayer.ofAlpha(coverage), glyphs);
    }

    private static @NotNull FontGlyph createGlyph(int @NotNull [] colors, int imageWidth, int codePoint, int cellX, int cellY, int cellWidth,
                                                  int cellHeight, int spacing, int spaceAdvance) {
        int left = cellWidth;
        int top = cellHeight;
        int right = -1;
        int bottom = -1;

        for (int y = 0; y < cellHeight; y++) {
            for (int x = 0; x < cellWidth; x++) {
                if (colors[(cellY + y) * imageWidth + cellX + x] >>> ALPHA_SHIFT != 0) {
                    left   = Math.min(left, x);
                    top    = Math.min(top, y);
                    right  = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }

        if (right < 0) {
            return new FontGlyph(codePoint, spaceAdvance, 0, 0, 0, 0, cellX, cellY);
        }

        int width = right - left + 1;

        return new FontGlyph(codePoint, width + spacing, 0, top, width, bottom - top + 1, cellX + left, cellY + top);
    }

    private PackFontUtils() {
    }
}
