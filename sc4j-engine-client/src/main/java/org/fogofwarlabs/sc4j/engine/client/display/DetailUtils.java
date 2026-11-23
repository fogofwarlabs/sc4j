package org.fogofwarlabs.sc4j.engine.client.display;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.ResourceKey;
import org.fogofwarlabs.sc4j.engine.resource.ResourceNotFoundException;
import org.fogofwarlabs.sc4j.engine.resource.ResourceProvider;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileGraphics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Names, loads and picks the detail versions of a font, picture, sprite sheet or tileset: the same resource drawn at a whole multiple of its
 * size, for displays whose pixels are smaller than a canvas unit. A resource provider names the detail of resource {@code <key>} at multiple
 * {@code n} {@code <key>-<n>x}, for example {@code body-2x}, for the multiples {@value #MINIMUM_MULTIPLE} up to {@value #MAXIMUM_MULTIPLE}. A
 * drawer measures and lays out with the resource itself and draws from the smallest detail whose multiple is at least the physical pixels per
 * resource pixel, or from the largest one when none is that large, so that it draws from texels near one per pixel ({@link DetailSet}).
 * <p>
 * Everything a detail places is {@code n} times the resource's: the size of a picture, its anchor and borders; the frame box of a sprite
 * sheet, its anchor and every frame's rectangle; the tile size of a tileset. A detail has the same layers (team colour mask, shadow), frames
 * and tiles as its resource, so that drawing from it only adds pixels.
 */
public final class DetailUtils {
    /**
     * The smallest multiple of a detail.
     */
    public static final int MINIMUM_MULTIPLE = 2;
    /**
     * The largest multiple of a detail that a drawer looks for.
     */
    public static final int MAXIMUM_MULTIPLE = 4;

    private static final @NotNull String KEY_SEPARATOR = "-";
    private static final @NotNull String KEY_SUFFIX = "x";
    /**
     * The tolerance of the pixels per resource pixel when a detail is picked, so that a scale computed from rounded sizes still picks the
     * detail of its multiple.
     */
    private static final float TOLERANCE = 0.05f;

    /**
     * Loads one resource of a provider.
     *
     * @param <T> the type of the resource
     */
    @FunctionalInterface
    private interface Loader<T> {
        @NotNull T load(@NotNull ResourceKey key) throws IOException;
    }

    /**
     * Finds what a detail does not place at its multiple of the resource.
     *
     * @param <T> the type of the resource
     */
    @FunctionalInterface
    private interface Check<T> {
        @Nullable String findMismatch(@NotNull T resource, @NotNull T detail, int multiple);
    }

    /**
     * Creates the key of a detail of a resource.
     *
     * @param key      the key of the resource
     * @param multiple the multiple of the detail
     * @return the created key {@code <key>-<multiple>x}
     */
    public static @NotNull ResourceKey createKey(@NotNull ResourceKey key, int multiple) {
        return ResourceKey.of(key.getName() + KEY_SEPARATOR + multiple + KEY_SUFFIX);
    }

    /**
     * Finds the detail a resource is drawn from.
     *
     * @param multiples     the multiples of the resource's details, smallest first
     * @param pixelsPerUnit the physical pixels per resource pixel of the drawing
     * @return the index of the detail in the multiples, or {@code -1} for the resource itself
     */
    public static int findDetail(int @NotNull [] multiples, float pixelsPerUnit) {
        if (multiples.length == 0 || pixelsPerUnit <= 1 + TOLERANCE) {
            return -1;
        }

        for (int index = 0; index < multiples.length; index++) {
            if (multiples[index] >= pixelsPerUnit - TOLERANCE) {
                return index;
            }
        }

        return multiples.length - 1;
    }

    /**
     * Loads a picture of a provider with its detail pictures.
     *
     * @param resources the provider
     * @param key       the key of the picture
     * @return the picture with its details
     * @throws ResourceNotFoundException if the provider has no picture under the key
     * @throws IOException               if the picture or a detail cannot be read
     * @throws IllegalArgumentException  if a detail does not place its size, anchor or borders at its multiple of the picture's, or differs in its team colour mask
     */
    public static @NotNull DetailSet<Picture> loadPictures(@NotNull ResourceProvider resources, @NotNull ResourceKey key) throws IOException {
        return load(key, resources::loadPicture, DetailUtils::findPictureMismatch);
    }

    /**
     * Loads a sprite sheet of a provider with its detail sheets.
     *
     * @param resources the provider
     * @param key       the key of the sprite sheet
     * @return the sprite sheet with its details
     * @throws ResourceNotFoundException if the provider has no sprite sheet under the key
     * @throws IOException               if the sheet or a detail cannot be read
     * @throws IllegalArgumentException  if a detail does not place its frame box, anchor or frames at its multiple of the sheet's, or differs in its frame count or layers
     */
    public static @NotNull DetailSet<SpriteSheet> loadSpriteSheets(@NotNull ResourceProvider resources, @NotNull ResourceKey key) throws IOException {
        return load(key, resources::loadSpriteSheet, DetailUtils::findSpriteSheetMismatch);
    }

    /**
     * Loads the tile pictures of a tileset of a provider with their detail pictures.
     *
     * @param resources the provider
     * @param key       the key of the tileset
     * @return the tile pictures with their details
     * @throws ResourceNotFoundException if the provider has no tileset under the key
     * @throws IOException               if the tile pictures or a detail cannot be read
     * @throws IllegalArgumentException  if a detail's tile size is not its multiple of the tileset's, or it differs in its tile count, shape or pixel format
     */
    public static @NotNull DetailSet<TileGraphics> loadTileGraphics(@NotNull ResourceProvider resources, @NotNull ResourceKey key) throws IOException {
        return load(key, resources::loadTileGraphics, DetailUtils::findTileGraphicsMismatch);
    }

    private static <T> @NotNull DetailSet<T> load(@NotNull ResourceKey key, @NotNull Loader<T> loader, @NotNull Check<T> check) throws IOException {
        T resource = loader.load(key);
        List<T> details = new ArrayList<>();
        int[] multiples = new int[MAXIMUM_MULTIPLE - MINIMUM_MULTIPLE + 1];

        for (int multiple = MINIMUM_MULTIPLE; multiple <= MAXIMUM_MULTIPLE; multiple++) {
            ResourceKey detailKey = createKey(key, multiple);
            T detail;

            try {
                detail = loader.load(detailKey);
            } catch (ResourceNotFoundException exception) {
                // The provider has no detail of this multiple.
                continue;
            }

            String mismatch = check.findMismatch(resource, detail, multiple);

            if (mismatch != null) {
                throw Exceptions.newIllegalArgumentException("The detail {0} is not {1} at {2} times: {3}", detailKey.getName(), key.getName(),
                                                             multiple, mismatch);
            }

            multiples[details.size()] = multiple;
            details.add(detail);
        }

        return new DetailSet<>(resource, details, Arrays.copyOf(multiples, details.size()));
    }

    private static @Nullable String findPictureMismatch(@NotNull Picture picture, @NotNull Picture detail, int multiple) {
        if (detail.getWidth() != picture.getWidth() * multiple || detail.getHeight() != picture.getHeight() * multiple) {
            return "size " + detail.getWidth() + "x" + detail.getHeight() + " of " + picture.getWidth() + "x" + picture.getHeight();
        }

        if (detail.getAnchorX() != picture.getAnchorX() * multiple || detail.getAnchorY() != picture.getAnchorY() * multiple) {
            return "anchor";
        }

        if (detail.getBorderLeft() != picture.getBorderLeft() * multiple || detail.getBorderTop() != picture.getBorderTop() * multiple ||
            detail.getBorderRight() != picture.getBorderRight() * multiple || detail.getBorderBottom() != picture.getBorderBottom() * multiple) {
            return "borders";
        }

        return (detail.getTeamMask() == null) != (picture.getTeamMask() == null) ? "team colour mask" : null;
    }

    private static @Nullable String findSpriteSheetMismatch(@NotNull SpriteSheet sheet, @NotNull SpriteSheet detail, int multiple) {
        if (detail.getWidth() != sheet.getWidth() * multiple || detail.getHeight() != sheet.getHeight() * multiple) {
            return "frame box " + detail.getWidth() + "x" + detail.getHeight() + " of " + sheet.getWidth() + "x" + sheet.getHeight();
        }

        if (detail.getAnchorX() != sheet.getAnchorX() * multiple || detail.getAnchorY() != sheet.getAnchorY() * multiple) {
            return "anchor";
        }

        if (detail.getColor().getFormat() != sheet.getColor().getFormat() || (detail.getTeamMask() == null) != (sheet.getTeamMask() == null) ||
            (detail.getShadow() == null) != (sheet.getShadow() == null)) {
            return "layers";
        }

        if (detail.getFrameCount() != sheet.getFrameCount()) {
            return "frame count " + detail.getFrameCount() + " of " + sheet.getFrameCount();
        }

        for (int index = 0; index < sheet.getFrameCount(); index++) {
            SpriteFrame frame = sheet.getFrame(index);
            SpriteFrame detailFrame = detail.getFrame(index);

            if (detailFrame.getX() != frame.getX() * multiple || detailFrame.getY() != frame.getY() * multiple ||
                detailFrame.getWidth() != frame.getWidth() * multiple || detailFrame.getHeight() != frame.getHeight() * multiple) {
                return "frame " + index;
            }
        }

        return null;
    }

    private static @Nullable String findTileGraphicsMismatch(@NotNull TileGraphics tiles, @NotNull TileGraphics detail, int multiple) {
        if (detail.getTileWidth() != tiles.getTileWidth() * multiple || detail.getTileHeight() != tiles.getTileHeight() * multiple) {
            return "tile size " + detail.getTileWidth() + "x" + detail.getTileHeight() + " of " + tiles.getTileWidth() + "x" + tiles.getTileHeight();
        }

        if (detail.getTileCount() != tiles.getTileCount() || detail.getShape() != tiles.getShape() ||
            detail.getPixels().getFormat() != tiles.getPixels().getFormat()) {
            return "tiles";
        }

        return null;
    }

    private DetailUtils() {
    }
}
