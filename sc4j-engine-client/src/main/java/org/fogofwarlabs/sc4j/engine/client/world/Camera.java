package org.fogofwarlabs.sc4j.engine.client.world;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.projection.WorldProjection;
import org.fogofwarlabs.sc4j.engine.client.render.WorldViewport;
import org.jetbrains.annotations.NotNull;

/**
 * Holds the world camera of a client: the position of the top-left corner of the screen area that shows the map on the projected plane of
 * the client's {@link WorldProjection} and the zoom (screen pixels per projected pixel) between a lower and an upper limit. The camera keeps
 * the shown area inside the map's bounds on the projected plane (the box of the projected map) and the centre of the shown area over the map,
 * so a turned map (a diamond in the isometric projection) never leaves more than about half the screen empty at the corners of its box; a map
 * smaller than the shown area is centred. Every change clamps the camera at once. With the top-down square projection the projected plane is
 * the world plane, and the box is the map.
 * <p>
 * The camera is the engine's {@link CameraControl}: a {@link CameraScroll} scrolls it by keys and at the screen edges, a
 * {@link CameraTargetScroll} and {@link ViewJumps} move it. The client's slash commands ({@link CameraCommandUtils}) change the zoom and
 * its limits.
 */
public final class Camera implements CameraControl {
    /**
     * The default lower zoom limit.
     */
    public static final float DEFAULT_MINIMUM_ZOOM = 0.5f;
    /**
     * The default upper zoom limit.
     */
    public static final float DEFAULT_MAXIMUM_ZOOM = 2;

    /**
     * Finds the smallest camera position on one axis: the lower bound, or the centred position when the shown length is at least the length
     * of the bounds.
     *
     * @param shown   the shown length in projected pixels
     * @param minimum the lower bound on the axis
     * @param maximum the upper bound on the axis
     * @return the smallest position
     */
    private static float findMinimumPosition(float shown, float minimum, float maximum) {
        float size = maximum - minimum;

        return shown >= size ? minimum + (size - shown) / 2 : minimum;
    }

    /**
     * Finds the largest camera position on one axis: the upper bound less the shown length, or the centred position when the shown length is
     * at least the length of the bounds.
     *
     * @param shown   the shown length in projected pixels
     * @param minimum the lower bound on the axis
     * @param maximum the upper bound on the axis
     * @return the largest position
     */
    private static float findMaximumPosition(float shown, float minimum, float maximum) {
        float size = maximum - minimum;

        return shown >= size ? minimum + (size - shown) / 2 : maximum - shown;
    }

    private final @NotNull WorldProjection projection;
    private final int mapWidth;
    private final int mapHeight;
    private final float boundsLeft;
    private final float boundsTop;
    private final float boundsRight;
    private final float boundsBottom;

    private int screenWidth;
    private int screenHeight;
    private float x;
    private float y;
    private float zoom;
    private float minimumZoom;
    private float maximumZoom;

    /**
     * Creates a {@link Camera} instance at the top-left corner of a map's projected bounds without zoom, with the default zoom limits.
     *
     * @param projection the projection of the client's world view
     * @param mapWidth   the map width in world units
     * @param mapHeight  the map height in world units
     * @throws IllegalArgumentException if a map size is not positive
     */
    public Camera(@NotNull WorldProjection projection, int mapWidth, int mapHeight) {
        if (mapWidth <= 0 || mapHeight <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid map size {0}x{1}", mapWidth, mapHeight);
        }

        this.projection   = projection;
        this.mapWidth     = mapWidth;
        this.mapHeight    = mapHeight;
        this.boundsLeft   = projection.getProjectedLeft(mapWidth, mapHeight);
        this.boundsTop    = projection.getProjectedTop(mapWidth, mapHeight);
        this.boundsRight  = projection.getProjectedRight(mapWidth, mapHeight);
        this.boundsBottom = projection.getProjectedBottom(mapWidth, mapHeight);

        this.screenWidth  = 1;
        this.screenHeight = 1;
        this.x            = this.boundsLeft;
        this.y            = this.boundsTop;
        this.zoom         = 1;
        this.minimumZoom  = DEFAULT_MINIMUM_ZOOM;
        this.maximumZoom  = DEFAULT_MAXIMUM_ZOOM;
    }

