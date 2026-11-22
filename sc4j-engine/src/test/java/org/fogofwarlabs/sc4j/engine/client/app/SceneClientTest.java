package org.fogofwarlabs.sc4j.engine.client.app;

import org.fogofwarlabs.sc4j.engine.client.audio.AudioMixer;
import org.fogofwarlabs.sc4j.engine.client.audio.SilentAudioDevice;
import org.fogofwarlabs.sc4j.engine.client.cursor.HeadlessCursorSurface;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.host.FrameResult;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.manifest.ManifestException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the scene flow of an engine-only client without a window: the first scene from the mod's factory, input to the top scene, a requested
 * switch at the next frame, the last frame after a quit request and the scenes closed at the end, and the logical units of pointer input at a
 * display scale, lowered until the scene and the engine minimum fit.
 */
final class SceneClientTest {
    private static final int RED = 0xFF0000FF;
    private static final int BLUE = 0x0000FFFF;
    private static final long FRAME_TIME = 16;
    private static final int LOGICAL_WIDTH = 800;
    private static final int LOGICAL_HEIGHT = 480;
    private static final int SQUARE_LEFT = 100;
    private static final int SQUARE_SIZE = 50;
    private static final float POINTER_X = 120;
    private static final float POINTER_Y = 80;
    private static final float TOLERANCE = 0.01f;

    /**
     * Fills a square of fixed logical size and records the pointer positions it receives; it needs a logical area of a given size.
     */
    private static final class SquareScene implements ClientScene {
        private final int minimumWidth;
        private final int minimumHeight;
        private final @NotNull List<float @NotNull []> pointers;

        private SquareScene(int minimumWidth, int minimumHeight) {
            this.minimumWidth  = minimumWidth;
            this.minimumHeight = minimumHeight;
            this.pointers      = new ArrayList<>();
        }

        @Override
        public int getMinimumWidth() {
            return this.minimumWidth;
        }

        @Override
        public int getMinimumHeight() {
            return this.minimumHeight;
        }

        @Override
        public void open(@NotNull SceneClient context) {
        }

        @Override
        public void update(long time) {
        }

        @Override
        public void draw(@NotNull Canvas surface) {
            surface.fillRectangle(SQUARE_LEFT, SQUARE_LEFT, SQUARE_SIZE, SQUARE_SIZE, RED);
        }

        @Override
        public boolean handleInput(@NotNull InputEvent event) {
            this.pointers.add(new float[]{event.getX(), event.getY()});

            return true;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Records what the client does with a scene and fills the frame with its colour.
     */
    private static final class RecordingScene implements ClientScene {
        private final @NotNull String name;
        private final int color;
        private final @NotNull List<@NotNull String> events;

        private RecordingScene(@NotNull String name, int color, @NotNull List<@NotNull String> events) {
            this.name   = name;
            this.color  = color;
            this.events = events;
        }

        @Override
        public void open(@NotNull SceneClient context) {
            this.events.add(this.name + " open");
        }

        @Override
        public void update(long time) {
            this.events.add(this.name + " update " + time);
        }

        @Override
        public void draw(@NotNull Canvas surface) {
            surface.fillRectangle(0, 0, surface.getWidth(), surface.getHeight(), this.color);
        }

        @Override
        public boolean handleInput(@NotNull InputEvent event) {
            this.events.add(this.name + " input " + event.getKey());

            return true;
        }

        @Override
        public void close() {
            this.events.add(this.name + " close");
        }
    }

    @Test
    void scene_client_switches_and_ends_with_the_scenes_of_the_mod(@TempDir @NotNull Path storage) throws ManifestException,
                                                                                                          ModException,
                                                                                                          IOException {
        List<String> events = new ArrayList<>();
        RecordingScene menu = new RecordingScene("menu", RED, events);
        RecordingScene game = new RecordingScene("game", BLUE, events);
        ImageCanvas canvas = new ImageCanvas(ClientLaunchScenario.WIDTH, ClientLaunchScenario.HEIGHT);

        try (SceneClient client = ClientLaunchScenario.createClient(ClientLaunchScenario.createLaunch(List.of()),
                                                                    ClientLaunchScenario.createStorage(storage), canvas)) {
            client.start(started -> menu);

            Assertions.assertThrows(IllegalStateException.class, () -> client.start(started -> game));

            Assertions.assertEquals(FrameResult.PRESENTED, client.runFrame(FRAME_TIME, ClientLaunchScenario.WIDTH, ClientLaunchScenario.HEIGHT));
            Assertions.assertTrue(client.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.ENTER, 0)));

            client.getScenes().replace(game);

            Assertions.assertSame(menu, client.getScenes().getScene(), "a requested scene waits for the next frame");

            client.runFrame(FRAME_TIME * 2, ClientLaunchScenario.WIDTH, ClientLaunchScenario.HEIGHT);

            Assertions.assertSame(game, client.getScenes().getScene());
            Assertions.assertEquals(FRAME_TIME * 2, client.getTime());

            client.requestQuit();

            Assertions.assertEquals(FrameResult.LAST, client.runFrame(FRAME_TIME * 3, ClientLaunchScenario.WIDTH, ClientLaunchScenario.HEIGHT));
        }

