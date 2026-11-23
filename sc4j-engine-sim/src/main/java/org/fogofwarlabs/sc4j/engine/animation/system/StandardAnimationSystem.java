package org.fogofwarlabs.sc4j.engine.animation.system;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.animation.AnimationEffects;
import org.fogofwarlabs.sc4j.engine.animation.AnimationKind;
import org.fogofwarlabs.sc4j.engine.animation.AnimationLibrary;
import org.fogofwarlabs.sc4j.engine.animation.AnimationTarget;
import org.fogofwarlabs.sc4j.engine.content.type.ImageType;
import org.fogofwarlabs.sc4j.engine.entity.Image;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.entity.pool.SlotList;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.StateHashContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Runs the animation programs of the engine image pool. It is the engine default {@link AnimationSystem} and its own
 * frame phase: a mod registers it under {@link AnimationSystem#KEY} and adds the phase where the animations run in its
 * frame.
 * {@snippet :
 * builder.useSystem(AnimationSystem.KEY, context -> new StandardAnimationSystem(library, effects, ANIMATION_SOURCE, 1));
 * builder.addFramePhase(AnimationSystem.KEY);
 *}
 * <p>
 * A shown frame sets {@link Image#setFrameIndex}. The state holds one entry per image index ({@code 0} unused) and is
 * created when the system is attached. A system that allocates an image starts its animation ({@link #play}) or stops it
 * ({@link #stop}) before the image's first frame, because a reused slot keeps the running animation of its previous
 * use.
 * <p>
 * Determinism contract: the phase updates the used image slots in pool order, taking the next slot before each update,
 * so an update may free its own image; the system mixes the whole animation state into the state hash.
 */
public final class StandardAnimationSystem implements AnimationSystem, StateHashContributor, SnapshotContributor {
    /**
     * Shows frames on the images of the engine pool.
     */
    private static final class ImageTarget implements AnimationTarget {
        /**
         * Shows a frame on an animated image.
         *
         * @param image the animated image
         * @param frame the frame number of the animation; how it maps to a picture (direction, mirroring) is up to the owner
         */
        @Override
        public void showFrame(@NotNull Image image, int frame) {
            image.setFrameIndex(frame);
        }
    }

    private final @NotNull AnimationLibrary library;
    private final @NotNull AnimationEffects effects;
    private final int randomSource;
    private final int callStackCapacity;

    private @Nullable EntityPool<@NotNull Image> images;
    private @Nullable AnimationRunner runner;

    /**
     * Creates a {@link StandardAnimationSystem} instance for one game.
     *
     * @param library           the animation library, shared by the games
     * @param effects           the effects of this game
     * @param randomSource      the call source of every animation draw
     * @param callStackCapacity the number of return positions each call stack holds, at least {@code 0}
     * @throws IllegalArgumentException if the call stack capacity is negative
     */
    public StandardAnimationSystem(@NotNull AnimationLibrary library, @NotNull AnimationEffects effects, int randomSource, int callStackCapacity) {
        if (callStackCapacity < 0) {
            throw Exceptions.newIllegalArgumentException("The call stack capacity must not be negative: {0}", callStackCapacity);
        }

        this.library           = library;
        this.effects           = effects;
        this.randomSource      = randomSource;
        this.callStackCapacity = callStackCapacity;

        this.images = null;
        this.runner = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Creates the animation state for the image pool and the runner with the game generator.
     *
     * @param game the game
     * @throws IllegalArgumentException if the random source is out of the generator's range
     */
    @Override
    public void attach(@NotNull Game game) {
        EntityPool<Image> pool = game.getImages();
        AnimationState state = new AnimationState(pool.getCapacity() + 1, this.callStackCapacity);

        this.images = pool;
        this.runner = new AnimationRunner(state, new ImageTarget(), this.effects,
                                          game.getRandom(), this.randomSource, AnimationRunner.DEFAULT_INSTRUCTION_LIMIT);
    }

    /**
     * @return the animation library of the game
     */
    @Override
    public @NotNull AnimationLibrary getLibrary() {
        return this.library;
    }

    /**
     * @return the running animation of every image, indexed by the image index
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public @NotNull AnimationState getState() {
        return this.requireRunner().getState();
    }


    /**
     * Starts an animation of an image's set; the first instruction runs in the next update of the image.
     *
     * @param image the allocated image
     * @param kind  the kind
     * @return {@code true} if the image's set has the animation and it was started, {@code false} otherwise
     * @throws IllegalStateException     if the system is not attached or the image has no type
     * @throws IndexOutOfBoundsException if the image type index names no animation set
     */
    @Override
    public boolean play(@NotNull Image image, @NotNull AnimationKind kind) {
        ImageType type = image.getType();

        if (type == null) {
            throw Exceptions.newIllegalStateException("The image {0} has no type.", image.getIndex());
        }

        return this.requireRunner().start(image, this.library.getSet(type.getIndex()), kind);
    }

    /**
     * Stops the animation of an image; it keeps the frame it shows.
     *
     * @param image the image
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void stop(@NotNull Image image) {
        this.requireRunner().getState().setRunning(image.getIndex(), false);
    }

    /**
     * Checks whether an image runs its animation program.
     *
     * @param image the image
     * @return {@code true} if the program runs, {@code false} if it ended, was stopped or never started
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean isPlaying(@NotNull Image image) {
        return this.requireRunner().getState().isRunning(image.getIndex());
    }

    /**
     * Updates the animation of one image outside the frame phase, for example right after it is started.
     *
     * @param image the image
     * @throws IllegalStateException if the system is not attached, the update reaches the instruction limit or the end of the program, or an instruction fails
     */
    @Override
    public void update(@NotNull Image image) {
        this.requireRunner().update(image);
    }

    /**
     * Runs this phase of the current frame.
     * <p>
     * Updates every used image slot in pool order.
     *
     * @param game the game
     * @throws IllegalStateException if the system is not attached, or an update fails
     */
    @Override
    public void run(@NotNull Game game) {
        AnimationRunner animationRunner = this.requireRunner();
        EntityPool<Image> pool = this.images;

        if (pool == null) {
            throw Exceptions.newIllegalStateException("The animation system is not attached.");
        }

        SlotList used = pool.getUsedSlots();
        int index = used.getFirst();

        while (index != SlotList.NONE) {
            int next = used.getNext(index);

            animationRunner.update(pool.get(index));

            index = next;
        }
    }

    /**
     * Mixes this part of the simulation state into the hash: the animation state of every image index.
     *
     * @param hash the hash being computed
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        this.requireRunner().getState().contributeStateHash(hash);
    }

    /**
     * Writes this part of the simulation state: the running animation of every image ({@link AnimationState#writeSnapshot}).
     *
     * @param writer the writer of the snapshot
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        this.requireRunner().getState().writeSnapshot(writer);
    }

    /**
     * Reads this part of the simulation state back, replacing the current values.
     *
     * @param reader the reader of the snapshot
     * @throws IOException           if the snapshot ends early, holds another number of images or names no set or program of the library
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        this.requireRunner().getState().readSnapshot(reader, this.library);
    }

    private @NotNull AnimationRunner requireRunner() {
        AnimationRunner attached = this.runner;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The animation system is not attached.");
        }

        return attached;
    }
}
