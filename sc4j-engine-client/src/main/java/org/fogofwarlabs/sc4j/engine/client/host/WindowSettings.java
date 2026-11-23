package org.fogofwarlabs.sc4j.engine.client.host;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

/**
 * Holds how a {@link HostWindow} is created: its title, client area size, the smallest size the user may resize it to,
 * whether it has a frame, and the OpenGL core profile version of its context. A window is always created hidden;
 * {@link HostWindow#show} shows it, so an offscreen client never calls it.
 */
public final class WindowSettings {
    /**
     * The OpenGL major version of the engine's renderer defaults.
     */
    public static final int DEFAULT_CONTEXT_MAJOR_VERSION = 3;
    /**
     * The OpenGL minor version of the engine's renderer defaults.
     */
    public static final int DEFAULT_CONTEXT_MINOR_VERSION = 3;
    /**
     * The size limit value that leaves a dimension unlimited.
     */
    public static final int NO_SIZE_LIMIT = 0;

    /**
     * Builds {@link WindowSettings} instances.
     */
    public static final class Builder {
        private final @NotNull String title;
        private final int width;
        private final int height;

        private int minimumWidth;
        private int minimumHeight;
        private boolean decorated;
        private boolean scaledToMonitor;
        private int contextMajorVersion;
        private int contextMinorVersion;

        private Builder(@NotNull String title, int width, int height) {
            this.title  = title;
            this.width  = width;
            this.height = height;

            this.minimumWidth        = NO_SIZE_LIMIT;
            this.minimumHeight       = NO_SIZE_LIMIT;
            this.decorated           = true;
            this.scaledToMonitor     = false;
            this.contextMajorVersion = DEFAULT_CONTEXT_MAJOR_VERSION;
            this.contextMinorVersion = DEFAULT_CONTEXT_MINOR_VERSION;
        }

        /**
         * Sets the smallest client area the user may resize the window to, in the units of the window's size ({@link #setScaledToMonitor}).
         *
         * @param minimumWidth  the smallest width, or {@link #NO_SIZE_LIMIT}
         * @param minimumHeight the smallest height, or {@link #NO_SIZE_LIMIT}
         * @return this instance for chaining
         */
        public @NotNull Builder setMinimumSize(int minimumWidth, int minimumHeight) {
            this.minimumWidth  = minimumWidth;
            this.minimumHeight = minimumHeight;

            return this;
        }

        /**
         * Sets whether the window has a frame (title bar and border). An offscreen window has none, so that a screen-sized
         * client area is not cut by the frame.
         *
         * @param decorated whether the window has a frame
         * @return this instance for chaining
         */
        public @NotNull Builder setDecorated(boolean decorated) {
            this.decorated = decorated;

            return this;
        }

        /**
         * Sets whether the window's size is in logical units of its monitor: the platform multiplies the requested size by the monitor's
         * content scale ({@link HostWindow#getContentScale}) and resizes the window when it moves to a monitor of another scale. Off, the
         * size is in the platform's screen coordinates (pixels on Windows).
         *
         * @param scaledToMonitor whether the window's size follows its monitor's scale
         * @return this instance for chaining
         */
        public @NotNull Builder setScaledToMonitor(boolean scaledToMonitor) {
            this.scaledToMonitor = scaledToMonitor;

            return this;
        }

        /**
         * Sets the OpenGL core profile version of the window's context.
         *
         * @param majorVersion the major version
         * @param minorVersion the minor version
         * @return this instance for chaining
         */
        public @NotNull Builder setContextVersion(int majorVersion, int minorVersion) {
            this.contextMajorVersion = majorVersion;
            this.contextMinorVersion = minorVersion;

            return this;
        }

        /**
         * Builds a {@link WindowSettings} instance from the builder's values.
         *
         * @return the built {@link WindowSettings} instance
         * @throws IllegalArgumentException if a size is not positive or a size limit is negative
         */
        public @NotNull WindowSettings build() {
            if (this.width <= 0 || this.height <= 0 || this.minimumWidth < 0 || this.minimumHeight < 0) {
                throw Exceptions.newIllegalArgumentException("Invalid window size {0}x{1} or minimum size {2}x{3}", this.width, this.height,
                                                             this.minimumWidth, this.minimumHeight);
            }

            return new WindowSettings(this);
        }
    }

    /**
     * Creates a {@link Builder} instance for a window.
     *
     * @param title  the window title
     * @param width  the client area width in screen coordinates
     * @param height the client area height in screen coordinates
     * @return the created {@link Builder} instance
     */
    public static @NotNull Builder builder(@NotNull String title, int width, int height) {
        return new Builder(title, width, height);
    }


    private final @NotNull String title;
    private final int width;
    private final int height;

    private final int minimumWidth;
    private final int minimumHeight;
    private final boolean decorated;
    private final boolean scaledToMonitor;
    private final int contextMajorVersion;
    private final int contextMinorVersion;

    private WindowSettings(@NotNull Builder builder) {
        this.title  = builder.title;
        this.width  = builder.width;
        this.height = builder.height;

        this.minimumWidth        = builder.minimumWidth;
        this.minimumHeight       = builder.minimumHeight;
        this.decorated           = builder.decorated;
        this.scaledToMonitor     = builder.scaledToMonitor;
        this.contextMajorVersion = builder.contextMajorVersion;
        this.contextMinorVersion = builder.contextMinorVersion;
    }

    /**
     * @return the window title
     */
    public @NotNull String getTitle() {
        return this.title;
    }

    /**
     * @return the client area width in screen coordinates
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the client area height in screen coordinates
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the smallest width the user may resize the window to, or {@link #NO_SIZE_LIMIT}
     */
    public int getMinimumWidth() {
        return this.minimumWidth;
    }

    /**
     * @return the smallest height the user may resize the window to, or {@link #NO_SIZE_LIMIT}
     */
    public int getMinimumHeight() {
        return this.minimumHeight;
    }

    /**
     * @return {@code true} if the window has a frame, {@code false} otherwise
     */
    public boolean isDecorated() {
        return this.decorated;
    }

    /**
     * @return {@code true} if the window's size follows its monitor's content scale, {@code false} otherwise
     */
    public boolean isScaledToMonitor() {
        return this.scaledToMonitor;
    }

    /**
     * @return the OpenGL major version of the window's context
     */
    public int getContextMajorVersion() {
        return this.contextMajorVersion;
    }

    /**
     * @return the OpenGL minor version of the window's context
     */
    public int getContextMinorVersion() {
        return this.contextMinorVersion;
    }
}
