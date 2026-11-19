package org.fogofwarlabs.sc4j.engine.client.host;

import org.fogofwarlabs.sc4j.engine.client.cursor.CursorSurface;
import org.fogofwarlabs.sc4j.engine.client.display.DetailSet;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the runtime host on a hidden window: window creation, the frame loop with its frame results, pacing and the close
 * request, the drawn frame read back, the window's input and cursor surface, and the window services.
 */
final class ClientHostTest {
    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the OpenGL check.";
    private static final @NotNull String TITLE = "engine host test";
    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int FRAME_LIMIT = 4;
    private static final int CLOSING_FRAME = 2;
    private static final int FRAMES_PER_SECOND = 500;
    private static final int RGBA_BYTES = 4;
    private static final float CLEAR_RED = 1;
    private static final float CLEAR_GREEN = 0.5f;
    private static final float CLEAR_BLUE = 0;
    private static final int RED_VALUE = 255;
    private static final int GREEN_VALUE = 128;
    private static final int GREEN_TOLERANCE = 1;

    /**
     * Counts the frames of a test application and reads back the last drawn frame.
     */
    private static final class CountingApplication implements HostApplication {
        private final @NotNull HostWindow window;
        private final boolean closeAfterSecondFrame;

        private int begunFrames;
        private int frames;
        private int centerRed;
        private int centerGreen;

        private CountingApplication(@NotNull HostWindow window, boolean closeAfterSecondFrame) {
            this.window                = window;
            this.closeAfterSecondFrame = closeAfterSecondFrame;

            this.begunFrames = 0;
            this.frames      = 0;
            this.centerRed   = 0;
            this.centerGreen = 0;
        }

        /**
         * Called at the start of every frame, before the window events are polled.
         */
        @Override
        public void beginFrame() {
            this.begunFrames++;
        }

        /**
         * Runs one frame: input, simulation, drawing and presenting.
         *
         * @return what the frame did
         */
        @Override
        public @NotNull FrameResult runFrame() {
            this.frames++;

            if (this.frames == 1) {
                return FrameResult.SKIPPED;
            }

            GL11C.glViewport(0, 0, this.window.getFramebufferWidth(), this.window.getFramebufferHeight());
            GL11C.glClearColor(CLEAR_RED, CLEAR_GREEN, CLEAR_BLUE, 1);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);

            ByteBuffer pixel = MemoryUtil.memAlloc(RGBA_BYTES);

            try {
                GL11C.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixel);
                this.centerRed   = Byte.toUnsignedInt(pixel.get(0));
                this.centerGreen = Byte.toUnsignedInt(pixel.get(1));
            } finally {
                MemoryUtil.memFree(pixel);
            }

            this.window.swapBuffers();

            if (this.closeAfterSecondFrame && this.frames == CLOSING_FRAME) {
                this.window.requestClose();
            }

