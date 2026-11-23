package org.fogofwarlabs.sc4j.engine.client.text;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.client.display.DetailUtils;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.fogofwarlabs.sc4j.engine.resource.ResourceKey;
import org.fogofwarlabs.sc4j.engine.resource.ResourceNotFoundException;
import org.fogofwarlabs.sc4j.engine.resource.ResourceProvider;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelFormat;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.text.FontFace;
import org.fogofwarlabs.sc4j.engine.resource.text.FontGlyph;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draws a {@link FontFace} of the resource model: the engine's default {@link Font}.
 * <p>
 * The glyph image is uploaded once as one atlas texture of the canvas; each glyph draws its rectangle of the atlas tinted by
 * the pen colour. {@link PixelFormat#ALPHA_8} glyph pixels give the coverage; for {@link PixelFormat#INDEXED_8} glyph pixels
 * every shade other than {@code 0} is fully covered, so the text has the pen colour only. The {@link TextColorHook} of the
 * style decides which characters are colour codes. The font's kerning is a fixed number of font units added to the advance of
 * every glyph that has pixels, when the style's kerning is on.
 * <p>
 * A font may have detail faces: the same font drawn at a whole multiple of its size (a line height that is that multiple of the font's), for
 * displays whose pixels are smaller than a canvas unit. Measuring always uses the font's own face; drawing picks the smallest detail face whose
 * multiple is at least the pixels per font unit (the canvas's {@link Canvas#getPixelScale} times the style's scale), or the largest one when
 * none is that large, and draws its glyphs at the font's size, so the text keeps its layout and is drawn from texels near one per pixel.
 * A pack names the detail faces of font {@code <key>} {@code <key>-2x}, {@code <key>-3x} and {@code <key>-4x} ({@link #load}, {@link DetailUtils}).
 */
public final class BitmapFont implements Font, AutoCloseable {
    /**
     * The kerning of a font that adds nothing between glyphs.
     */
    public static final int NO_KERNING = 0;

    private static final int OPAQUE_WHITE = 0xFFFFFFFF;
    private static final int WHITE_RGB = 0x00FFFFFF;
    private static final int ALPHA_SHIFT = 24;

    /**
     * Creates a {@link BitmapFont} instance from a font of a resource provider with the detail faces the provider has for it
     * ({@code <key>-2x} up to {@code <key>-4x}), without kerning, whose default style has the given colour codes, and uploads the glyph
     * images to the canvas.
     *
     * @param canvas   the canvas that draws the text, current
     * @param provider the resource provider
     * @param key      the key of the font
     * @param colors   the colour codes of the default style, or {@link TextColorHook#NONE}
     * @return the created {@link BitmapFont} instance, closed by the caller
     * @throws IOException              if the font or a detail face cannot be read
     * @throws IllegalArgumentException if a detail face is not a whole multiple of the font
     */
    public static @NotNull BitmapFont load(@NotNull Canvas canvas, @NotNull ResourceProvider provider, @NotNull ResourceKey key,
                                           @NotNull TextColorHook colors) throws IOException {
        FontFace face = provider.loadFont(key);
        List<FontFace> details = new ArrayList<>();

        for (int multiple = DetailUtils.MINIMUM_MULTIPLE; multiple <= DetailUtils.MAXIMUM_MULTIPLE; multiple++) {
            try {
                details.add(provider.loadFont(DetailUtils.createKey(key, multiple)));
            } catch (ResourceNotFoundException exception) {
                // The provider has no detail face of this multiple.
            }
        }

        return new BitmapFont(canvas, face, details, NO_KERNING, TextStyle.builder().setColors(colors).build());
    }

    private static int @NotNull [] createAtlasColors(@NotNull FontFace face) {
        PixelLayer pixels = face.getPixels();
        boolean coverage = pixels.getFormat() == PixelFormat.ALPHA_8;
        int[] colors = new int[pixels.getLength()];

        for (int offset = 0; offset < colors.length; offset++) {
            int value = pixels.getValue(offset);

            if (coverage) {
                colors[offset] = value << ALPHA_SHIFT | WHITE_RGB;
            } else {
                colors[offset] = value == 0 ? 0 : OPAQUE_WHITE;
            }
        }

        return colors;
    }

    private static boolean hasPixels(@NotNull FontGlyph glyph) {
        return glyph.getWidth() > 0 && glyph.getHeight() > 0;
    }


    private final @NotNull FontFace face;
    private final @NotNull FontFace @NotNull [] detailFaces;
    private final int @NotNull [] detailMultiples;
    private final int kerning;
    private final @NotNull TextStyle defaultStyle;

    private final @NotNull Texture atlas;
    private final @NotNull Texture @NotNull [] detailAtlases;

    /**
     * Creates a {@link BitmapFont} instance without kerning whose default style has the given colour codes, and uploads the
     * glyph image to the canvas.
     *
     * @param canvas the canvas that draws the text, current
     * @param face   the font of the resource model
     * @param colors the colour codes of the default style, or {@link TextColorHook#NONE}
     */
    public BitmapFont(@NotNull Canvas canvas, @NotNull FontFace face, @NotNull TextColorHook colors) {
        this(canvas, face, NO_KERNING, TextStyle.builder().setColors(colors).build());
    }

    /**
     * Creates a {@link BitmapFont} instance and uploads the glyph image to the canvas.
     *
     * @param canvas       the canvas that draws the text, current
     * @param face         the font of the resource model
     * @param kerning      the font units added to the advance of every glyph with pixels when a style's kerning is on, or {@link #NO_KERNING}
     * @param defaultStyle the style of the forms without one
     */
    public BitmapFont(@NotNull Canvas canvas, @NotNull FontFace face, int kerning, @NotNull TextStyle defaultStyle) {
        this(canvas, face, List.of(), kerning, defaultStyle);
    }

    /**
     * Creates a {@link BitmapFont} instance with detail faces and uploads the glyph images to the canvas.
     *
     * @param canvas       the canvas that draws the text, current
     * @param face         the font of the resource model
     * @param details      the detail faces, each a whole multiple of the font (its line height that multiple of the font's)
     * @param kerning      the font units added to the advance of every glyph with pixels when a style's kerning is on, or {@link #NO_KERNING}
     * @param defaultStyle the style of the forms without one
     * @throws IllegalArgumentException if a detail face is not a whole multiple of the font of at least {@code 2}
     */
    public BitmapFont(@NotNull Canvas canvas, @NotNull FontFace face, @NotNull List<@NotNull FontFace> details, int kerning,
                      @NotNull TextStyle defaultStyle) {
        List<FontFace> sorted = new ArrayList<>(details);
        int[] multiples = new int[sorted.size()];

        sorted.sort(Comparator.comparingInt(FontFace::getLineHeight));

        for (int index = 0; index < multiples.length; index++) {
            int lineHeight = sorted.get(index).getLineHeight();

            if (lineHeight % face.getLineHeight() != 0 || lineHeight / face.getLineHeight() < DetailUtils.MINIMUM_MULTIPLE) {
                throw Exceptions.newIllegalArgumentException("A detail face of line height {0} is no whole multiple of the line height {1}.",
                                                             lineHeight, face.getLineHeight());
            }

            multiples[index] = lineHeight / face.getLineHeight();
        }

        this.face            = face;
        this.detailFaces     = sorted.toArray(new FontFace[0]);
        this.detailMultiples = multiples;
        this.kerning         = kerning;
        this.defaultStyle    = defaultStyle;

        this.atlas         = canvas.uploadTexture(face.getImageWidth(), face.getImageHeight(), createAtlasColors(face));
        this.detailAtlases = new Texture[this.detailFaces.length];

        for (int index = 0; index < this.detailFaces.length; index++) {
            FontFace detail = this.detailFaces[index];

            this.detailAtlases[index] = canvas.uploadTexture(detail.getImageWidth(), detail.getImageHeight(), createAtlasColors(detail));
        }
    }

    /**
     * @return the font of the resource model
     */
    public @NotNull FontFace getFace() {
        return this.face;
    }

    /**
     * @return the multiples of the detail faces, smallest first, a copy
     */
    public int @NotNull [] getDetailMultiples() {
        return this.detailMultiples.clone();
    }

    /**
     * @return the font units added to the advance of every glyph with pixels when a style's kerning is on
     */
    public int getKerning() {
        return this.kerning;
    }

    /**
     * @return the distance between the tops of two lines in font units (times the style's scale on the canvas)
     */
    @Override
    public int getLineHeight() {
        return this.face.getLineHeight();
    }

    /**
     * @return the style of the forms without one: the font's own colour codes and kerning at one canvas unit per font unit
     */
    @Override
    public @NotNull TextStyle getDefaultStyle() {
        return this.defaultStyle;
    }


    /**
     * Returns the multiple of the face a line is drawn from on a canvas in a style.
     *
     * @param canvas the canvas, between its {@link Canvas#begin} and {@link Canvas#end}
     * @param style  the style
     * @return {@code 1} for the font's own face, or the multiple of the picked detail face
     */
    public int findDrawnMultiple(@NotNull Canvas canvas, @NotNull TextStyle style) {
        int index = DetailUtils.findDetail(this.detailMultiples, canvas.getPixelScale() * style.getScale());

        return index < 0 ? 1 : this.detailMultiples[index];
    }

    /**
     * Measures how far the pen moves over a line in a style.
     *
     * @param text  the line, without line breaks
     * @param style the style: its colour codes do not advance, its kerning and scale apply
     * @return the advance of the line in canvas units
     */
    @Override
    public float measure(@NotNull CharSequence text, @NotNull TextStyle style) {
        TextColorHook colors = style.getColors();
        int kerning = style.isKerning() ? this.kerning : NO_KERNING;
        int advance = 0;

        for (int index = 0; index < text.length(); ) {
            int codePoint = Character.codePointAt(text, index);

            index += Character.charCount(codePoint);

            if (colors.isColorCode(codePoint)) {
                continue;
            }

            FontGlyph glyph = this.face.findGlyph(codePoint);

            if (glyph != null) {
                advance += glyph.getAdvance() + (hasPixels(glyph) ? kerning : NO_KERNING);
            }
        }

        return advance * style.getScale();
    }

    /**
     * Draws a line in a style. The pen starts at {@code x} and moves by each advance times the style's scale, in canvas units.
     *
     * @param canvas the canvas that draws, between its {@link Canvas#begin} and {@link Canvas#end}; the one this font uploaded its glyph image to
     * @param text   the line, without line breaks
     * @param x      the left edge of the pen in canvas units
     * @param y      the line top in canvas units
     * @param color  the packed {@code 0xRRGGBBAA} colour the line starts in
     * @param style  the style of the line
     * @return the pen position after the line
     */
    @Override
    public float draw(@NotNull Canvas canvas, @NotNull CharSequence text, float x, float y, int color, @NotNull TextStyle style) {
        TextColorHook colors = style.getColors();
        int kerning = style.isKerning() ? this.kerning : NO_KERNING;
        float scale = style.getScale();
        boolean pixelSnapped = style.isPixelSnapped();
        int detail = DetailUtils.findDetail(this.detailMultiples, canvas.getPixelScale() * scale);
        FontFace detailFace = detail < 0 ? null : this.detailFaces[detail];
        float pen = x;
        int penColor = color;
        int previousColor = color;

        for (int index = 0; index < text.length(); ) {
            int codePoint = Character.codePointAt(text, index);

            index += Character.charCount(codePoint);

            if (colors.isColorCode(codePoint)) {
                int nextColor = colors.getColor(codePoint, color, penColor, previousColor);

                previousColor = penColor;
                penColor      = nextColor;

                continue;
            }

            FontGlyph glyph = this.face.findGlyph(codePoint);

            if (glyph == null) {
                continue;
            }

            if (hasPixels(glyph)) {
                FontGlyph detailGlyph = detailFace == null ? null : detailFace.findGlyph(codePoint);

                // A detail face without the glyph draws it from the font's own face.
                if (detailFace != null && detailGlyph != null && hasPixels(detailGlyph)) {
                    this.drawGlyph(canvas, this.detailAtlases[detail], detailFace, detailGlyph, pen, y, scale / this.detailMultiples[detail],
                                   pixelSnapped, penColor);
                } else {
                    this.drawGlyph(canvas, this.atlas, this.face, glyph, pen, y, scale, pixelSnapped, penColor);
                }

                pen += (glyph.getAdvance() + kerning) * scale;
            } else {
                pen += glyph.getAdvance() * scale;
            }
        }

        return pen;
    }

    private void drawGlyph(@NotNull Canvas canvas, @NotNull Texture atlas, @NotNull FontFace drawnFace, @NotNull FontGlyph glyph, float pen, float y,
                           float scale, boolean pixelSnapped, int color) {
        float imageWidth = drawnFace.getImageWidth();
        float imageHeight = drawnFace.getImageHeight();
        float left = pen + glyph.getOffsetX() * scale;
        float top = y + glyph.getOffsetY() * scale;

        if (pixelSnapped) {
            left = Math.round(left);
            top  = Math.round(top);
        }

        canvas.drawTexture(atlas,
                           left, top, glyph.getWidth() * scale, glyph.getHeight() * scale,
                           glyph.getImageX() / imageWidth, glyph.getImageY() / imageHeight,
                           (glyph.getImageX() + glyph.getWidth()) / imageWidth, (glyph.getImageY() + glyph.getHeight()) / imageHeight,
                           color);
    }


    /**
     * Disposes resources: releases the glyph atlas textures, the detail faces' last first.
     */
    @Override
    public void close() {
        for (int index = this.detailAtlases.length - 1; index >= 0; index--) {
            IgnoreException.execute(
                    this.detailAtlases[index]::close
            );
        }

        this.atlas.close();
    }
}
