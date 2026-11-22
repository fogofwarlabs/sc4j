package org.fogofwarlabs.sc4j.engine.client.render;

import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.pack.StandardPack;
import org.fogofwarlabs.sc4j.engine.resource.ResourceKey;
import org.fogofwarlabs.sc4j.engine.resource.ResourceNotFoundException;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests the picture renderer: a picture with a team colour mask composed once per team colour, pictures loaded once from the resource provider,
 * and the detail picture picked for the display's pixels per picture pixel.
 */
final class PictureRendererTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int TEAM_COLOR = 0x2040E0;
    private static final int HALF_MASK = 128;
    private static final int FULL_MASK = 255;
    private static final int DETAIL_MULTIPLE = 2;
    private static final int WRONG_DETAIL_SIZE = 3;
    private static final float HALF_SIZE = 0.5f;
    private static final int CANVAS_SIZE = 8;

    private static @NotNull StandardPack openPack(@NotNull Path folder) throws IOException {
        BufferedImage icon = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

        icon.setRGB(0, 0, RED);
        ImageIO.write(icon, "png", folder.resolve("icon.png").toFile());
        Files.writeString(folder.resolve("pack.json"), "{\"format\": 1, \"pictures\": {\"icons/red\": \"icon.png\"}}", StandardCharsets.UTF_8);

        return StandardPack.open(folder);
    }

    private static void writePicture(@NotNull Path folder, @NotNull String name, int size, int color) throws IOException {
        BufferedImage picture = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                picture.setRGB(x, y, color);
            }
        }

        ImageIO.write(picture, "png", folder.resolve(name).toFile());
    }

    @Test
    void get_texture_should_compose_a_masked_picture_once_per_team_colour(@TempDir @NotNull Path folder) throws IOException {
        int[] colors = {BLACK, WHITE, RED};
        byte[] mask = {(byte) FULL_MASK, (byte) HALF_MASK, 0};
        Picture portrait = new Picture(colors.length, 1, PixelLayer.ofRgba(colors), PixelLayer.ofAlpha(mask), 0, 0, 0, 0, 0, 0);
        ImageCanvas canvas = new ImageCanvas(CANVAS_SIZE, CANVAS_SIZE);

        try (StandardPack pack = openPack(folder);
             PictureRenderer renderer = new PictureRenderer(canvas, pack)) {
            canvas.begin(CANVAS_SIZE, CANVAS_SIZE);
            renderer.draw(canvas, portrait, 0, 0, colors.length, 1, PictureScaling.STRETCH, TEAM_COLOR, PictureRenderer.OPAQUE_WHITE);
            canvas.end();

            Assertions.assertSame(renderer.getTexture(portrait, TEAM_COLOR), renderer.getTexture(portrait, TEAM_COLOR), "composed once");
        }
    }

    @Test
    void get_picture_should_load_once_and_find_picture_should_skip_missing_pictures(@TempDir @NotNull Path folder) throws IOException {
        ImageCanvas canvas = new ImageCanvas(CANVAS_SIZE, CANVAS_SIZE);

        try (StandardPack pack = openPack(folder);
             PictureRenderer renderer = new PictureRenderer(canvas, pack)) {
            ResourceKey key = ResourceKey.of("icons/red");
            ResourceKey missing = ResourceKey.of("icons/missing");

            Assertions.assertSame(renderer.getPicture(key), renderer.getPicture(key));
            Assertions.assertNull(renderer.findPicture(missing));
            Assertions.assertThrows(ResourceNotFoundException.class, () -> renderer.getPicture(missing));
            Assertions.assertThrows(IllegalArgumentException.class,
                                    () -> renderer.draw(new ImageCanvas(1, 1), renderer.getPicture(key), 0, 0, 1, 1, PictureScaling.FIT,
                                                        PictureRenderer.NO_TEAM_COLOR, PictureRenderer.OPAQUE_WHITE),
                                    "another canvas");
        }
    }

    @Test
    void find_drawn_multiple_should_pick_the_detail_picture_of_the_pixels_per_picture_pixel(@TempDir @NotNull Path folder) throws IOException {
        ImageCanvas canvas = new ImageCanvas(CANVAS_SIZE * DETAIL_MULTIPLE, CANVAS_SIZE * DETAIL_MULTIPLE);

        writePicture(folder, "icon.png", 1, RED);
        writePicture(folder, "icon-2x.png", DETAIL_MULTIPLE, GREEN);
        writePicture(folder, "wrong-2x.png", WRONG_DETAIL_SIZE, BLUE);
        Files.writeString(folder.resolve("pack.json"), """
                                                       {"format": 1, "pictures": {"icon": "icon.png", "icon-2x": "icon-2x.png", "wrong": "icon.png", "wrong-2x": "wrong-2x.png"}}
                                                       """, StandardCharsets.UTF_8);

        try (StandardPack pack = StandardPack.open(folder);
             PictureRenderer renderer = new PictureRenderer(canvas, pack)) {
            Picture icon = renderer.getPicture(ResourceKey.of("icon"));

            canvas.begin(CANVAS_SIZE, CANVAS_SIZE);
            Assertions.assertEquals(1, renderer.findDrawnMultiple(icon, 1), "one pixel per picture pixel draws the picture itself");
            Assertions.assertEquals(DETAIL_MULTIPLE, renderer.findDrawnMultiple(icon, DETAIL_MULTIPLE), "a picture drawn twice as large");
            canvas.end();

            canvas.begin(UiSurface.of(CANVAS_SIZE * DETAIL_MULTIPLE, CANVAS_SIZE * DETAIL_MULTIPLE, DisplayScale.of(DETAIL_MULTIPLE, 1)));
            Assertions.assertEquals(DETAIL_MULTIPLE, renderer.findDrawnMultiple(icon, 1));
            Assertions.assertEquals(1, renderer.findDrawnMultiple(icon, HALF_SIZE), "at half size the picture itself has the pixels");
            canvas.end();

            Assertions.assertThrows(IllegalArgumentException.class, () -> renderer.getPicture(ResourceKey.of("wrong")),
                                    "a detail picture of another size");
        }
    }
}