            return this.frames >= FRAME_LIMIT ? FrameResult.LAST : FrameResult.PRESENTED;
        }

        /**
         * Returns the frame rate limit of the frame just run. It is read after {@link #runFrame} returned a presented frame.
         *
         * @return the frames per second the host paces to
         */
        @Override
        public int getFrameRateLimit() {
            return FRAMES_PER_SECOND;
        }
    }

    @Test
    void run_should_end_after_the_last_frame_and_present_the_drawn_frame() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (ClientHost host = ClientHost.initialize()) {
            HostWindow window = host.createWindow(WindowSettings.builder(TITLE, WIDTH, HEIGHT).setDecorated(false).build());

            window.makeContextCurrent();
            host.createCapabilities();
            window.setSwapInterval(HostWindow.SWAP_INTERVAL_OFF);

            CountingApplication application = new CountingApplication(window, false);

            host.run(window, application);

            Assertions.assertEquals(FRAME_LIMIT, application.frames);
            Assertions.assertEquals(FRAME_LIMIT, application.begunFrames);
            Assertions.assertEquals(RED_VALUE, application.centerRed);
            Assertions.assertEquals(GREEN_VALUE, application.centerGreen, GREEN_TOLERANCE);
            Assertions.assertNull(window.getDisplayMode());
        }
    }

    @Test
    void run_should_stop_before_the_next_frame_when_the_window_is_asked_to_close() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (ClientHost host = ClientHost.initialize()) {
            HostWindow window = host.createWindow(WindowSettings.builder(TITLE, WIDTH, HEIGHT).setDecorated(false).build());
            HostWindow shared = host.createSharedContextWindow(window, 1, 1);

            window.makeContextCurrent();
            host.createCapabilities();

            CountingApplication application = new CountingApplication(window, true);

            host.run(window, application);

            Assertions.assertEquals(CLOSING_FRAME, application.frames);
            Assertions.assertTrue(window.isCloseRequested());
            Assertions.assertNotNull(shared);
        }
    }

    @Test
    void swap_buffers_should_wait_for_the_previous_frame_without_errors() {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (ClientHost host = ClientHost.initialize()) {
            HostWindow window = host.createWindow(WindowSettings.builder(TITLE, WIDTH, HEIGHT).setDecorated(false).build());

            window.makeContextCurrent();
            host.createCapabilities();
            window.setSwapInterval(HostWindow.SWAP_INTERVAL_OFF);

            for (int frame = 0; frame < FRAME_LIMIT; frame++) {
                GL11C.glClearColor(CLEAR_RED, CLEAR_GREEN, CLEAR_BLUE, 1);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
                window.swapBuffers();
            }

            Assertions.assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        }
    }

    @Test
    void create_input_and_create_cursor_surface_should_serve_the_window() {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (ClientHost host = ClientHost.initialize()) {
            HostWindow window = host.createWindow(WindowSettings.builder(TITLE, WIDTH, HEIGHT).setDecorated(false).build());
            List<InputEvent> events = new ArrayList<>();
            WindowInput input = window.createInput(events::add);

            Assertions.assertEquals(window.getFramebufferWidth(), input.toFramebufferX(WIDTH));
            Assertions.assertEquals(window.getFramebufferHeight(), input.toFramebufferY(HEIGHT));
            Assertions.assertTrue(events.isEmpty());

            try (CursorSurface cursor = window.createCursorSurface()) {
                cursor.showDefault();
                cursor.show(DetailSet.of(new Picture(2, 2, PixelLayer.ofRgba(new int[]{0xFF000000, 0, 0, 0xFFFFFFFF}), null, 1, 1, 0, 0, 0, 0)));
                cursor.hide();
                cursor.showDefault();

                Assertions.assertTrue(cursor.isAvailable());
            }
        }
    }

    @Test
    void window_services_report_the_hidden_window_and_take_requests() {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (ClientHost host = ClientHost.initialize()) {
            HostWindow window = host.createWindow(WindowSettings.builder(TITLE, WIDTH, HEIGHT).setDecorated(false).build());
            HostWindow shared = host.createSharedContextWindow(window, 1, 1);

            Assertions.assertEquals(WIDTH, window.getWidth());
            Assertions.assertEquals(HEIGHT, window.getHeight());
            Assertions.assertFalse(window.isFocused(), "a hidden window has no focus");
            Assertions.assertFalse(window.isMinimized());
            Assertions.assertFalse(window.isPointerInside());
            Assertions.assertFalse(window.isKeyDown(Key.A));
            Assertions.assertFalse(window.isKeyDown(Key.UNKNOWN));

            window.setPointerMode(PointerMode.HIDDEN);
            window.setPointerMode(PointerMode.NORMAL);
            // A window without the focus holds no pointer clip: a position outside it stays as reported.
            window.setPointerMode(PointerMode.CAPTURED);
            window.setPointerClip(PointerClip.of(WIDTH / 4f, HEIGHT / 4f, WIDTH / 2f, HEIGHT / 2f));

            Assertions.assertFalse(((GlfwHostWindow) window).confinePointer(0, 0));

            window.setPointerClip(null);
            window.setPointerMode(PointerMode.NORMAL);
            window.setFileDropListener(files -> Assertions.fail("nothing is dropped"));
            window.setFileDropListener(null);

            short[] ramp = new short[HostWindow.GAMMA_RAMP_SIZE * HostWindow.GAMMA_RAMP_CHANNEL_COUNT];

            window.applyGammaRamp(ramp);
            window.applyGammaRamp(null);

            Assertions.assertThrows(IllegalArgumentException.class, () -> window.applyGammaRamp(new short[1]));
            Assertions.assertNotNull(shared);

            shared.makeContextCurrent();
            shared.releaseContext();
            window.releaseContext();
        }
    }
}