    /**
     * @return the projection of the client's world view
     */
    public @NotNull WorldProjection getProjection() {
        return this.projection;
    }

    /**
     * @return the map width in world units
     */
    public int getMapWidth() {
        return this.mapWidth;
    }

    /**
     * @return the map height in world units
     */
    public int getMapHeight() {
        return this.mapHeight;
    }

    /**
     * @return the left edge of the map's bounds on the projected plane
     */
    public float getBoundsLeft() {
        return this.boundsLeft;
    }

    /**
     * @return the top edge of the map's bounds on the projected plane
     */
    public float getBoundsTop() {
        return this.boundsTop;
    }

    /**
     * @return the right edge of the map's bounds on the projected plane
     */
    public float getBoundsRight() {
        return this.boundsRight;
    }

    /**
     * @return the bottom edge of the map's bounds on the projected plane
     */
    public float getBoundsBottom() {
        return this.boundsBottom;
    }

    /**
     * @return the width of the screen area in pixels
     */
    public int getScreenWidth() {
        return this.screenWidth;
    }

    /**
     * @return the height of the screen area in pixels
     */
    public int getScreenHeight() {
        return this.screenHeight;
    }

    /**
     * Sets the size of the screen area that shows the map and clamps the camera.
     *
     * @param screenWidth  the width in pixels
     * @param screenHeight the height in pixels
     * @throws IllegalArgumentException if a size is not positive
     */
    public void setScreenSize(int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid screen area {0}x{1}", screenWidth, screenHeight);
        }

