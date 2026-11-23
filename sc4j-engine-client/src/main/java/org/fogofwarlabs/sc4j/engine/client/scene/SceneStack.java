package org.fogofwarlabs.sc4j.engine.client.scene;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the current scene of a client, the scene requested next and the modal scenes over the current one.
 * <p>
 * A switch opens the next scene first: if it fails to open, the current scene stays and the listener hears of the failure;
 * otherwise the left scene is closed (unless it is the retained scene, which its owner keeps for later) and the next one
 * becomes current. A requested scene ({@link #replace}) is switched to when the client applies it
 * ({@link #applyPendingScene}), after its transition when it has one. Modal scenes are drawn over the current scene in the
 * order they were opened; the top one is the input target ({@link #getTopScene}) unless an input router chooses another
 * ({@link #setInputRouter}).
 * <p>
 * Each frame the client calls {@link #applyPendingScene}, {@link #update} and {@link #draw}, in that order.
 *
 * @param <C> the type of the context the scenes open with
 * @param <S> the type of the surface the scenes draw on
 * @param <T> the type of the scenes
 */
public final class SceneStack<C, S, T extends Scene<C, S>> {
    /**
     * Hears the switches of a {@link SceneStack}.
     *
     * @param <T> the type of the scenes
     */
    public interface Listener<T> {
        /**
         * Called after a scene became current: the stack opened it and closed the left scene.
         *
         * @param previous the left scene, or {@code null} for the first scene
         * @param next     the new current scene
         */
        void onSwitched(@Nullable T previous, @NotNull T next);

        /**
         * Called when a scene the stack switches to fails to open; the current scene stays.
         *
         * @param scene     the scene that failed
         * @param exception what its open threw
         */
        void onOpenFailed(@NotNull T scene, @NotNull Exception exception);
    }

    /**
     * Chooses the scene that takes an input event, for a client whose input routing differs from the default: the top scene
     * ({@link SceneStack#getTopScene}), for example one that routes a pointer event to the scene under the pointer.
     *
     * @param <T> the type of the scenes
     */
    public interface InputRouter<T> {
        /**
         * Finds the scene that takes an input event.
         *
         * @param event the event
         * @return the scene that takes it, or {@code null} for the default target, the top scene
         */
        @Nullable T findInputTarget(@NotNull InputEvent event);
    }


    private final @NotNull C context;
    private final @NotNull Listener<T> listener;

    private @Nullable T scene;
    private @Nullable T retainedScene;

    private @Nullable T pendingScene;
    private @Nullable SceneTransition<S> pendingTransition;
    private boolean transitionStarted;

    private final @NotNull List<T> modalScenes;

    private @Nullable InputRouter<T> inputRouter;

    /**
     * Creates a {@link SceneStack} instance without scenes.
     *
     * @param context  the context the scenes open with
     * @param listener hears the switches
     */
    public SceneStack(@NotNull C context, @NotNull Listener<T> listener) {
        this.context  = context;
        this.listener = listener;

        this.scene         = null;
        this.retainedScene = null;

        this.pendingScene      = null;
        this.pendingTransition = null;
        this.transitionStarted = false;

        this.modalScenes = new ArrayList<>();

        this.inputRouter = null;
    }

    /**
     * @return the current scene, or {@code null} before the first
     */
    public @Nullable T getScene() {
        return this.scene;
    }

    /**
     * Returns the current scene of a started stack.
     *
     * @return the current scene
     * @throws IllegalStateException if no scene was started
     */
    public @NotNull T requireScene() {
        T current = this.scene;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The scene stack has no scene.");
        }

        return current;
    }

    /**
     * @return the top modal scene, else the current scene, or {@code null} before the first: the scene that takes the input
     */
    public @Nullable T getTopScene() {
        return this.modalScenes.isEmpty() ? this.scene : this.modalScenes.getLast();
    }

    /**
     * @return the modal scenes over the current scene from bottom to top, unmodifiable
     */
    public @NotNull List<T> getModalScenes() {
        return Collections.unmodifiableList(this.modalScenes);
    }

    /**
     * @return the scene requested next, or {@code null} if none is pending
     */
    public @Nullable T getPendingScene() {
        return this.pendingScene;
    }

    /**
     * Sets the scene the stack keeps open when it is left (for example a title screen that comes back); its owner closes it,
     * or {@link #close} does.
     *
     * @param scene the retained scene, or {@code null} for none
     */
    public void setRetainedScene(@Nullable T scene) {
        this.retainedScene = scene;
    }

    /**
     * @return {@code true} if a modal scene is open, {@code false} otherwise
     */
    public boolean hasModal() {
        return !this.modalScenes.isEmpty();
    }

    /**
     * Sets the router that chooses the scene taking each input event ({@link #handleInput}).
     *
     * @param inputRouter the router, or {@code null} for the default: the top scene
     */
    public void setInputRouter(@Nullable InputRouter<T> inputRouter) {
        this.inputRouter = inputRouter;
    }


    /**
     * Opens the first scene and makes it current.
     *
     * @param first the first scene
     * @throws IOException           if the scene cannot be opened
     * @throws IllegalStateException if a scene was started already
     */
    public void start(@NotNull T first) throws IOException {
        if (this.scene != null) {
            throw Exceptions.newIllegalStateException("The scene stack was started already.");
        }

        first.open(this.context);

        this.scene = first;
        this.listener.onSwitched(null, first);
    }

    /**
     * Switches to a scene at once: opens it, closes the left scene unless it is retained or the same, and makes it current. A
     * scene that fails to open leaves the current scene, and the listener hears of the failure.
     *
     * @param next the next scene
     * @return {@code true} if the scene became current, {@code false} if it failed to open
     */
    public boolean switchTo(@NotNull T next) {
        try {
            next.open(this.context);
        } catch (IOException | RuntimeException exception) {
            this.listener.onOpenFailed(next, exception);

            return false;
        }

        T previous = this.scene;

        if (previous != null && previous != this.retainedScene && previous != next) {
            previous.close();
        }

        this.scene = next;
        this.listener.onSwitched(previous, next);

        return true;
    }

    /**
     * Requests a switch to a scene the next time the client applies the pending scene. It replaces an earlier request.
     *
     * @param next the next scene, not opened yet
     */
    public void replace(@NotNull T next) {
        this.pendingScene      = next;
        this.pendingTransition = null;
        this.transitionStarted = false;
    }

    /**
     * Requests a switch to a scene through a transition: the transition starts the next time the client applies the pending
     * scene, and the switch happens when it completes. It replaces an earlier request.
     *
     * @param next       the next scene, not opened yet
     * @param transition the transition
     */
    public void replace(@NotNull T next, @NotNull SceneTransition<S> transition) {
        this.pendingScene      = next;
        this.pendingTransition = transition;
        this.transitionStarted = false;
    }

    /**
     * Drops the requested switch and its transition.
     */
    public void clearPendingScene() {
        this.pendingScene      = null;
        this.pendingTransition = null;
        this.transitionStarted = false;
    }

    /**
     * Applies the requested switch: advances its transition, and once the transition completes (or without one) switches to
     * the requested scene ({@link #switchTo}).
     *
     * @param time the client time in milliseconds
     */
    public void applyPendingScene(long time) {
        T next = this.pendingScene;

        if (next == null) {
            return;
        }

        SceneTransition<S> transition = this.pendingTransition;

        if (transition != null) {
            if (!this.transitionStarted) {
                this.transitionStarted = true;
                transition.start(time);
            }

            if (!transition.update(time)) {
                return;
            }
        }

        this.clearPendingScene();
        this.switchTo(next);
    }

    /**
     * Opens a modal scene over the others; it becomes the input target.
     *
     * @param modal the modal scene
     * @throws IOException if the scene cannot be opened
     */
    public void pushModal(@NotNull T modal) throws IOException {
        modal.open(this.context);
        this.addModal(modal);
    }

    /**
     * Puts a scene its owner opened already over the others as the top modal scene; from then on the stack updates and draws
     * it and closes it on {@link #closeModal}, {@link #popModal} or {@link #close}.
     *
     * @param modal the open modal scene
     */
    public void addModal(@NotNull T modal) {
        this.modalScenes.add(modal);
    }

    /**
     * Closes a modal scene wherever it is in the modal layer; the modal scenes above it stay open.
     *
     * @param modal the modal scene
     * @return {@code true} if the scene was a modal scene of this stack and is closed now, {@code false} if it was not (nothing is closed then)
     */
    public boolean closeModal(@NotNull T modal) {
        if (!this.modalScenes.remove(modal)) {
            return false;
        }

        modal.close();

        return true;
    }

    /**
     * Closes the top modal scene.
     *
     * @throws IllegalStateException if no modal scene is open
     */
    public void popModal() {
        if (this.modalScenes.isEmpty()) {
            throw Exceptions.newIllegalStateException("No modal scene is open.");
        }

        this.modalScenes.removeLast().close();
    }

    /**
     * Updates the current scene, then the modal scenes from bottom to top.
     *
     * @param time the client time in milliseconds
     */
    public void update(long time) {
        T current = this.scene;

        if (current != null) {
            current.update(time);
        }

        for (int index = 0; index < this.modalScenes.size(); index++) {
            this.modalScenes.get(index).update(time);
        }
    }

    /**
     * Hands an input event to the scene that takes the input: the scene the input router chooses ({@link #setInputRouter}), by default
     * the top modal scene, else the current scene ({@link #getTopScene}).
     *
     * @param event the event
     * @return {@code true} if the scene used the event, {@code false} otherwise or without a scene
     */
    public boolean handleInput(@NotNull InputEvent event) {
        InputRouter<T> router = this.inputRouter;
        T target = router != null ? router.findInputTarget(event) : null;

        if (target == null) {
            target = this.getTopScene();
        }

        return target != null && target.handleInput(event);
    }

    /**
     * Draws the current scene, then the modal scenes from bottom to top, then the running transition.
     *
     * @param surface the surface
     * @throws IOException if a scene or the transition cannot read a resource it draws
     */
    public void draw(@NotNull S surface) throws IOException {
        T current = this.scene;

        if (current != null) {
            current.draw(surface);
        }

        for (int index = 0; index < this.modalScenes.size(); index++) {
            this.modalScenes.get(index).draw(surface);
        }

        SceneTransition<S> transition = this.pendingTransition;

        if (transition != null && this.transitionStarted) {
            transition.draw(surface);
        }
    }

    /**
     * Closes every scene: the modal scenes from top to bottom, the current scene, then the retained scene when it is not the
     * current one. A requested scene was never opened and is dropped.
     */
    public void close() {
        while (!this.modalScenes.isEmpty()) {
            this.modalScenes.removeLast().close();
        }

        T current = this.scene;
        T retained = this.retainedScene;

        this.clearPendingScene();

        if (current != null) {
            current.close();
        }

        if (retained != null && retained != current) {
            retained.close();
        }

        this.scene         = null;
        this.retainedScene = null;
    }
}
