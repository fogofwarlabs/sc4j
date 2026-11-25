package org.fogofwarlabs.sc4j.engine.client.host;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a client on GLFW: the engine default {@link ClientHost}. It initializes the library, creates the windows and their OpenGL contexts,
 * runs the frame loop of a {@link HostApplication} with frame pacing ({@link FramePacer}) and releases everything in reverse order. One host
 * exists per process; it is used from the thread that created it.
 * <p>
 * The frame loop runs until the main window is asked to close or a frame returns {@link FrameResult#LAST}. Each frame:
 * {@link HostApplication#beginFrame}, event polling, {@link HostApplication#runFrame}, then for a presented frame the wait
 * under {@link HostApplication#getFrameRateLimit}.
 */
@ApiStatus.Internal
public final class GlfwClientHost implements ClientHost {
    /**
     * Creates a {@link GlfwClientHost} instance: installs an error callback that prints the library's errors to the standard
     * error stream and initializes the windowing library.
     *
     * @return the created {@link GlfwClientHost} instance
     * @throws IllegalStateException if the windowing library cannot be initialized
     */
    public static @NotNull GlfwClientHost initialize() {
        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);

        errorCallback.set();

        if (!GLFW.glfwInit()) {
            GLFW.glfwSetErrorCallback(null);
            errorCallback.free();

            throw Exceptions.newIllegalStateException("GLFW initialization failed.");
        }

        return new GlfwClientHost(errorCallback);
    }

    private static int toSizeLimit(int limit, float scale) {
        return limit == WindowSettings.NO_SIZE_LIMIT ? GLFW.GLFW_DONT_CARE : Math.round(limit * scale);
    }


    private final @NotNull GLFWErrorCallback errorCallback;
    private final @NotNull FramePacer pacer;

    private final @NotNull List<GlfwHostWindow> windows;

    private boolean closed;

    private GlfwClientHost(@NotNull GLFWErrorCallback errorCallback) {
        this.errorCallback = errorCallback;
        this.pacer         = new FramePacer();

        this.windows = new ArrayList<>();

        this.closed = false;
    }

    /**
     * @return the seconds since the windowing library was initialized
     */
    @Override
    public double getTime() {
        return GLFW.glfwGetTime();
    }


    /**
     * Creates a hidden window with an OpenGL core profile context of the settings' version, forward compatible on macOS. Its context is not
     * made current.
     *
     * @param settings the window settings
     * @return the created {@link HostWindow} instance, closed by the host
     * @throws IllegalStateException if the window cannot be created
     */
    @Override
    public @NotNull HostWindow createWindow(@NotNull WindowSettings settings) {
        long handle = this.createHandle(settings, settings.getWidth(), settings.getHeight(), MemoryUtil.NULL);

        if (handle == MemoryUtil.NULL) {
            throw Exceptions.newIllegalStateException("Window creation failed.");
        }

        GlfwHostWindow window = new GlfwHostWindow(handle, settings);

        this.windows.add(window);

        if (settings.getMinimumWidth() != WindowSettings.NO_SIZE_LIMIT || settings.getMinimumHeight() != WindowSettings.NO_SIZE_LIMIT) {
            // The platform scales the size of a window scaled to its monitor, but not its limits.
            float scale = settings.isScaledToMonitor() ? window.getContentScale() : 1;

            GLFW.glfwSetWindowSizeLimits(handle,
                                         toSizeLimit(settings.getMinimumWidth(), scale), toSizeLimit(settings.getMinimumHeight(), scale),
                                         GLFW.GLFW_DONT_CARE, GLFW.GLFW_DONT_CARE);
        }

        return window;
    }

    /**
     * Creates a hidden window of the given size whose context shares the objects (textures, buffers) of another window's
     * context, for a loader thread that uploads while the frame loop draws. It has the other window's settings otherwise.
     *
     * @param window the window of this host whose context objects are shared
     * @param width  the width in screen coordinates
     * @param height the height in screen coordinates
     * @return the created {@link HostWindow} instance, closed by the host, or {@code null} if it cannot be created
     * @throws IllegalArgumentException if the window is not a window of this host
     */
    @Override
    public @Nullable HostWindow createSharedContextWindow(@NotNull HostWindow window, int width, int height) {
        if (!(window instanceof GlfwHostWindow glfwWindow) || !this.windows.contains(glfwWindow)) {
            throw Exceptions.newIllegalArgumentException("The window is not a window of this host: {0}", window);
        }

        long handle = this.createHandle(window.getSettings(), width, height, glfwWindow.getHandle());

        if (handle == MemoryUtil.NULL) {
            return null;
        }

        GlfwHostWindow shared = new GlfwHostWindow(handle, window.getSettings());

        this.windows.add(shared);

        return shared;
    }

    private long createHandle(@NotNull WindowSettings settings, int width, int height, long sharedHandle) {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, settings.getContextMajorVersion());
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, settings.getContextMinorVersion());
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

        // macOS creates a core profile context only when it is forward compatible.
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_COCOA) {
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        }

        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, settings.isDecorated() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_SCALE_TO_MONITOR, settings.isScaledToMonitor() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);

        return GLFW.glfwCreateWindow(width, height, settings.getTitle(), MemoryUtil.NULL, sharedHandle);
    }

    /**
     * Creates the OpenGL function table of the context current on this thread; call it once after
     * {@link HostWindow#makeContextCurrent}.
     */
    @Override
    public void createCapabilities() {
        GL.createCapabilities();
    }

    /**
     * Polls the pending window events; their callbacks run on this thread.
     */
    @Override
    public void pollEvents() {
        GLFW.glfwPollEvents();
    }

    /**
     * Waits for a window event or the timeout, then handles the pending events, for example while a window is minimized.
     *
     * @param timeoutSeconds the longest wait in seconds
     */
    @Override
    public void waitEvents(double timeoutSeconds) {
        GLFW.glfwWaitEventsTimeout(timeoutSeconds);
    }

    /**
     * Runs the frame loop of an application on a window until the window is asked to close or a frame is the last.
     *
     * @param window      the main window
     * @param application the application
     * @throws IOException if a frame cannot read or write a file it needs
     */
    @Override
    public void run(@NotNull HostWindow window, @NotNull HostApplication application) throws IOException {
        while (!window.isCloseRequested()) {
            application.beginFrame();
            this.pollEvents();

            FrameResult result = application.runFrame();

            if (!result.isPresented()) {
                continue;
            }

            int limit = application.getFrameRateLimit();

            if (limit > 0) {
                this.pacer.waitForNextFrame(limit);
            }

            if (result == FrameResult.LAST) {
                break;
            }
        }
    }


    /**
     * Disposes resources: destroys the windows in reverse creation order, clears the thread's OpenGL function table,
     * terminates the windowing library and frees its error callback. A failing step is logged and the later steps still run.
     * Closing again does nothing.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        for (int index = this.windows.size() - 1; index >= 0; index--) {
            IgnoreException.execute(
                    this.windows.get(index)::close
            );
        }

        IgnoreException.execute(
                () -> GL.setCapabilities(null),
                GLFW::glfwTerminate,
                () -> GLFW.glfwSetErrorCallback(null),
                this.errorCallback::free
        );
    }
}