        this.screenWidth  = screenWidth;
        this.screenHeight = screenHeight;
        this.clamp();
    }

    /**
     * @return the projected x shown at the left edge
     */
    @Override
    public float getX() {
        return this.x;
    }

    /**
     * @return the projected y shown at the top edge
     */
    @Override
    public float getY() {
        return this.y;
    }

    /**
     * @return the smallest x of the position
     */
    @Override
    public float getMinimumX() {
        return findMinimumPosition(this.getViewWidth(), this.boundsLeft, this.boundsRight);
    }

    /**
     * @return the largest x of the position; below {@link #getMinimumX} the camera has no room to move on the axis
     */
    @Override
    public float getMaximumX() {
        return findMaximumPosition(this.getViewWidth(), this.boundsLeft, this.boundsRight);
    }

    /**
     * @return the smallest y of the position
     */
    @Override
    public float getMinimumY() {
        return findMinimumPosition(this.getViewHeight(), this.boundsTop, this.boundsBottom);
    }

    /**
     * @return the largest y of the position; below {@link #getMinimumY} the camera has no room to move on the axis
     */
    @Override
    public float getMaximumY() {
        return findMaximumPosition(this.getViewHeight(), this.boundsTop, this.boundsBottom);
    }

    /**
     * @return the width of the shown area in projected pixels
     */
    @Override
    public float getViewWidth() {
        return this.screenWidth / this.zoom;
    }

    /**
     * @return the height of the shown area in projected pixels
     */
    @Override
    public float getViewHeight() {
        return this.screenHeight / this.zoom;
    }

    /**
     * @return the screen pixels per projected pixel
     */
    @Override
    public float getZoom() {
        return this.zoom;
    }

    /**
     * Sets the zoom, limited to the zoom limits, keeping the centre of the shown area, and clamps the camera.
     *
     * @param zoom the screen pixels per projected pixel
     * @throws IllegalArgumentException if the zoom is not positive
     */
    public void setZoom(float zoom) {
        if (!(zoom > 0)) {
            throw Exceptions.newIllegalArgumentException("Invalid zoom {0}", zoom);
        }

        float centerX = this.x + this.getViewWidth() / 2;
        float centerY = this.y + this.getViewHeight() / 2;

        this.zoom = Math.clamp(zoom, this.minimumZoom, this.maximumZoom);
        this.moveTo(centerX - this.getViewWidth() / 2, centerY - this.getViewHeight() / 2);
    }

    /**
     * @return the lower zoom limit
     */
    public float getMinimumZoom() {
        return this.minimumZoom;
    }

    /**
     * @return the upper zoom limit
     */
    public float getMaximumZoom() {
        return this.maximumZoom;
    }

    /**
     * Sets the zoom limits and limits the current zoom to them.
     *
     * @param minimumZoom the lower limit
     * @param maximumZoom the upper limit
     * @throws IllegalArgumentException if a limit is not positive or the lower limit is above the upper one
     */
    public void setZoomLimits(float minimumZoom, float maximumZoom) {
        if (!(minimumZoom > 0) || !(maximumZoom >= minimumZoom)) {
            throw Exceptions.newIllegalArgumentException("Invalid zoom limits {0} to {1}", minimumZoom, maximumZoom);
        }

        this.minimumZoom = minimumZoom;
        this.maximumZoom = maximumZoom;
        this.setZoom(this.zoom);
    }


    /**
     * Moves the camera to a position of the top-left corner; the camera keeps itself inside the map as it does for every move.
     *
     * @param x the projected x shown at the left edge
     * @param y the projected y shown at the top edge
     */
    @Override
    public void moveTo(float x, float y) {
        this.x = x;
        this.y = y;
        this.clamp();
    }

    /**
     * Moves the camera so that a world position is in the centre of the shown area, as far as the map's bounds allow.
     *
     * @param worldX the world x
     * @param worldY the world y
     */
    public void centerOn(float worldX, float worldY) {
        this.moveTo(this.projection.toProjectedX(worldX, worldY) - this.getViewWidth() / 2,
                    this.projection.toProjectedY(worldX, worldY) - this.getViewHeight() / 2);
    }

    /**
     * Moves the camera by screen pixels.
     *
     * @param screenX the horizontal move in screen pixels, right positive
     * @param screenY the vertical move in screen pixels, down positive
     */
    public void scroll(float screenX, float screenY) {
        this.moveTo(this.x + screenX / this.zoom, this.y + screenY / this.zoom);
    }

    /**
     * Converts a position of the screen area to a world x through the projection.
     *
     * @param screenX the column
     * @param screenY the row
     * @return the world x
     */
    public float toWorldX(float screenX, float screenY) {
        return this.projection.toWorldX(this.x + screenX / this.zoom, this.y + screenY / this.zoom);
    }

    /**
     * Converts a position of the screen area to a world y through the projection.
     *
     * @param screenX the column
     * @param screenY the row
     * @return the world y
     */
    public float toWorldY(float screenX, float screenY) {
        return this.projection.toWorldY(this.x + screenX / this.zoom, this.y + screenY / this.zoom);
    }

    /**
     * Sets a world viewport to the camera's screen area, position and zoom.
     *
     * @param viewport the viewport of the next frame
     */
    public void applyTo(@NotNull WorldViewport viewport) {
        viewport.set(this.screenWidth, this.screenHeight, this.x, this.y, this.zoom);
    }

    /**
     * Clamps the camera: the shown area inside the map's bounds, then its centre over the map (the world position of the centre moved into
     * the map and the shown area into the bounds again).
     */
    private void clamp() {
        this.clampToBounds();

        float centerX = this.x + this.getViewWidth() / 2;
        float centerY = this.y + this.getViewHeight() / 2;
        float worldX = this.projection.toWorldX(centerX, centerY);
        float worldY = this.projection.toWorldY(centerX, centerY);
        float insideX = Math.clamp(worldX, 0, this.mapWidth);
        float insideY = Math.clamp(worldY, 0, this.mapHeight);

        if (insideX == worldX && insideY == worldY) {
            return;
        }

        this.x = this.projection.toProjectedX(insideX, insideY) - this.getViewWidth() / 2;
        this.y = this.projection.toProjectedY(insideX, insideY) - this.getViewHeight() / 2;
        this.clampToBounds();
    }

    private void clampToBounds() {
        this.x = Math.clamp(this.x, this.getMinimumX(), this.getMaximumX());
        this.y = Math.clamp(this.y, this.getMinimumY(), this.getMaximumY());
    }
}
