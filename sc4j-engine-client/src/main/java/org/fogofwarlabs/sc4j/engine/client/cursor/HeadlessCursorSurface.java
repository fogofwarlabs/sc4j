package org.fogofwarlabs.sc4j.engine.client.cursor;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.display.DetailSet;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Serves a client without window, for example an offscreen run or a test: it shows nothing and only remembers the last shown picture. No platform
 * shows its pictures, so it is never available ({@link #isAvailable}).
 */
public final class HeadlessCursorSurface implements CursorSurface {
    private @Nullable DetailSet<Picture> shown;
    private int scale;

    /**
     * Creates a {@link HeadlessCursorSurface} instance that shows the platform's own arrow.
     */
    public HeadlessCursorSurface() {
        this.shown = null;
        this.scale = 1;
    }

    /**
     * @return the last shown picture with its detail pictures, or {@code null} while the platform's own arrow or no pointer is shown
     */
    public @Nullable DetailSet<Picture> getShown() {
        return this.shown;
    }

    /**
     * @return the platform pixels each picture pixel covers
     */
    public int getScale() {
        return this.scale;
    }

    /**
     * @return {@code true} if the platform shows cursor pictures (it has not refused one), {@code false} otherwise; without window always {@code false}
     */
    @Override
    public boolean isAvailable() {
        return false;
    }

    /**
     * Sets how many platform pixels each picture pixel covers, so that the pointer keeps its size on a scaled display; a picture shown already
     * is shown again at the new scale.
     * <p>
     * Without window it only remembers the scale.
     *
     * @param scale the whole scale, at least {@code 1}
     * @throws IllegalArgumentException if the scale is less than {@code 1}
     */
    @Override
    public void setScale(int scale) {
        if (scale < 1) {
            throw Exceptions.newIllegalArgumentException("Invalid cursor scale {0}", scale);
        }

        this.scale = scale;
    }


    /**
     * Shows a picture as the pointer, from the detail of the scale; the picture's anchor is the hot spot. Showing the same set again builds
     * nothing new.
     * <p>
     * Without window it only remembers the picture.
     *
     * @param pictures the picture with its detail pictures
     */
    @Override
    public void show(@NotNull DetailSet<Picture> pictures) {
        this.shown = pictures;
    }

    /**
     * Shows the platform's own arrow.
     */
    @Override
    public void showDefault() {
        this.shown = null;
    }

    /**
     * Shows no pointer over the window (a blank picture), for frames whose cursor the client draws itself or that show none. Nothing changes
     * while the surface is unavailable.
     * <p>
     * Without window it only forgets the shown picture.
     */
    @Override
    public void hide() {
        this.shown = null;
    }


    /**
     * Disposes resources: shows the platform's own arrow again and releases the built cursors. Closing again does nothing.
     */
    @Override
    public void close() {
        this.shown = null;
    }
}
