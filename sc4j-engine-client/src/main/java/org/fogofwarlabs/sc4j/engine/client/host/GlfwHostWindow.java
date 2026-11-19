package org.fogofwarlabs.sc4j.engine.client.host;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.cursor.CursorSurface;
import org.fogofwarlabs.sc4j.engine.client.input.DoublePressDetector;
import org.fogofwarlabs.sc4j.engine.client.input.InputListener;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Holds one GLFW window of a {@link GlfwClientHost} with its OpenGL context: the engine default {@link HostWindow}. It holds its size, display
 * mode, swap interval, close request and buffer swap, creates the window's input ({@link GlfwInput}) and pointer ({@link GlfwCursorSurface}),
 * and serves the window services (focus, key state, pointer, clipboard, dropped files, gamma ramp) through GLFW. A window is created hidden
 * ({@link ClientHost#createWindow}); {@link #show} shows it.
 * <p>
 * The display mode keeps the windowed bounds while the window is fullscreen or borderless, so returning to windowed mode
 * restores them. The swap interval is only passed on when it changes. A buffer swap waits until the previous frame's commands completed, so at most
 * one presented frame is queued ahead of the display.
 */
@ApiStatus.Internal
public final class GlfwHostWindow implements HostWindow {
    /**
     * The extensions that allow adaptive vertical sync (late swaps tear instead of waiting).
     */
    private static final @NotNull String WGL_SWAP_CONTROL_TEAR = "WGL_EXT_swap_control_tear";
    private static final @NotNull String GLX_SWAP_CONTROL_TEAR = "GLX_EXT_swap_control_tear";
    /**
     * The divisor that centers a length in a larger one.
     */
    private static final int CENTER_DIVISOR = 2;
    /**
     * The index of the blue channel in a gamma ramp (red {@code 0}, green {@code 1}).
     */
    private static final int BLUE_CHANNEL_INDEX = 2;
    private static final @NotNull GlfwKeyCodes KEY_CODES = GlfwKeyCodes.getInstance();
    private static final @NotNull Logger LOGGER = Logger.of(GlfwHostWindow.class);
    private static final @NotNull String OPERATING_SYSTEM_PROPERTY = "os.name";
    private static final @NotNull String WINDOWS_PREFIX = "windows";
    /**
     * The longest wait for the previous frame's commands at a buffer swap.
     */
    private static final long FRAME_FENCE_TIMEOUT_NANOSECONDS = 1_000_000_000L;

    /**
     * Reads the gamma ramp of a monitor.
     *
     * @param monitor the monitor
     * @return the red, green and blue values, or {@code null} if the monitor's ramp is not {@link #GAMMA_RAMP_SIZE} values per channel
     */
    private static short @Nullable [] readGammaRamp(long monitor) {
        GLFWGammaRamp original = GLFW.glfwGetGammaRamp(monitor);

        if (original == null || original.size() != GAMMA_RAMP_SIZE) {
            return null;
        }

        short[] ramp = new short[GAMMA_RAMP_SIZE * GAMMA_RAMP_CHANNEL_COUNT];

        original.red().get(0, ramp, 0, GAMMA_RAMP_SIZE);
        original.green().get(0, ramp, GAMMA_RAMP_SIZE, GAMMA_RAMP_SIZE);
        original.blue().get(0, ramp, GAMMA_RAMP_SIZE * BLUE_CHANNEL_INDEX, GAMMA_RAMP_SIZE);

        return ramp;
    }

    /**
     * Writes the gamma ramp of a monitor.
     *
     * @param monitor the monitor
     * @param ramp    the red, green and blue values
     */
    private static void writeGammaRamp(long monitor, short @NotNull [] ramp) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ShortBuffer red = stack.mallocShort(GAMMA_RAMP_SIZE).put(0, ramp, 0, GAMMA_RAMP_SIZE);
            ShortBuffer green = stack.mallocShort(GAMMA_RAMP_SIZE).put(0, ramp, GAMMA_RAMP_SIZE, GAMMA_RAMP_SIZE);
            ShortBuffer blue = stack.mallocShort(GAMMA_RAMP_SIZE).put(0, ramp, GAMMA_RAMP_SIZE * BLUE_CHANNEL_INDEX, GAMMA_RAMP_SIZE);

            GLFW.glfwSetGammaRamp(monitor, GLFWGammaRamp.malloc(stack).red(red).green(green).blue(blue).size(GAMMA_RAMP_SIZE));
        }
    }

    private final long handle;
    private final @NotNull WindowSettings settings;

    private @Nullable DisplayMode displayMode;
    private int windowedLeft;
    private int windowedTop;
    private int windowedWidth;
    private int windowedHeight;
    private @Nullable Integer swapInterval;
    /**
     * The fence after the previous buffer swap, or {@link MemoryUtil#NULL}.
     */
    private long frameFence;

    private long gammaMonitor;
    private short @Nullable [] appliedGammaRamp;
    private short @Nullable [] originalGammaRamp;

    private @Nullable FileDropListener dropListener;
    private boolean dropCallbackInstalled;

    private @Nullable TextInputMethod textInputMethod;

    private @NotNull PointerMode pointerMode;
    private @Nullable PointerClip pointerClip;
    /**
     * The applied clip in window coordinates ({@link PointerClip#toWindowBounds}), or {@code null} while no clip applies.
     */
    private int @Nullable [] pointerClipBounds;
    private @Nullable WindowsCursorClip cursorClip;
    private boolean cursorClipBound;
    private boolean cursorClipApplied;

    private boolean closed;

    /**
     * Creates a {@link GlfwHostWindow} instance over a created window.
     *
     * @param handle   the window handle
     * @param settings the settings the window was created with
     */
    GlfwHostWindow(long handle, @NotNull WindowSettings settings) {
        this.handle   = handle;
        this.settings = settings;

        this.displayMode    = null;
        this.windowedLeft   = 0;
        this.windowedTop    = 0;
        this.windowedWidth  = 0;
        this.windowedHeight = 0;
        this.swapInterval   = null;
        this.frameFence     = MemoryUtil.NULL;

        this.gammaMonitor      = MemoryUtil.NULL;
        this.appliedGammaRamp  = null;
        this.originalGammaRamp = null;

        this.dropListener          = null;
        this.dropCallbackInstalled = false;

        this.textInputMethod = null;

        this.pointerMode       = PointerMode.NORMAL;
        this.pointerClip       = null;
        this.pointerClipBounds = null;
        this.cursorClip        = null;
        this.cursorClipBound   = false;
        this.cursorClipApplied = false;

        this.closed = false;
    }

    /**
     * Returns the GLFW handle of the window, for the engine's GLFW classes that install window callbacks or read the window directly.
     *
     * @return the window handle
     */
    public long getHandle() {
        return this.handle;
    }

    /**
     * @return the settings the window was created with
     */
    @Override
    public @NotNull WindowSettings getSettings() {
        return this.settings;
    }

    /**
     * @return the display mode last applied with {@link #setDisplayMode}, or {@code null} before the first
     */
    @Override
    public @Nullable DisplayMode getDisplayMode() {
        return this.displayMode;
    }

    /**
     * Applies a display mode when it differs from the applied one. Leaving windowed mode remembers the window bounds;
     * returning to it restores them, or the given size centered on the primary monitor when none were remembered. Without a
     * primary monitor video mode a fullscreen or borderless request is recorded but changes nothing.
     *
     * @param mode          the display mode
     * @param defaultWidth  the windowed width when no bounds were remembered, in the units of the window's size
     * @param defaultHeight the windowed height when no bounds were remembered, in the units of the window's size
     */
    @Override
    public void setDisplayMode(@NotNull DisplayMode mode, int defaultWidth, int defaultHeight) {
        if (mode == this.displayMode) {
            return;
        }

        this.changeDisplayMode(mode, defaultWidth, defaultHeight);
        // The content area moved or changed size: the pointer clip follows it.
        this.applyPointerClip();
    }

    private void changeDisplayMode(@NotNull DisplayMode mode, int defaultWidth, int defaultHeight) {
        boolean wasWindowed = this.displayMode == null || this.displayMode == DisplayMode.WINDOWED;

        this.displayMode = mode;

        if (mode == DisplayMode.WINDOWED) {
            if (!wasWindowed) {
                // The default size of a window scaled to its monitor is in the monitor's scaled units.
                float scale = this.settings.isScaledToMonitor() ? this.getContentScale() : 1;
                int width = this.windowedWidth > 0 ? this.windowedWidth : Math.round(defaultWidth * scale);
                int height = this.windowedHeight > 0 ? this.windowedHeight : Math.round(defaultHeight * scale);

                GLFW.glfwSetWindowAttrib(this.handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                GLFW.glfwSetWindowMonitor(this.handle, MemoryUtil.NULL, this.windowedLeft, this.windowedTop, width, height, GLFW.GLFW_DONT_CARE);

                if (this.windowedWidth <= 0) {
                    this.center();
                }
            }

            return;
        }

        if (wasWindowed) {
            int[] x = new int[1];
            int[] y = new int[1];
            int[] width = new int[1];
            int[] height = new int[1];

            GLFW.glfwGetWindowPos(this.handle, x, y);
            GLFW.glfwGetWindowSize(this.handle, width, height);

            this.windowedLeft   = x[0];
            this.windowedTop    = y[0];
            this.windowedWidth  = width[0];
            this.windowedHeight = height[0];
        }

        long primary = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode video = primary == MemoryUtil.NULL ? null : GLFW.glfwGetVideoMode(primary);

        if (video == null) {
            return;
        }

        if (mode == DisplayMode.FULLSCREEN) {
            GLFW.glfwSetWindowMonitor(this.handle, primary, 0, 0, video.width(), video.height(), video.refreshRate());

            return;
        }

        int[] monitorX = new int[1];
        int[] monitorY = new int[1];

        GLFW.glfwGetMonitorPos(primary, monitorX, monitorY);
        GLFW.glfwSetWindowAttrib(this.handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwSetWindowMonitor(this.handle, MemoryUtil.NULL, monitorX[0], monitorY[0], video.width(), video.height(), GLFW.GLFW_DONT_CARE);
    }

    /**
     * Sets the swap interval of the window's context when it differs from the applied one. The context must be current. An
     * adaptive request falls back to {@link #SWAP_INTERVAL_ON} when the driver offers no tear control extension.
     *
     * @param interval {@link #SWAP_INTERVAL_OFF}, {@link #SWAP_INTERVAL_ON}, {@link #SWAP_INTERVAL_ADAPTIVE} or a larger interval
     */
    @Override
    public void setSwapInterval(int interval) {
        int applied = interval;

        if (applied < 0 && !GLFW.glfwExtensionSupported(WGL_SWAP_CONTROL_TEAR) && !GLFW.glfwExtensionSupported(GLX_SWAP_CONTROL_TEAR)) {
            applied = SWAP_INTERVAL_ON;
        }

        if (this.swapInterval != null && this.swapInterval == applied) {
            return;
        }

        this.swapInterval = applied;
        GLFW.glfwSwapInterval(applied);
    }

    /**
     * @return the width of the window's content area in window coordinates
     */
    @Override
    public int getWidth() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);

            GLFW.glfwGetWindowSize(this.handle, width, null);

            return width.get(0);
        }
    }

    /**
     * @return the height of the window's content area in window coordinates
     */
    @Override
    public int getHeight() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer height = stack.mallocInt(1);

            GLFW.glfwGetWindowSize(this.handle, null, height);

            return height.get(0);
        }
    }

    /**
     * @return the width of the window's framebuffer in pixels
     */
    @Override
    public int getFramebufferWidth() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);

            GLFW.glfwGetFramebufferSize(this.handle, width, null);

            return width.get(0);
        }
    }

    /**
     * @return the height of the window's framebuffer in pixels
     */
    @Override
    public int getFramebufferHeight() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer height = stack.mallocInt(1);

            GLFW.glfwGetFramebufferSize(this.handle, null, height);

            return height.get(0);
        }
    }

    /**
     * Returns the operating system's scale of the monitor the window is on, for example {@code 1.5} at a display scale of 150%; it changes
     * when the window moves to a monitor of another scale.
     *
     * @return the content scale across, {@code 1} when the platform reports none
     */
    @Override
    public float getContentScale() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer scale = stack.mallocFloat(1);

            GLFW.glfwGetWindowContentScale(this.handle, scale, null);

            float value = scale.get(0);

            return value > 0 && Float.isFinite(value) ? value : 1;
        }
    }

    /**
     * @return {@code true} if the window was asked to close (by the user or {@link #requestClose}), {@code false} otherwise
     */
    @Override
    public boolean isCloseRequested() {
        return GLFW.glfwWindowShouldClose(this.handle);
    }

    /**
     * @return {@code true} if the window has the input focus, {@code false} otherwise
     */
    @Override
    public boolean isFocused() {
        return GLFW.glfwGetWindowAttrib(this.handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
    }

    /**
     * @return {@code true} if the window is minimized, {@code false} otherwise
     */
    @Override
    public boolean isMinimized() {
        return GLFW.glfwGetWindowAttrib(this.handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
    }

    /**
     * @return {@code true} if the pointer is over the window's content area, {@code false} otherwise
     */
    @Override
    public boolean isPointerInside() {
        return GLFW.glfwGetWindowAttrib(this.handle, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE;
    }

    /**
     * Returns whether a key is down in the window, as the window's last key events left it.
     *
     * @param key the key
     * @return {@code true} if the key is down, {@code false} otherwise; {@link Key#UNKNOWN} is never down
     */
    @Override
    public boolean isKeyDown(@NotNull Key key) {
        return key != Key.UNKNOWN && GLFW.glfwGetKey(this.handle, KEY_CODES.getCode(key)) == GLFW.GLFW_PRESS;
    }

    /**
     * @return the pointer column in window coordinates, from the left edge of the content area
     */
    @Override
    public double getPointerX() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer x = stack.mallocDouble(1);

            GLFW.glfwGetCursorPos(this.handle, x, null);

            return x.get(0);
        }
    }

    /**
     * @return the pointer row in window coordinates, from the top edge of the content area
     */
    @Override
    public double getPointerY() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer y = stack.mallocDouble(1);

            GLFW.glfwGetCursorPos(this.handle, null, y);

            return y.get(0);
        }
    }

    /**
     * @return the text of the system clipboard, or {@code null} if it holds no text
     */
    @Override
    public @Nullable String getClipboardText() {
        return GLFW.glfwGetClipboardString(this.handle);
    }

    /**
     * Sets the text of the system clipboard.
     *
     * @param text the text
     */
    @Override
    public void setClipboardText(@NotNull String text) {
        GLFW.glfwSetClipboardString(this.handle, text);
    }

    /**
     * Sets how the platform pointer behaves over the window.
     *
     * @param mode the pointer mode
     */
    @Override
    public void setPointerMode(@NotNull PointerMode mode) {
        this.pointerMode = mode;

        GLFW.glfwSetInputMode(this.handle, GLFW.GLFW_CURSOR, switch (mode) {
            case NORMAL -> GLFW.GLFW_CURSOR_NORMAL;
            case HIDDEN -> GLFW.GLFW_CURSOR_HIDDEN;
            case CAPTURED -> GLFW.GLFW_CURSOR_CAPTURED;
        });
        // GLFW captures the whole content area (and releases it on focus loss, during a move or resize and for another mode); a clip narrows it.
        this.applyPointerClip();
    }

    /**
     * Narrows the captured area to a rectangle: while the pointer mode is {@link PointerMode#CAPTURED}, the window is focused and not minimized,
     * the pointer stays inside the rectangle instead of the whole content area. On Windows the system cursor is clipped ({@code ClipCursor});
     * elsewhere a pointer reported outside is moved to the nearest point inside. The clip follows the content area when the display mode changes;
     * after a move, a resize or a focus change, GLFW captures the whole content area again and the clip is applied again as soon as the pointer
     * passes its edge.
     *
     * @param clip the rectangle in framebuffer pixels, or {@code null} for the whole content area
     */
    @Override
    public void setPointerClip(@Nullable PointerClip clip) {
        this.pointerClip = clip;

        if (clip != null && !this.cursorClipBound) {
            this.cursorClip      = WindowsCursorClip.create();
            this.cursorClipBound = true;
        }

        this.applyPointerClip();
    }

    /**
     * Keeps a pointer position the window reported inside the applied clip: a position outside applies the clip again (the platform's own
     * capture replaced it) and moves the pointer to the nearest point inside, which the window reports next.
     *
     * @param x the reported column in window coordinates
     * @param y the reported row in window coordinates
     * @return {@code true} if the position was outside and the pointer moved, {@code false} if it stays as reported
     */
    boolean confinePointer(double x, double y) {
        int[] bounds = this.pointerClipBounds;

        if (bounds == null || !PointerClip.isApplied(this.pointerClip, this.pointerMode, this.isFocused(), this.isMinimized(), this.closed)) {
            return false;
        }

        double insideX = Math.clamp(x, bounds[PointerClip.LEFT_INDEX], bounds[PointerClip.RIGHT_INDEX] - 1);
        double insideY = Math.clamp(y, bounds[PointerClip.TOP_INDEX], bounds[PointerClip.BOTTOM_INDEX] - 1);

        if (insideX == x && insideY == y) {
            return false;
        }

        this.applyPointerClip();
        GLFW.glfwSetCursorPos(this.handle, insideX, insideY);

        return true;
    }

    /**
     * Applies the pointer clip when it holds the pointer ({@link PointerClip#isApplied}): the window bounds for {@link #confinePointer} and, on
     * Windows, the system cursor clip in screen coordinates. A clip removed from a captured, focused window gives the system cursor the whole
     * content area back, as GLFW's capture keeps it.
     */
    private void applyPointerClip() {
        PointerClip clip = this.pointerClip;
        boolean focused = this.isFocused();
        boolean minimized = this.isMinimized();
        int[] bounds = clip != null && PointerClip.isApplied(clip, this.pointerMode, focused, minimized, this.closed)
                       ? clip.toWindowBounds(this.getWidth(), this.getHeight(), this.getFramebufferWidth(), this.getFramebufferHeight())
                       : null;

        this.pointerClipBounds = bounds;

        WindowsCursorClip system = this.cursorClip;

        if (system == null || bounds == null && !this.cursorClipApplied) {
            return;
        }

        int[] left = new int[1];
        int[] top = new int[1];

        // The content area's position on the virtual screen.
        GLFW.glfwGetWindowPos(this.handle, left, top);

        if (bounds != null) {
            this.cursorClipApplied = system.clip(left[0] + bounds[PointerClip.LEFT_INDEX], top[0] + bounds[PointerClip.TOP_INDEX],
                                                 left[0] + bounds[PointerClip.RIGHT_INDEX], top[0] + bounds[PointerClip.BOTTOM_INDEX]);

            return;
        }

        if (this.pointerMode == PointerMode.CAPTURED && focused && !minimized && !this.closed) {
            system.clip(left[0], top[0], left[0] + this.getWidth(), top[0] + this.getHeight());
        }

        this.cursorClipApplied = false;
    }

    /**
     * Moves the pointer to a point of the content area. Nothing moves while the window does not have the input focus.
     *
     * @param x the column in window coordinates
     * @param y the row in window coordinates
     */
    @Override
    public void setPointerPosition(double x, double y) {
        GLFW.glfwSetCursorPos(this.handle, x, y);
    }

    /**
     * Sets the receiver of the files dropped onto the window.
     *
     * @param listener the receiver, or {@code null} to ignore drops
     */
    @Override
    public void setFileDropListener(@Nullable FileDropListener listener) {
        this.dropListener = listener;

        if (listener != null && !this.dropCallbackInstalled) {
            GLFW.glfwSetDropCallback(this.handle, (targetWindow, count, names) -> this.onFilesDropped(count, names));
            this.dropCallbackInstalled = true;
        }
    }

    private void onFilesDropped(int count, long names) {
        FileDropListener listener = this.dropListener;

        if (listener == null || count <= 0) {
            return;
        }

        List<Path> files = new ArrayList<>(count);

        for (int index = 0; index < count; index++) {
            files.add(Path.of(GLFWDropCallback.getName(names, index)));
        }

        listener.onFilesDropped(files);
    }

    /**
     * Applies a gamma ramp to the monitor of the fullscreen window while the window is focused, and gives the monitor its original ramp back
     * otherwise: when the window is windowed or not focused, has moved to another monitor, or the ramp is {@code null}. A client calls it once
     * per frame; a monitor is written only when the target monitor or the ramp changes. Closing the window gives the monitor its original ramp
     * back.
     *
     * @param ramp the {@link #GAMMA_RAMP_SIZE} red, then green, then blue values (unsigned 16-bit), or {@code null} for the original ramp
     * @throws IllegalArgumentException if the ramp does not hold {@link #GAMMA_RAMP_CHANNEL_COUNT} times {@link #GAMMA_RAMP_SIZE} values
     */
    @Override
    public void applyGammaRamp(short @Nullable [] ramp) {
        if (ramp != null && ramp.length != GAMMA_RAMP_SIZE * GAMMA_RAMP_CHANNEL_COUNT) {
            throw Exceptions.newIllegalArgumentException("A gamma ramp holds {0} values, not {1}.", GAMMA_RAMP_SIZE * GAMMA_RAMP_CHANNEL_COUNT,
                                                         ramp.length);
        }

        long monitor = GLFW.glfwGetWindowMonitor(this.handle);
        long target = ramp != null && monitor != MemoryUtil.NULL && this.isFocused() ? monitor : MemoryUtil.NULL;

        if (target == this.gammaMonitor && (target == MemoryUtil.NULL || Arrays.equals(ramp, this.appliedGammaRamp))) {
            return;
        }

        if (this.gammaMonitor != MemoryUtil.NULL && target != this.gammaMonitor && this.originalGammaRamp != null) {
            writeGammaRamp(this.gammaMonitor, this.originalGammaRamp);
        }

        if (target != MemoryUtil.NULL) {
            if (target != this.gammaMonitor) {
                this.originalGammaRamp = readGammaRamp(target);
            }

            writeGammaRamp(target, ramp);
        }

        this.gammaMonitor     = target;
        this.appliedGammaRamp = ramp == null ? null : ramp.clone();
    }


    /**
     * Asks the window to close; the host's frame loop ends before its next frame.
     */
    @Override
    public void requestClose() {
        GLFW.glfwSetWindowShouldClose(this.handle, true);
    }

    /**
     * Makes the window's OpenGL context current on the calling thread.
     */
    @Override
    public void makeContextCurrent() {
        GLFW.glfwMakeContextCurrent(this.handle);
    }

    /**
     * Makes no context current on the calling thread when the window's context is current there, for a thread that is done with the
     * context (for example a loader thread of a shared context window).
     */
    @Override
    public void releaseContext() {
        if (GLFW.glfwGetCurrentContext() == this.handle) {
            GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
        }
    }

    /**
     * Centers the window, frame included, in the primary monitor's work area (the desktop without the task bar); a window larger
     * than the work area is first made as large as the work area allows. Without a primary monitor nothing changes.
     */
    @Override
    public void center() {
        long monitor = GLFW.glfwGetPrimaryMonitor();

        if (monitor == MemoryUtil.NULL) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer areaX = stack.mallocInt(1);
            IntBuffer areaY = stack.mallocInt(1);
            IntBuffer areaWidth = stack.mallocInt(1);
            IntBuffer areaHeight = stack.mallocInt(1);

            GLFW.glfwGetMonitorWorkarea(monitor, areaX, areaY, areaWidth, areaHeight);

            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);

            GLFW.glfwGetWindowSize(this.handle, width, height);

            IntBuffer frameLeft = stack.mallocInt(1);
            IntBuffer frameTop = stack.mallocInt(1);
            IntBuffer frameRight = stack.mallocInt(1);
            IntBuffer frameBottom = stack.mallocInt(1);

            GLFW.glfwGetWindowFrameSize(this.handle, frameLeft, frameTop, frameRight, frameBottom);

            int frameWidth = frameLeft.get(0) + frameRight.get(0);
            int frameHeight = frameTop.get(0) + frameBottom.get(0);
            int fittedWidth = Math.max(1, Math.min(width.get(0), areaWidth.get(0) - frameWidth));
            int fittedHeight = Math.max(1, Math.min(height.get(0), areaHeight.get(0) - frameHeight));

            // A window scaled to a monitor of a high display scale can be larger than the monitor.
            if (fittedWidth != width.get(0) || fittedHeight != height.get(0)) {
                GLFW.glfwSetWindowSize(this.handle, fittedWidth, fittedHeight);
            }

            int outerWidth = fittedWidth + frameWidth;
            int outerHeight = fittedHeight + frameHeight;
            int left = areaX.get(0) + Math.max(0, (areaWidth.get(0) - outerWidth) / CENTER_DIVISOR);
            int top = areaY.get(0) + Math.max(0, (areaHeight.get(0) - outerHeight) / CENTER_DIVISOR);

            GLFW.glfwSetWindowPos(this.handle, left + frameLeft.get(0), top + frameTop.get(0));
        }
    }

    /**
     * Shows the window. An offscreen client never shows its window.
     */
    @Override
    public void show() {
        GLFW.glfwShowWindow(this.handle);
    }

    /**
     * Presents the drawn frame: swaps the window's buffers. While the window's context is current, it then waits until the commands before the
     * previous swap completed, so that the next frame starts at most one frame ahead of the display.
     */
    @Override
    public void swapBuffers() {
        GLFW.glfwSwapBuffers(this.handle);

        if (GLFW.glfwGetCurrentContext() != this.handle) {
            return;
        }

        long previous = this.frameFence;

        this.frameFence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

        if (previous != MemoryUtil.NULL) {
            GL32C.glClientWaitSync(previous, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, FRAME_FENCE_TIMEOUT_NANOSECONDS);
            GL32C.glDeleteSync(previous);
        }
    }

    /**
     * Creates a {@link WindowInput} instance with a {@link DoublePressDetector} of the default interval and the system's monotonic clock,
     * reads the window's surface size and installs the window's input callbacks.
     *
     * @param listener the receiver of the events
     * @return the created {@link WindowInput} instance; its callbacks stay installed until the window closes
     */
    @Override
    public @NotNull WindowInput createInput(@NotNull InputListener listener) {
        return new GlfwInput(this, listener);
    }

    /**
     * Creates a {@link WindowInput} instance and installs the window's input callbacks; the surface size starts empty (a window width and
     * height of {@code 0} convert as {@code 1}) until it is set.
     *
     * @param listener      the receiver of the events
     * @param doublePresses the detector that pairs button presses
     * @param clock         the clock of the button presses, in milliseconds
     * @return the created {@link WindowInput} instance; its callbacks stay installed until the window closes
     */
    @Override
    public @NotNull WindowInput createInput(@NotNull InputListener listener, @NotNull DoublePressDetector doublePresses, @NotNull LongSupplier clock) {
        return new GlfwInput(this, listener, doublePresses, clock);
    }

    /**
     * Returns the window's input method, created on the first call: the platform's on Windows, a {@link NoTextInputMethod} elsewhere or when
     * the platform's cannot be bound. It is used on the thread that owns the window.
     *
     * @return the input method; one instance per window
     */
    @Override
    public @NotNull TextInputMethod getTextInputMethod() {
        TextInputMethod method = this.textInputMethod;

        if (method != null) {
            return method;
        }

        method = new NoTextInputMethod();

        if (System.getProperty(OPERATING_SYSTEM_PROPERTY, "").toLowerCase(Locale.ROOT).startsWith(WINDOWS_PREFIX)) {
            try {
                method = new WindowsTextInputMethod(this);
            } catch (Throwable exception) {
                LOGGER.warning("INPUT METHOD UNAVAILABLE: " + exception);
            }
        }

        this.textInputMethod = method;

        return method;
    }

    /**
     * Creates a {@link CursorSurface} instance that shows pictures as the window's pointer; the window keeps the platform's own arrow until a
     * picture is shown. It is used on the thread that owns the window.
     *
     * @return the created {@link CursorSurface} instance, closed by the caller
     */
    @Override
    public @NotNull CursorSurface createCursorSurface() {
        return new GlfwCursorSurface(this);
    }


    /**
     * Disposes resources: gives a monitor whose gamma ramp the window set its original ramp back, frees the window's callbacks and destroys
     * the window. Closing again does nothing.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        WindowsCursorClip system = this.cursorClip;

        // A window that lost the focus had its clip released already; another program may hold the cursor clip since.
        if (system != null && this.cursorClipApplied && this.isFocused()) {
            system.release();
            this.cursorClipApplied = false;
        }

        if (this.gammaMonitor != MemoryUtil.NULL && this.originalGammaRamp != null) {
            writeGammaRamp(this.gammaMonitor, this.originalGammaRamp);
        }

        if (this.frameFence != MemoryUtil.NULL && GLFW.glfwGetCurrentContext() == this.handle) {
            GL32C.glDeleteSync(this.frameFence);
        }

        this.frameFence = MemoryUtil.NULL;

        Callbacks.glfwFreeCallbacks(this.handle);
        GLFW.glfwDestroyWindow(this.handle);
    }
}
