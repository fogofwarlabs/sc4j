package org.fogofwarlabs.sc4j.engine.client.text;

import org.fogofwarlabs.sc4j.engine.client.HiddenWindowScenario;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.fogofwarlabs.sc4j.engine.client.render.gl.GlCanvas;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.text.FontFace;
import org.fogofwarlabs.sc4j.engine.resource.text.FontGlyph;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests the default bitmap font: glyph advances, colour codes (with the default {@link TextColorTable}), the style's kerning, scale
 * and pixel snapping, and the detail face picked for a scaled canvas.
 */
final class BitmapFontTest {
    private static final int FIELD_PAGE_SIZE = 64;
    private static final int LINE_HEIGHT = 8;
    private static final int IMAGE_WIDTH = 8;
    private static final int IMAGE_HEIGHT = 4;
    private static final int GLYPH_SIZE = 4;
    private static final int ADVANCE = 5;
    private static final int SPACE_ADVANCE = 2;
    private static final int GREEN_CODE = 0x01;
    private static final int RED = 0xFF0000FF;
    private static final int GREEN = 0x00FF00FF;
    private static final int TEXT_LEFT = 4;
    private static final int TEXT_TOP = 6;
    private static final int RESTORE_CODE = 0x02;
    private static final int KERNING = 1;
    private static final float SCALE = 2;
    private static final float FRACTIONAL_LEFT = 4.4f;
    private static final float FRACTIONAL_TOP = 6.6f;
    /**
     * The arguments of a recorded glyph quad: left, top, width, height.
     */
    private static final int QUAD_VALUES = 4;
    private static final int DETAIL_MULTIPLE = 2;
    private static final int CANVAS_UNITS = 32;

    private static final @NotNull TextColorHook GREEN_CODE_HOOK = new TextColorHook() {
        @Override
        public boolean isColorCode(int codePoint) {
            return codePoint == GREEN_CODE;
        }

        @Override
        public int getColor(int codePoint, int lineColor, int currentColor, int previousColor) {
            return GREEN;
        }
    };

    /**
     * Records the glyph quads a font draws, without a window.
     */
    private static final class RecordingCanvas {
        private final @NotNull List<float[]> quads;
        private final @NotNull List<Integer> colors;
        private final @NotNull Canvas canvas;

        private RecordingCanvas() {
            this.quads  = new ArrayList<>();
            this.colors = new ArrayList<>();

            Texture texture = (Texture) Proxy.newProxyInstance(Texture.class.getClassLoader(), new Class<?>[]{Texture.class},
                                                               (proxy, method, arguments) -> null);

            this.canvas = (Canvas) Proxy.newProxyInstance(Canvas.class.getClassLoader(), new Class<?>[]{Canvas.class}, (proxy, method, arguments) -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, arguments);
                }

                if (method.getName().equals("uploadTexture")) {
                    return texture;
                }

                if (method.getName().equals("drawTexture")) {
                    float[] quad = new float[QUAD_VALUES];

                    for (int index = 0; index < QUAD_VALUES; index++) {
                        quad[index] = (Float) arguments[index + 1];
                    }

                    this.quads.add(quad);
                    this.colors.add((Integer) arguments[arguments.length - 1]);
                }

