package org.fogofwarlabs.sc4j.engine.client.render.gl;

import org.fogofwarlabs.sc4j.engine.client.HiddenWindowScenario;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the OpenGL canvas default on a hidden window: the target size and uploaded textures.
 */
final class GlCanvasTest {
    private static final int FIELD_PAGE_SIZE = 64;
    private static final int RED_ARGB = 0xFFFF0000;
    private static final int BLUE_ARGB = 0xFF0000FF;

    @Test
    void begin_should_take_the_target_size() {
        HiddenWindowScenario.run(() -> {
            try (GlCanvas canvas = new GlCanvas(FIELD_PAGE_SIZE)) {
                canvas.begin(HiddenWindowScenario.SIZE, HiddenWindowScenario.SIZE);
                canvas.end();

                Assertions.assertEquals(HiddenWindowScenario.SIZE, canvas.getWidth());
            }
        });
    }

    @Test
    void upload_texture_should_keep_the_size_and_close_once() {
        HiddenWindowScenario.run(() -> {
            try (GlCanvas canvas = new GlCanvas(FIELD_PAGE_SIZE)) {
                Texture texture = canvas.uploadTexture(2, 1, new int[]{RED_ARGB, BLUE_ARGB});

                Assertions.assertEquals(2, texture.getWidth());

                texture.close();
                texture.close();
            }
        });
    }
}
