package org.fogofwarlabs.sc4j.engine.client.host;

import org.fogofwarlabs.sc4j.engine.client.cursor.CursorSurface;
import org.fogofwarlabs.sc4j.engine.client.input.DoublePressDetector;
import org.fogofwarlabs.sc4j.engine.client.input.InputListener;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongSupplier;

/**
 * Holds one window of a {@link ClientHost} with its OpenGL context: its size, display mode, swap interval, close request and
 * buffer swap, the window's input ({@link #createInput}) and pointer ({@link #createCursorSurface}), and the window services a client
 * needs besides: focus and minimized state, key state, pointer position and mode, clipboard, dropped files and the gamma ramp of a
 * fullscreen window. A window is created hidden ({@link ClientHost#createWindow}); {@link #show} shows it.
 * <p>
 * The display mode keeps the windowed bounds while the window is fullscreen or borderless, so returning to windowed mode
 * restores them. The swap interval is only passed on when it changes.
 */
public interface HostWindow extends AutoCloseable {
    /**
     * The swap interval that presents without waiting for the display.
     */
    int SWAP_INTERVAL_OFF = 0;
    /**
     * The swap interval that waits for every vertical blank.
     */
    int SWAP_INTERVAL_ON = 1;
    /**
     * The swap interval of adaptive vertical sync: wait for the vertical blank unless the frame is late.
     */
    int SWAP_INTERVAL_ADAPTIVE = -1;
    /**
     * The number of values of each color channel of a gamma ramp ({@link #applyGammaRamp}).
     */
    int GAMMA_RAMP_SIZE = 256;
    /**
     * The number of color channels of a gamma ramp: red, green and blue.
     */
    int GAMMA_RAMP_CHANNEL_COUNT = 3;

    /**
     * @return the settings the window was created with
     */
    @NotNull WindowSettings getSettings();

    /**
     * @return the display mode last applied with {@link #setDisplayMode}, or {@code null} before the first
     */
    @Nullable DisplayMode getDisplayMode();

    /**
     * Applies a display mode when it differs from the applied one. Leaving windowed mode remembers the window bounds;
     * returning to it restores them, or the given size centered on the primary monitor when none were remembered. Without a
     * primary monitor video mode a fullscreen or borderless request is recorded but changes nothing.
     *
     * @param mode          the display mode
     * @param defaultWidth  the windowed width when no bounds were remembered, in the units of the window's size
     * @param defaultHeight the windowed height when no bounds were remembered, in the units of the window's size
     */
    void setDisplayMode(@NotNull DisplayMode mode, int defaultWidth, int defaultHeight);

    /**
     * Sets the swap interval of the window's context when it differs from the applied one. The context must be current. An
     * adaptive request falls back to {@link #SWAP_INTERVAL_ON} when the driver offers no tear control extension.
     *
     * @param interval {@link #SWAP_INTERVAL_OFF}, {@link #SWAP_INTERVAL_ON}, {@link #SWAP_INTERVAL_ADAPTIVE} or a larger interval
     */
    void setSwapInterval(int interval);

    /**
     * @return the width of the window's content area in window coordinates
     */
    int getWidth();

    /**
     * @return the height of the window's content area in window coordinates
     */
    int getHeight();

    /**
     * @return the width of the window's framebuffer in pixels
     */
    int getFramebufferWidth();

    /**
     * @return the height of the window's framebuffer in pixels
     */
    int getFramebufferHeight();

    /**
     * Returns the operating system's scale of the monitor the window is on, for example {@code 1.5} at a display scale of 150%; it changes
     * when the window moves to a monitor of another scale.
     *
     * @return the content scale across, {@code 1} when the platform reports none
     */
    float getContentScale();

    /**
     * @return {@code true} if the window was asked to close (by the user or {@link #requestClose}), {@code false} otherwise
     */
    boolean isCloseRequested();

    /**
     * @return {@code true} if the window has the input focus, {@code false} otherwise
     */
    boolean isFocused();

    /**
     * @return {@code true} if the window is minimized, {@code false} otherwise
     */
    boolean isMinimized();

    /**
     * @return {@code true} if the pointer is over the window's content area, {@code false} otherwise
     */
    boolean isPointerInside();

    /**
     * Returns whether a key is down in the window, as the window's last key events left it.
     *
     * @param key the key
     * @return {@code true} if the key is down, {@code false} otherwise; {@link Key#UNKNOWN} is never down
     */
    boolean isKeyDown(@NotNull Key key);

    /**
     * @return the pointer column in window coordinates, from the left edge of the content area
     */
    double getPointerX();

    /**
     * @return the pointer row in window coordinates, from the top edge of the content area
     */
    double getPointerY();

    /**
     * @return the text of the system clipboard, or {@code null} if it holds no text
     */
    @Nullable String getClipboardText();