                return null;
            });
        }
    }

    private static @NotNull FontFace createFace() {
        byte[] alphas = new byte[IMAGE_WIDTH * IMAGE_HEIGHT];

        Arrays.fill(alphas, (byte) -1);

        return new FontFace(LINE_HEIGHT, IMAGE_WIDTH, PixelLayer.ofAlpha(alphas),
                            List.of(new FontGlyph(' ', SPACE_ADVANCE, 0, 0, 0, 0, 0, 0),
                                    new FontGlyph('A', ADVANCE, 0, 0, GLYPH_SIZE, GLYPH_SIZE, 0, 0),
                                    new FontGlyph('B', ADVANCE, 0, 0, GLYPH_SIZE, GLYPH_SIZE, GLYPH_SIZE, 0)));
    }

    /**
     * Creates the detail face of {@link #createFace} at twice the size.
     *
     * @return the created {@link FontFace} instance
     */
    private static @NotNull FontFace createDetailFace() {
        int width = IMAGE_WIDTH * DETAIL_MULTIPLE;
        int height = IMAGE_HEIGHT * DETAIL_MULTIPLE;
        int glyphSize = GLYPH_SIZE * DETAIL_MULTIPLE;
        byte[] alphas = new byte[width * height];

        return new FontFace(LINE_HEIGHT * DETAIL_MULTIPLE, width, PixelLayer.ofAlpha(alphas),
                            List.of(new FontGlyph('A', ADVANCE * DETAIL_MULTIPLE, 0, 0, glyphSize, glyphSize, 0, 0)));
    }

    @Test
    void find_drawn_multiple_should_pick_the_detail_face_for_the_pixels_per_unit() {
        ImageCanvas canvas = new ImageCanvas(CANVAS_UNITS * DETAIL_MULTIPLE, CANVAS_UNITS * DETAIL_MULTIPLE);

        try (BitmapFont font = new BitmapFont(canvas, createFace(), List.of(createDetailFace()), BitmapFont.NO_KERNING, TextStyle.DEFAULT)) {
            Assertions.assertArrayEquals(new int[]{DETAIL_MULTIPLE}, font.getDetailMultiples());

            canvas.begin(CANVAS_UNITS * DETAIL_MULTIPLE, CANVAS_UNITS * DETAIL_MULTIPLE);
            Assertions.assertEquals(1, font.findDrawnMultiple(canvas, TextStyle.DEFAULT), "one pixel per unit draws the font's own face");
            Assertions.assertEquals(DETAIL_MULTIPLE, font.findDrawnMultiple(canvas, TextStyle.builder().setScale(1.5f).build()));
            canvas.end();

            canvas.begin(UiSurface.of(CANVAS_UNITS * DETAIL_MULTIPLE, CANVAS_UNITS * DETAIL_MULTIPLE, DisplayScale.of(DETAIL_MULTIPLE, 1)));
            Assertions.assertEquals(DETAIL_MULTIPLE, font.findDrawnMultiple(canvas, TextStyle.DEFAULT), "two pixels per unit draw the detail face");
            canvas.end();
        }
    }

    @Test
    void constructor_should_reject_a_detail_face_that_is_no_whole_multiple() {
        ImageCanvas canvas = new ImageCanvas(CANVAS_UNITS, CANVAS_UNITS);
        FontFace odd = new FontFace(LINE_HEIGHT + 1, IMAGE_WIDTH, PixelLayer.ofAlpha(new byte[IMAGE_WIDTH * IMAGE_HEIGHT]), List.of());

        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> new BitmapFont(canvas, createFace(), List.of(odd), BitmapFont.NO_KERNING, TextStyle.DEFAULT));
    }

    @Test
    void measure_should_add_advances_and_skip_color_codes_and_missing_glyphs() {
        HiddenWindowScenario.run(() -> {
            try (GlCanvas canvas = new GlCanvas(FIELD_PAGE_SIZE);
                 BitmapFont font = new BitmapFont(canvas, createFace(), GREEN_CODE_HOOK)) {
                Assertions.assertEquals(LINE_HEIGHT, font.getLineHeight());
                Assertions.assertEquals(ADVANCE + SPACE_ADVANCE + ADVANCE, font.measure("A \u0001B"));
                Assertions.assertEquals(ADVANCE, font.measure("A?"));
            }
        });
    }

    @Test
    void draw_should_draw_glyphs_in_the_pen_color_after_color_codes() {
        RecordingCanvas recording = new RecordingCanvas();

        try (BitmapFont font = new BitmapFont(recording.canvas, createFace(), GREEN_CODE_HOOK)) {
            float pen = font.draw(recording.canvas, "A\u0001B", TEXT_LEFT, TEXT_TOP, RED);

            Assertions.assertEquals(TEXT_LEFT + ADVANCE + ADVANCE, pen);
            Assertions.assertEquals(List.of(RED, GREEN), recording.colors);
        }
    }

    @Test
    void draw_should_switch_back_after_a_restore_code_of_the_color_table() {
        TextColorTable table = TextColorTable.builder().setColor(GREEN_CODE, GREEN).setRestoreCode(RESTORE_CODE).build();
        TextStyle style = TextStyle.builder().setColors(table).build();
        RecordingCanvas recording = new RecordingCanvas();

        try (BitmapFont font = new BitmapFont(recording.canvas, createFace(), BitmapFont.NO_KERNING, style)) {
            font.draw(recording.canvas, "AB", TEXT_LEFT, TEXT_TOP, RED);

            Assertions.assertEquals(List.of(GREEN, RED), recording.colors);
        }
    }

    @Test
    void measure_and_draw_should_apply_the_kerning_and_scale_of_the_style() {
        RecordingCanvas recording = new RecordingCanvas();

        try (BitmapFont font = new BitmapFont(recording.canvas, createFace(), KERNING, TextStyle.DEFAULT)) {
            Assertions.assertEquals(KERNING, font.getKerning());

            TextStyle scaled = TextStyle.builder().setScale(SCALE).build();
            TextStyle unkerned = TextStyle.builder(scaled).setKerning(false).build();

            Assertions.assertEquals(ADVANCE + KERNING + SPACE_ADVANCE + ADVANCE + KERNING, font.measure("A B"));
            Assertions.assertEquals((ADVANCE + KERNING + SPACE_ADVANCE + ADVANCE + KERNING) * SCALE, font.measure("A B", scaled));
            Assertions.assertEquals((ADVANCE + SPACE_ADVANCE + ADVANCE) * SCALE, font.measure("A B", unkerned));

            float pen = font.draw(recording.canvas, "A B", TEXT_LEFT, TEXT_TOP, RED, scaled);

            Assertions.assertEquals(TEXT_LEFT + font.measure("A B", scaled), pen);
            Assertions.assertEquals(2, recording.quads.size());
            Assertions.assertArrayEquals(new float[]{TEXT_LEFT, TEXT_TOP, GLYPH_SIZE * SCALE, GLYPH_SIZE * SCALE}, recording.quads.get(0));

            float secondLeft = TEXT_LEFT + (ADVANCE + KERNING + SPACE_ADVANCE) * SCALE;

            Assertions.assertArrayEquals(new float[]{secondLeft, TEXT_TOP, GLYPH_SIZE * SCALE, GLYPH_SIZE * SCALE}, recording.quads.get(1));
            Assertions.assertEquals(List.of(RED, RED), recording.colors);
        }
    }

    @Test
    void draw_should_place_glyphs_on_whole_pixels_when_pixel_snapped() {
        RecordingCanvas recording = new RecordingCanvas();

        try (BitmapFont font = new BitmapFont(recording.canvas, createFace(), BitmapFont.NO_KERNING, TextStyle.DEFAULT)) {
            TextStyle snapped = TextStyle.builder().setPixelSnapped(true).build();

            float exactPen = font.draw(recording.canvas, "AB", FRACTIONAL_LEFT, FRACTIONAL_TOP, RED);
            float snappedPen = font.draw(recording.canvas, "AB", FRACTIONAL_LEFT, FRACTIONAL_TOP, RED, snapped);

            Assertions.assertEquals(exactPen, snappedPen);
            Assertions.assertArrayEquals(new float[]{FRACTIONAL_LEFT, FRACTIONAL_TOP, GLYPH_SIZE, GLYPH_SIZE}, recording.quads.get(0));
            Assertions.assertArrayEquals(new float[]{Math.round(FRACTIONAL_LEFT), Math.round(FRACTIONAL_TOP), GLYPH_SIZE, GLYPH_SIZE},
                                         recording.quads.get(2));
            Assertions.assertArrayEquals(new float[]{Math.round(FRACTIONAL_LEFT + ADVANCE), Math.round(FRACTIONAL_TOP), GLYPH_SIZE, GLYPH_SIZE},
                                         recording.quads.get(3));
        }
    }
}
