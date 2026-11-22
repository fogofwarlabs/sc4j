package org.fogofwarlabs.sc4j.engine.client;

import org.fogofwarlabs.sc4j.engine.client.host.ClientHost;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.client.host.WindowSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.lwjgl.opengl.GL11C;

/**
 * Runs an OpenGL check of the engine client defaults on a hidden host window.
 */
public final class HiddenWindowScenario {
    /**
     * The window size of the checks.
     */
    public static final int SIZE = 32;

    private static final @NotNull String RENDER_SMOKE_PROPERTY = "sc4j.render.smoke";
    private static final @NotNull String RENDER_SMOKE_MESSAGE = "Pass -Psc4j.render.smoke=true to run the OpenGL check.";
    private static final @NotNull String TITLE = "engine render test";

    /**
     * Runs while the hidden window's context is current.
     */
    public interface Check {
        /**
         * Runs the check.
         */
        void run();
    }

    /**
     * Runs a check on a hidden {@link #SIZE} by {@link #SIZE} window cleared to black, then closes the host.
     *
     * @param check the check
     */
    public static void run(@NotNull Check check) {
        run(SIZE, SIZE, check);
    }

    /**
     * Runs a check on a hidden window of a size cleared to black, then closes the host.
     *
     * @param width  the window width
     * @param height the window height
     * @param check  the check
     */
    public static void run(int width, int height, @NotNull Check check) {
        Assumptions.assumeTrue(Boolean.getBoolean(RENDER_SMOKE_PROPERTY), RENDER_SMOKE_MESSAGE);

        try (ClientHost host = ClientHost.initialize()) {
            HostWindow window = host.createWindow(WindowSettings.builder(TITLE, width, height).setDecorated(false).build());

            window.makeContextCurrent();
            host.createCapabilities();
            GL11C.glViewport(0, 0, width, height);
            GL11C.glClearColor(0, 0, 0, 1);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);

            check.run();
        }
    }

    private HiddenWindowScenario() {
    }
}
