package org.fogofwarlabs.sc4j.engine.resource.text;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelFormat;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Holds a bitmap font of one size: its line height, its glyph image and the glyphs of the characters it has, sorted by
 * code point.
 * <p>
 * The glyph image is one layer of {@code imageWidth} pixels per row, row-major: {@link PixelFormat#ALPHA_8} coverage, or
 * {@link PixelFormat#INDEXED_8} shades that the text renderer maps to the colours of the current text colour. Each glyph
 * names its rectangle in the image, so a renderer can upload the image as one atlas texture. A glyph of zero size draws
 * nothing and only advances the pen, for example a space.
 */
public final class FontFace {
    private final int lineHeight;

    private final int imageWidth;
    private final int imageHeight;
    private final @NotNull PixelLayer pixels;

    private final @NotNull List<@NotNull FontGlyph> glyphs;

    /**
     * Creates a {@link FontFace} instance.
     *
     * @param lineHeight the distance between two lines in pixels, at least {@code 1}
     * @param imageWidth the width of the glyph image in pixels, at least {@code 1}
     * @param pixels     the glyph image, alpha or indexed, a whole number of rows
     * @param glyphs     the glyphs in ascending code point order without duplicates, copied
     * @throws IllegalArgumentException if an argument does not meet its description or a glyph leaves the image
     */
    public FontFace(int lineHeight, int imageWidth, @NotNull PixelLayer pixels, @NotNull List<@NotNull FontGlyph> glyphs) {
        if (lineHeight < 1 || imageWidth < 1) {
            throw Exceptions.newIllegalArgumentException("The line height and the image width must be positive: {0}, {1}", lineHeight, imageWidth);
        }

        if (!pixels.getFormat().isByteStored()) {
            throw Exceptions.newIllegalArgumentException("The glyph pixels must be alpha or indexed.");
        }

        if (pixels.getLength() % imageWidth != 0) {
            throw Exceptions.newIllegalArgumentException("The glyph image of {0} pixels holds no whole number of {1} pixel rows.",
                                                         pixels.getLength(), imageWidth);
        }

        int imageHeight = pixels.getLength() / imageWidth;

        for (int index = 0; index < glyphs.size(); index++) {
            FontGlyph glyph = glyphs.get(index);

            if (index > 0 && glyph.getCodePoint() <= glyphs.get(index - 1).getCodePoint()) {
                throw Exceptions.newIllegalArgumentException("The glyphs are not in ascending code point order at {0}.", glyph.getCodePoint());
            }

            if (glyph.getWidth() > 0 && glyph.getHeight() > 0 &&
                (glyph.getImageX() + glyph.getWidth() > imageWidth || glyph.getImageY() + glyph.getHeight() > imageHeight)) {
                throw Exceptions.newIllegalArgumentException("The glyph {0} leaves the {1}x{2} glyph image.",
                                                             glyph.getCodePoint(), imageWidth, imageHeight);
            }
        }

        this.lineHeight = lineHeight;

        this.imageWidth  = imageWidth;
        this.imageHeight = imageHeight;
        this.pixels      = pixels;

        this.glyphs = List.copyOf(glyphs);
    }

    /**
     * @return the distance between two lines in pixels
     */
    public int getLineHeight() {
        return this.lineHeight;
    }

    /**
     * @return the width of the glyph image in pixels
     */
    public int getImageWidth() {
        return this.imageWidth;
    }

    /**
     * @return the height of the glyph image in pixels
     */
    public int getImageHeight() {
        return this.imageHeight;
    }

    /**
     * @return the glyph image, {@link #getImageWidth()} pixels per row
     */
    public @NotNull PixelLayer getPixels() {
        return this.pixels;
    }

    /**
     * @return the glyphs in ascending code point order, unmodifiable
     */
    public @NotNull List<@NotNull FontGlyph> getGlyphs() {
        return this.glyphs;
    }

    /**
     * Finds the glyph of a character.
     *
     * @param codePoint the Unicode code point
     * @return the glyph, or {@code null} if the font has no glyph for the character
     */
    public @Nullable FontGlyph findGlyph(int codePoint) {
        int low = 0;
        int high = this.glyphs.size() - 1;

        while (low <= high) {
            int middle = (low + high) >>> 1;
            FontGlyph glyph = this.glyphs.get(middle);

            if (glyph.getCodePoint() < codePoint) {
                low = middle + 1;
            } else if (glyph.getCodePoint() > codePoint) {
                high = middle - 1;
            } else {
                return glyph;
            }
        }

        return null;
    }
}