        Assertions.assertEquals(List.of("menu open", "menu update 16", "menu input ENTER", "game open", "menu close", "game update 32",
                                        "game update 48", "game close"), events);
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0f, 1.5f, 2.0f})
    void scene_client_takes_the_pointer_in_logical_units_at_a_display_scale(float scale, @TempDir @NotNull Path storage) throws ManifestException,
                                                                                                                                ModException,
                                                                                                                                IOException {
        int framebufferWidth = Math.round(LOGICAL_WIDTH * scale);
        int framebufferHeight = Math.round(LOGICAL_HEIGHT * scale);
        SquareScene scene = new SquareScene(0, 0);
        ImageCanvas canvas = new ImageCanvas(framebufferWidth, framebufferHeight);
        HeadlessCursorSurface cursor = new HeadlessCursorSurface();

        try (SceneClient client = new SceneClient(ClientLaunchScenario.createLaunch(List.of()), ClientLaunchScenario.createStorage(storage), canvas,
                                                  new AudioMixer(new SilentAudioDevice(() -> 0)), cursor)) {
            client.setDisplayScale(DisplayScale.of(scale, 1));
            client.start(started -> scene);
            client.runFrame(FRAME_TIME, framebufferWidth, framebufferHeight);

            Assertions.assertEquals(LOGICAL_WIDTH, client.getWidth());
            Assertions.assertEquals(LOGICAL_HEIGHT, client.getHeight());
            Assertions.assertEquals(Math.round(scale), cursor.getScale());

            client.handleInput(InputEvent.createPointerMoved(POINTER_X * scale, POINTER_Y * scale));
            client.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, Key.ENTER, 0));
        }

        Assertions.assertEquals(POINTER_X, scene.pointers.getFirst()[0], TOLERANCE);
        Assertions.assertEquals(POINTER_Y, scene.pointers.getFirst()[1], TOLERANCE);
        Assertions.assertEquals(2, scene.pointers.size(), "a key event reaches the scene too");
    }

    @ParameterizedTest
    @CsvSource({"1280, 720, 1.0, 1232, 680", "1920, 1080, 2.0, 1232, 712", "1280, 720, 2.0, 1232, 712", "1280, 720, 3.0, 0, 0",
                "2560, 1440, 2.0, 1232, 712"})
    void scene_client_lowers_the_display_scale_until_the_scene_and_the_engine_minimum_fit(int framebufferWidth, int framebufferHeight,
                                                                                          float scale, int minimumWidth, int minimumHeight,
                                                                                          @TempDir @NotNull Path storage) throws ManifestException,
                                                                                                                                 ModException,
                                                                                                                                 IOException {
        SquareScene scene = new SquareScene(minimumWidth, minimumHeight);
        ImageCanvas canvas = new ImageCanvas(framebufferWidth, framebufferHeight);
        HeadlessCursorSurface cursor = new HeadlessCursorSurface();
        int neededWidth = Math.max(minimumWidth, UiSurface.MINIMUM_WIDTH);
        int neededHeight = Math.max(minimumHeight, UiSurface.MINIMUM_HEIGHT);
        int windowWidth = Math.round(framebufferWidth / scale);
        int windowHeight = Math.round(framebufferHeight / scale);

        try (SceneClient client = new SceneClient(ClientLaunchScenario.createLaunch(List.of()), ClientLaunchScenario.createStorage(storage), canvas,
                                                  new AudioMixer(new SilentAudioDevice(() -> 0)), cursor)) {
            client.setDisplayScale(DisplayScale.of(scale, 1));
            client.start(started -> scene);
            client.runFrame(FRAME_TIME, framebufferWidth, framebufferHeight);

            UiSurface surface = client.getSurface();

            Assertions.assertNotNull(surface);
            Assertions.assertTrue(client.getWidth() >= neededWidth && client.getHeight() >= neededHeight,
                                  "the logical area " + surface + " holds " + neededWidth + "x" + neededHeight);
            Assertions.assertTrue(client.getWidth() >= windowWidth && client.getHeight() >= windowHeight, "the scale is never raised");
            Assertions.assertTrue(client.getWidth() == Math.max(neededWidth, windowWidth) ||
                                  client.getHeight() == Math.max(neededHeight, windowHeight),
                                  "the scale is lowered no further than the scene needs: " + surface);
            Assertions.assertEquals(Math.round(scale), cursor.getScale(), "the pointer keeps the window's scale");

            client.handleInput(InputEvent.createPointerMoved(surface.toFramebufferX(POINTER_X), surface.toFramebufferY(POINTER_Y)));
        }

        Assertions.assertEquals(POINTER_X, scene.pointers.getFirst()[0], TOLERANCE);
        Assertions.assertEquals(POINTER_Y, scene.pointers.getFirst()[1], TOLERANCE);
    }
}
