package org.fogofwarlabs.sc4j.engine.pack;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.json.JsonObjectReader;
import org.fogofwarlabs.sc4j.engine.map.TilesetLegend;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileGraphics;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileShape;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a tileset definition of a pack: its PNG picture of tiles in a grid into a {@link TileGraphics} of the given size and
 * {@link TileShape}, tile {@code row * columns + column} the grid cell at that row and column, and its tile codes into a
 * {@link TilesetLegend}.
 */
final class PackTilesetUtils {
    /**
     * The field of the picture.
     */
    private static final @NotNull String IMAGE_FIELD = "image";
    /**
     * The field of the tile width.
     */
    private static final @NotNull String TILE_WIDTH_FIELD = "tileWidth";
    /**
     * The field of the tile height.
     */
    private static final @NotNull String TILE_HEIGHT_FIELD = "tileHeight";
    /**
     * The field of the tile shape.
     */
    private static final @NotNull String SHAPE_FIELD = "shape";
    /**
     * The field of the tile codes.
     */
    private static final @NotNull String TILES_FIELD = "tiles";

    /**
     * Reads the tile pictures of a tileset.
     *
     * @param source         the pack files
     * @param definitionPath the pack path of the tileset definition
     * @return the tile graphics
     * @throws IOException if a file cannot be read, the picture is not a PNG of whole tiles or a field is invalid
     */
    static @NotNull TileGraphics readGraphics(@NotNull PackSource source, @NotNull String definitionPath) throws IOException {
        JsonObjectReader definition = JsonObjectReader.parse(source.read(definitionPath), definitionPath);
        int tileWidth = definition.getInt(TILE_WIDTH_FIELD);
        int tileHeight = definition.getInt(TILE_HEIGHT_FIELD);
        TileShape shape = readShape(definition);

        if (tileWidth < 1 || tileHeight < 1) {
            throw Exceptions.newIOException("{0}: a tile must not be empty: {1}x{2}", definitionPath, tileWidth, tileHeight);
        }

        String path = PackSource.resolveSibling(definitionPath, definition.getText(IMAGE_FIELD));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source.read(path)));

        if (image == null) {
            throw Exceptions.newIOException("{0}: the picture {1} is not a readable PNG.", definitionPath, path);
        }

        if (image.getWidth() % tileWidth != 0 || image.getHeight() % tileHeight != 0) {
            throw Exceptions.newIOException("{0}: the picture {1} is {2}x{3}, no whole number of {4}x{5} tiles.", definitionPath, path,
                                            image.getWidth(), image.getHeight(), tileWidth, tileHeight);
        }

        int columns = image.getWidth() / tileWidth;
        int tileCount = columns * (image.getHeight() / tileHeight);
        int tilePixels = tileWidth * tileHeight;
        int[] colors = new int[tileCount * tilePixels];
        int[] pixels = PackSpriteSheetUtils.readArgbPixels(image);
        int imageWidth = image.getWidth();

        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            int tileX = tileIndex % columns * tileWidth;
            int tileY = tileIndex / columns * tileHeight;

            for (int y = 0; y < tileHeight; y++) {
                System.arraycopy(pixels, (tileY + y) * imageWidth + tileX, colors, tileIndex * tilePixels + y * tileWidth, tileWidth);
            }
        }

        return new TileGraphics(tileWidth, tileHeight, shape, PixelLayer.ofRgba(colors));
    }

    /**
     * Reads the tile codes of a tileset: each code of the engine map format to its tile indices.
     *
     * @param source         the pack files
     * @param definitionPath the pack path of the tileset definition
     * @param tileCount      the number of tiles of the tileset's picture
     * @return the legend
     * @throws IOException if the definition cannot be read, a code is not one character or a tile index names no tile
     */
    static @NotNull TilesetLegend readLegend(@NotNull PackSource source, @NotNull String definitionPath, int tileCount) throws IOException {
        JsonObjectReader definition = JsonObjectReader.parse(source.read(definitionPath), definitionPath);
        JsonObjectReader tiles = definition.getObject(TILES_FIELD);
        Map<Character, int[]> codes = new LinkedHashMap<>();

        for (Object name : tiles.getNames()) {
            String code = String.valueOf(name);
            String location = tiles.getLocation(code);
            List<?> elements = tiles.getArray(code);
            int[] indices = new int[elements.size()];

            if (code.length() != 1 || indices.length == 0) {
                throw Exceptions.newIOException("{0}: a tile code is one character with at least one tile index.", location);
            }

            for (int index = 0; index < indices.length; index++) {
                indices[index] = JsonObjectReader.toInt(elements.get(index), location + "[" + index + "]");

                if (indices[index] < 0 || indices[index] >= tileCount) {
                    throw Exceptions.newIOException("{0}[{1}]: no tile {2}; the tileset has {3}.", location, index, indices[index], tileCount);
                }
            }

            codes.put(code.charAt(0), indices);
        }

        return new TilesetLegend(codes);
    }

    private static @NotNull TileShape readShape(@NotNull JsonObjectReader definition) throws IOException {
        String shape = definition.getText(SHAPE_FIELD);

        for (TileShape candidate : TileShape.values()) {
            if (candidate.name().toLowerCase(Locale.ROOT).equals(shape)) {
                return candidate;
            }
        }

        throw Exceptions.newIOException("{0}: unknown tile shape {1}; expected square or diamond.", definition.getLocation(SHAPE_FIELD), shape);
    }

    private PackTilesetUtils() {
    }
}
