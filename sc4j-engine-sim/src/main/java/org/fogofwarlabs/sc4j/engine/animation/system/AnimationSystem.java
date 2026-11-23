package org.fogofwarlabs.sc4j.engine.animation.system;

import org.fogofwarlabs.sc4j.engine.animation.AnimationKind;
import org.fogofwarlabs.sc4j.engine.animation.AnimationLibrary;
import org.fogofwarlabs.sc4j.engine.entity.Image;
import org.fogofwarlabs.sc4j.engine.game.frame.FramePhase;
import org.fogofwarlabs.sc4j.engine.game.system.GameSystem;
import org.fogofwarlabs.sc4j.engine.game.system.SystemKey;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the animation programs of the images of a game; the system is its own frame phase, which updates every image once.
 * The engine default is {@link StandardAnimationSystem}; a mod replaces the whole system by registering another
 * implementation under {@link #KEY}.
 * <p>
 * The animation set of an image is the set of its image type: {@link AnimationLibrary#getSet} of the index of
 * {@link Image#getType()}.
 * <p>
 * Determinism contract: images are updated once per running frame, in pool order of the used image slots, after the
 * phases registered before the animation phase; random values come only from the game generator with the system's one
 * random source; starting and stopping an animation draws nothing and runs no instruction.
 */
public interface AnimationSystem extends GameSystem, FramePhase {
    /**
     * The key of the animation system.
     */
    @NotNull SystemKey<AnimationSystem> KEY = SystemKey.of("animation", AnimationSystem.class);

    /**
     * @return the animation library of the game
     */
    @NotNull AnimationLibrary getLibrary();

    /**
     * @return the running animation of every image, indexed by the image index
     */
    @NotNull AnimationState getState();

    /**
     * Starts an animation of an image's set; the first instruction runs in the next update of the image.
     *
     * @param image the allocated image
     * @param kind  the kind
     * @return {@code true} if the image's set has the animation and it was started, {@code false} otherwise
     */
    boolean play(@NotNull Image image, @NotNull AnimationKind kind);

    /**
     * Stops the animation of an image; it keeps the frame it shows.
     *
     * @param image the image
     */
    void stop(@NotNull Image image);

    /**
     * Checks whether an image runs its animation program.
     *
     * @param image the image
     * @return {@code true} if the program runs, {@code false} if it ended, was stopped or never started
     */
    boolean isPlaying(@NotNull Image image);

    /**
     * Updates the animation of one image outside the frame phase, for example right after it is started.
     *
     * @param image the image
     * @throws IllegalStateException if the update reaches the instruction limit or the end of the program, or an instruction fails
     */
    void update(@NotNull Image image);
}