    /**
     * Sets the text of the system clipboard.
     *
     * @param text the text
     */
    void setClipboardText(@NotNull String text);

    /**
     * Sets how the platform pointer behaves over the window.
     *
     * @param mode the pointer mode
     */
    void setPointerMode(@NotNull PointerMode mode);

    /**
     * Narrows the captured area to a rectangle of the content area: while the pointer mode is {@link PointerMode#CAPTURED}, the window is
     * focused and not minimized, the pointer stays inside the rectangle instead of the whole content area. The clip is released while the window
     * is not focused, minimized or closed and for another pointer mode, and holds again when the pointer is captured again. It is kept in
     * framebuffer pixels and follows the content area's position and size.
     *
     * @param clip the rectangle in framebuffer pixels, or {@code null} for the whole content area
     */
    void setPointerClip(@Nullable PointerClip clip);

    /**
     * Moves the pointer to a point of the content area. Nothing moves while the window does not have the input focus, or on a platform that
     * does not let a program move the pointer.
     *
     * @param x the column in window coordinates
     * @param y the row in window coordinates
     */
    void setPointerPosition(double x, double y);

    /**
     * Sets the receiver of the files dropped onto the window.
     *
     * @param listener the receiver, or {@code null} to ignore drops
     */
    void setFileDropListener(@Nullable FileDropListener listener);

    /**
     * Applies a gamma ramp to the monitor of the fullscreen window while the window is focused, and gives the monitor its original ramp back
     * otherwise: when the window is windowed or not focused, has moved to another monitor, or the ramp is {@code null}. A client calls it once
     * per frame; a monitor is written only when the target monitor or the ramp changes. Closing the window gives the monitor its original ramp
     * back. On a platform that does not let a program set gamma ramps nothing is applied.
     *
     * @param ramp the {@link #GAMMA_RAMP_SIZE} red, then green, then blue values (unsigned 16-bit), or {@code null} for the original ramp
     * @throws IllegalArgumentException if the ramp does not hold {@link #GAMMA_RAMP_CHANNEL_COUNT} times {@link #GAMMA_RAMP_SIZE} values
     */
    void applyGammaRamp(short @Nullable [] ramp);


    /**
     * Asks the window to close; the host's frame loop ends before its next frame.
     */
    void requestClose();

    /**
     * Makes the window's OpenGL context current on the calling thread.
     */
    void makeContextCurrent();

    /**
     * Makes no context current on the calling thread when the window's context is current there, for a thread that is done with the
     * context (for example a loader thread of a shared context window).
     */
    void releaseContext();

    /**
     * Centers the window, frame included, in the primary monitor's work area (the desktop without the task bar); a window larger
     * than the work area is first made as large as the work area allows. Without a primary monitor nothing changes. On a platform that does
     * not let a program place windows the window is only fitted and the platform places it.
     */
    void center();

    /**
     * Shows the window. An offscreen client never shows its window.
     */
    void show();

    /**
     * Presents the drawn frame: swaps the window's buffers.
     */
    void swapBuffers();

    /**
     * Creates a {@link WindowInput} instance with a {@link DoublePressDetector} of the default interval and the system's monotonic clock,
     * reads the window's surface size and installs the window's input callbacks.
     *
     * @param listener the receiver of the events
     * @return the created {@link WindowInput} instance; its callbacks stay installed until the window closes
     */
    @NotNull WindowInput createInput(@NotNull InputListener listener);

    /**
     * Creates a {@link WindowInput} instance and installs the window's input callbacks; the surface size starts empty (a window width and
     * height of {@code 0} convert as {@code 1}) until it is set.
     *
     * @param listener      the receiver of the events
     * @param doublePresses the detector that pairs button presses
     * @param clock         the clock of the button presses, in milliseconds
     * @return the created {@link WindowInput} instance; its callbacks stay installed until the window closes
     */
    @NotNull WindowInput createInput(@NotNull InputListener listener, @NotNull DoublePressDetector doublePresses, @NotNull LongSupplier clock);

    /**
     * Returns the window's input method, created on the first call: the platform's on Windows, a {@link NoTextInputMethod} elsewhere or when
     * the platform's cannot be bound. It is used on the thread that owns the window.
     *
     * @return the input method; one instance per window
     */
    @NotNull TextInputMethod getTextInputMethod();

    /**
     * Creates a {@link CursorSurface} instance that shows pictures as the window's pointer; the window keeps the platform's own arrow until a
     * picture is shown. It is used on the thread that owns the window.
     *
     * @return the created {@link CursorSurface} instance, closed by the caller
     */
    @NotNull CursorSurface createCursorSurface();


    /**
     * Disposes resources: gives a monitor whose gamma ramp the window set its original ramp back, frees the window's callbacks and destroys
     * the window. Closing again does nothing.
     */
    @Override
    void close();
}
