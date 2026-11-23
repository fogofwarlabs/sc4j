package org.fogofwarlabs.sc4j.engine.pack;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.json.JsonObjectReader;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;

/**
 * Reads a picture of a pack into a {@link Picture}: either a PNG file, a plain picture without team colour mask with its anchor at the
 * top-left corner and without borders, or a JSON picture definition that names its PNG pictures relative to its own folder, with an optional
 * team mask, anchor and borders.
 * <p>
 * The colour picture becomes an RGBA layer; the team mask is the first channel of its picture (grey for a greyscale PNG, red otherwise), as
 * in a sprite sheet.
 */
final class PackPictureUtils {
    /**
     * The file name ending of a PNG picture.
     */
    private static final @NotNull String PNG_ENDING = ".png";
    /**
     * The file name ending of a picture definition.
     */
    private static final @NotNull String DEFINITION_ENDING = ".json";
    /**
     * The field of the borders object.
     */
    private static final @NotNull String BORDERS_FIELD = "borders";
    /**
     * The field of the left border width.
     */
    private static final @NotNull String BORDER_LEFT_FIELD = "left";
    /**
     * The field of the top border height.
     */
    private static final @NotNull String BORDER_TOP_FIELD = "top";
    /**
     * The field of the right border width.
     */
    private static final @NotNull String BORDER_RIGHT_FIELD = "right";
    /**
     * The field of the bottom border height.
     */
    private static final @NotNull String BORDER_BOTTOM_FIELD = "bottom";

    /**
     * Reads a picture. The picture is invalid if the path is neither a PNG picture nor a JSON definition, a picture is not a PNG, the team
     * mask has another size than the picture, or a field is invalid.
     *
     * @param source the pack files
     * @param path   the pack path of the PNG picture or of the picture definition
     * @return the picture
     * @throws IOException if a file cannot be read or the picture is invalid
     */
    static @NotNull Picture read(@NotNull PackSource source, @NotNull String path) throws IOException {
        String name = path.toLowerCase(Locale.ROOT);

        if (name.endsWith(PNG_ENDING)) {
            BufferedImage image = PackSpriteSheetUtils.readImage(source, path, path);

            return new Picture(image.getWidth(), image.getHeight(), PixelLayer.ofRgba(readColors(image)));
        }

        if (!name.endsWith(DEFINITION_ENDING)) {
            throw Exceptions.newIOException("{0}: a picture is a {1} picture or a {2} picture definition.", path, PNG_ENDING, DEFINITION_ENDING);
        }

        JsonObjectReader definition = JsonObjectReader.parse(source.read(path), path);
        BufferedImage image = PackSpriteSheetUtils.readImage(source, path,
                                                             PackSource.resolveSibling(path, definition.getText(PackSpriteSheetUtils.IMAGE_FIELD)));
        String teamMaskName = definition.findText(PackSpriteSheetUtils.TEAM_MASK_FIELD);
        PixelLayer teamMask = teamMaskName != null ? readTeamMask(source, path, PackSource.resolveSibling(path, teamMaskName), image) : null;
        int anchorX = 0;
        int anchorY = 0;
        int borderLeft = 0;
        int borderTop = 0;
        int borderRight = 0;
        int borderBottom = 0;

        if (definition.has(PackSpriteSheetUtils.ANCHOR_FIELD)) {
            JsonObjectReader anchor = definition.getObject(PackSpriteSheetUtils.ANCHOR_FIELD);

            anchorX = anchor.getInt(PackSpriteSheetUtils.ANCHOR_X_FIELD);
            anchorY = anchor.getInt(PackSpriteSheetUtils.ANCHOR_Y_FIELD);
        }

        if (definition.has(BORDERS_FIELD)) {
            JsonObjectReader borders = definition.getObject(BORDERS_FIELD);

            borderLeft   = borders.getInt(BORDER_LEFT_FIELD, 0);
            borderTop    = borders.getInt(BORDER_TOP_FIELD, 0);
            borderRight  = borders.getInt(BORDER_RIGHT_FIELD, 0);
            borderBottom = borders.getInt(BORDER_BOTTOM_FIELD, 0);
        }

        try {
            return new Picture(image.getWidth(), image.getHeight(), PixelLayer.ofRgba(readColors(image)), teamMask, anchorX, anchorY,
                               borderLeft, borderTop, borderRight, borderBottom);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "{0}: invalid picture: {1}", definition.getLocation(BORDERS_FIELD), exception.getMessage());
        }
    }

    private static int @NotNull [] readColors(@NotNull BufferedImage image) {
        return PackSpriteSheetUtils.readArgbPixels(image);
    }

    private static @NotNull PixelLayer readTeamMask(@NotNull PackSource source, @NotNull String definitionPath, @NotNull String path,
                                                    @NotNull BufferedImage color) throws IOException {
        BufferedImage mask = PackSpriteSheetUtils.readImage(source, definitionPath, path);

        if (mask.getWidth() != color.getWidth() || mask.getHeight() != color.getHeight()) {
            throw Exceptions.newIOException("{0}: the team mask {1} is {2}x{3}, the picture {4}x{5}.", definitionPath, path, mask.getWidth(),
                                            mask.getHeight(), color.getWidth(), color.getHeight());
        }

        byte[] values = new byte[mask.getWidth() * mask.getHeight()];
        int[] row = new int[mask.getWidth()];

        for (int y = 0; y < mask.getHeight(); y++) {
            PackSpriteSheetUtils.readMaskRow(mask, 0, y, row, values, y * mask.getWidth());
        }

        return PixelLayer.ofAlpha(values);
    }

    private PackPictureUtils() {
    }
}
