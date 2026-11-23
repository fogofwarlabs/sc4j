package org.fogofwarlabs.sc4j.engine.client.app;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.audio.AudioMixer;
import org.fogofwarlabs.sc4j.engine.client.audio.MusicPlaylist;
import org.fogofwarlabs.sc4j.engine.client.cursor.CursorSurface;
import org.fogofwarlabs.sc4j.engine.client.cursor.HeadlessCursorSurface;
import org.fogofwarlabs.sc4j.engine.client.display.DisplayScale;
import org.fogofwarlabs.sc4j.engine.client.display.UiSurface;
import org.fogofwarlabs.sc4j.engine.client.host.FrameResult;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.scene.SceneStack;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.engine.mod.ModLaunch;
import org.fogofwarlabs.sc4j.engine.mod.ModStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Runs the scenes of an engine-only mod's client, independent of the window: the shared services its {@link ClientScene}s open with (the launch
 * request, the mod storage, the canvas, the audio mixer, the music ({@link MusicPlaylist}, which plays across the scenes), the cursor surface and the
 * scene stack) and the frame of its main loop. Each frame ({@link #runFrame}) applies the requested scene switch, updates the music and the scenes
 * and draws the scenes on the canvas over a black background; input events go to the top scene ({@link #handleInput}). A scene asks the client to end
 * with {@link #requestQuit}; the frame that follows is the last.
 * <p>
 * Scenes work in logical units only: each frame makes the interface surface ({@link UiSurface}) of the framebuffer at the client's display
 * scale ({@link #setDisplayScale}), fitted to the logical area the current and the modal scenes need and the engine minimum
 * ({@link ClientScene#getMinimumWidth}, {@link UiSurface#fitTo}), begins the canvas on it, and the pointer positions of the input events are
 * converted from framebuffer pixels to logical units before a scene sees them. The cursor surface shows the pointer pictures at the whole
 * number nearest the scale.
 * <p>
 * The {@link WindowClient} runs it on a window; an offscreen run (a test, a screenshot) drives the same frames on an
 * {@link org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas}.
 */
public final class SceneClient implements AutoCloseable {
    private static final @NotNull Logger LOGGER = Logger.of(SceneClient.class);
    private static final int BACKGROUND_COLOR = 0x000000FF;

    /**
     * Logs the switches of the client's scenes.
     */
    private static final class SwitchLog implements SceneStack.Listener<ClientScene> {
        /**
         * Called after a scene became current: the stack opened it and closed the left scene.
         *
         * @param previous the left scene, or {@code null} for the first scene
         * @param next     the new current scene
         */
        @Override
        public void onSwitched(@Nullable ClientScene previous, @NotNull ClientScene next) {
            LOGGER.info("SCENE: {0}", next.getClass().getSimpleName());
        }

        /**
         * Called when a scene the stack switches to fails to open; the current scene stays.
         *
         * @param scene     the scene that failed
         * @param exception what its open threw
         */
        @Override
        public void onOpenFailed(@NotNull ClientScene scene, @NotNull Exception exception) {
            LOGGER.exception(LogLevel.ERROR, exception, "SCENE OPEN FAILED: {0}", scene.getClass().getSimpleName());
        }
    }

    private final @NotNull ModLaunch launch;
    private final @NotNull ModStorage storage;
    private final @NotNull Canvas canvas;
    private final @NotNull AudioMixer audio;
    private final @NotNull MusicPlaylist music;
    private final @NotNull CursorSurface cursor;

    private final @NotNull SceneStack<SceneClient, Canvas, ClientScene> scenes;

    private long time;
    private @NotNull DisplayScale displayScale;
    private @Nullable UiSurface surface;
    private int width;
    private int height;
    private boolean quitRequested;

    /**
     * Creates a {@link SceneClient} instance without scenes and without window pointer: its cursor surface shows nothing.
     *
     * @param launch  the launch request of the run
     * @param storage the mod storage
     * @param canvas  the canvas the scenes draw on
     * @param audio   the audio mixer of the scenes' sounds
     */
    public SceneClient(@NotNull ModLaunch launch, @NotNull ModStorage storage, @NotNull Canvas canvas, @NotNull AudioMixer audio) {
        this(launch, storage, canvas, audio, new HeadlessCursorSurface());
    }

    /**
     * Creates a {@link SceneClient} instance without scenes.
     *
     * @param launch  the launch request of the run
     * @param storage the mod storage
     * @param canvas  the canvas the scenes draw on
     * @param audio   the audio mixer of the scenes' sounds
     * @param cursor  the surface that shows the window's pointer, closed by the caller
     */
    public SceneClient(@NotNull ModLaunch launch, @NotNull ModStorage storage, @NotNull Canvas canvas, @NotNull AudioMixer audio,
                       @NotNull CursorSurface cursor) {
        this.launch  = launch;
        this.storage = storage;
        this.canvas  = canvas;
        this.audio   = audio;
        this.music   = new MusicPlaylist(audio);
        this.cursor  = cursor;

        this.scenes = new SceneStack<>(this, new SwitchLog());

        this.time          = 0;
        this.displayScale  = DisplayScale.ONE;
        this.surface       = null;
        this.width         = canvas.getWidth();
        this.height        = canvas.getHeight();
        this.quitRequested = false;
    }

    /**
     * @return the launch request of the run: the mod's identity, game definition, resources and formats, and a requested replay or game to join
     */
    public @NotNull ModLaunch getLaunch() {
        return this.launch;
    }

    /**
     * @return the mod storage, where the mod keeps its replays and saved games
     */
    public @NotNull ModStorage getStorage() {
        return this.storage;
    }

    /**
     * @return the canvas the scenes draw on; a scene uploads its textures to it
     */
    public @NotNull Canvas getCanvas() {
        return this.canvas;
    }

    /**
     * @return the audio mixer of the scenes' sounds
     */
    public @NotNull AudioMixer getAudio() {
        return this.audio;
    }

    /**
     * @return the music, which plays across the scenes on its own bus under the audio mixer's master bus
     */
    public @NotNull MusicPlaylist getMusic() {
        return this.music;
    }

    /**
     * @return the surface that shows the window's pointer, for a scene's {@link org.fogofwarlabs.sc4j.engine.client.cursor.CursorController}
     */
    public @NotNull CursorSurface getCursor() {
        return this.cursor;
    }

    /**
     * @return the scene stack: a scene requests the next scene with {@link SceneStack#replace} and opens popups with {@link SceneStack#pushModal}
     */
    public @NotNull SceneStack<SceneClient, Canvas, ClientScene> getScenes() {
        return this.scenes;
    }

    /**
     * @return the client time of the current frame in milliseconds
     */
    public long getTime() {
        return this.time;
    }

    /**
     * @return the display scale the frames are drawn at
     */
    public @NotNull DisplayScale getDisplayScale() {
        return this.displayScale;
    }

    /**
     * Sets the display scale the next frames are drawn at; the window client sets it from the window's monitor when it changes, keeping the
     * user's interface scale.
     *
     * @param displayScale the display scale
     */
    public void setDisplayScale(@NotNull DisplayScale displayScale) {
        this.displayScale = displayScale;
    }

    /**
     * Sets the user's interface scale of the mod, for example from the mod's options screen: the next frames are drawn at the window's content
     * scale times the new user scale.
     *
     * @param userScale the user's interface scale, {@code 1} for the operating system's scale
     * @throws IllegalArgumentException if the scale is not positive and finite
     */
    public void setUserScale(float userScale) {
        this.displayScale = DisplayScale.of(this.displayScale.getContentScale(), userScale);
    }

    /**
     * @return the interface surface of the current frame, or {@code null} before the first frame
     */
    public @Nullable UiSurface getSurface() {
        return this.surface;
    }

    /**
     * @return the width of the current frame in logical units
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the height of the current frame in logical units
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return {@code true} if a scene asked the client to end, {@code false} otherwise
     */
    public boolean isQuitRequested() {
        return this.quitRequested;
    }


    /**
     * Creates the first scene with the mod's factory and opens it.
     *
     * @param factory the mod's factory of the first scene
     * @throws ModException          if the mod refuses the launch request
     * @throws IOException           if the first scene cannot be opened
     * @throws IllegalStateException if the client was started already
     */
    public void start(@NotNull FirstSceneFactory factory) throws ModException, IOException {
        if (this.scenes.getScene() != null) {
            throw Exceptions.newIllegalStateException("The client was started already.");
        }

        this.scenes.start(factory.createFirstScene(this));
    }

    /**
     * Asks the client to end: the next frame is the last.
     */
    public void requestQuit() {
        this.quitRequested = true;
    }

    /**
     * Hands an input event to the top scene, its pointer position converted from framebuffer pixels to the logical units of the current
     * frame's surface (unchanged before the first frame).
     *
     * @param event the event, its pointer position in framebuffer pixels
     * @return {@code true} if the scene used the event, {@code false} otherwise
     */
    public boolean handleInput(@NotNull InputEvent event) {
        UiSurface current = this.surface;
        InputEvent handed = event;

        if (current != null && !current.isPixelExact() && (event.isPointerEvent() || event.getType() == InputEvent.Type.WHEEL_SCROLLED)) {
            handed = event.createAt(current.toLogicalX(event.getX()), current.toLogicalY(event.getY()));
        }

        return this.scenes.handleInput(handed);
    }

    /**
     * Runs one frame: applies the requested scene switch, updates the scenes and draws them on the canvas over a black background, in the
     * logical units of the framebuffer at the client's display scale, lowered where the scenes or the engine minimum need a larger logical
     * area.
     *
     * @param time              the client time in milliseconds
     * @param framebufferWidth  the framebuffer width in pixels
     * @param framebufferHeight the framebuffer height in pixels
     * @return {@link FrameResult#LAST} once a scene asked the client to end, {@link FrameResult#PRESENTED} otherwise
     * @throws IOException if a scene cannot read a resource it draws
     */
    public @NotNull FrameResult runFrame(long time, int framebufferWidth, int framebufferHeight) throws IOException {
        this.time = time;
        this.cursor.setScale(this.displayScale.getWholeScale());
        this.scenes.applyPendingScene(time);

        UiSurface current = UiSurface.of(framebufferWidth, framebufferHeight, this.displayScale)
                                     .fitTo(this.findMinimumWidth(), this.findMinimumHeight());

        this.surface = current;
        this.width   = current.getWidth();
        this.height  = current.getHeight();

        this.music.update(time);
        this.scenes.update(time);

        this.canvas.begin(current);
        this.canvas.fillRectangle(0, 0, this.width, this.height, BACKGROUND_COLOR);

        try {
            this.scenes.draw(this.canvas);
        } finally {
            this.canvas.end();
        }

        return this.quitRequested ? FrameResult.LAST : FrameResult.PRESENTED;
    }

    private int findMinimumWidth() {
        ClientScene current = this.scenes.getScene();
        List<ClientScene> modals = this.scenes.getModalScenes();
        int width = Math.max(UiSurface.MINIMUM_WIDTH, current != null ? current.getMinimumWidth() : 0);

        for (int index = 0; index < modals.size(); index++) {
            width = Math.max(width, modals.get(index).getMinimumWidth());
        }

        return width;
    }

    private int findMinimumHeight() {
        ClientScene current = this.scenes.getScene();
        List<ClientScene> modals = this.scenes.getModalScenes();
        int height = Math.max(UiSurface.MINIMUM_HEIGHT, current != null ? current.getMinimumHeight() : 0);

        for (int index = 0; index < modals.size(); index++) {
            height = Math.max(height, modals.get(index).getMinimumHeight());
        }

        return height;
    }

    /**
     * Disposes resources: closes every scene, the modal scenes first, then stops the music and closes its player.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.scenes::close,
                this.music::close
        );
    }
}
