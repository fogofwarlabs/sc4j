package org.fogofwarlabs.sc4j.runtime.session;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.client.app.FirstSceneFactory;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.mod.HeadlessRunner;
import org.fogofwarlabs.sc4j.engine.mod.ModApplication;
import org.fogofwarlabs.sc4j.engine.mod.ModLaunch;
import org.fogofwarlabs.sc4j.engine.mod.ModRegistration;
import org.fogofwarlabs.sc4j.engine.replay.EngineReplayFormat;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormatFactory;
import org.fogofwarlabs.sc4j.engine.resource.ResourceProvider;
import org.fogofwarlabs.sc4j.engine.save.EngineSaveFormat;
import org.fogofwarlabs.sc4j.engine.save.SaveFormatFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects what a mod registers during {@code Mod.register} until the session closes the registration.
 */
final class RuntimeModRegistration implements ModRegistration {
    private @Nullable ModApplication application;
    private @Nullable FirstSceneFactory firstScene;
    private @Nullable HeadlessRunner headlessRunner;

    private final @NotNull List<byte @NotNull []> identityData;
    private final GameDefinition.@NotNull Builder simulation;
    private @Nullable ResourceProvider resourceProvider;
    private final @NotNull List<@NotNull ReplayFormatFactory> replayFormats;
    private final @NotNull List<@NotNull SaveFormatFactory> saveFormats;

    private boolean closed;

    /**
     * Creates an open {@link RuntimeModRegistration} instance without registrations.
     */
    RuntimeModRegistration() {
        this.application    = null;
        this.firstScene     = null;
        this.headlessRunner = null;

        this.identityData     = new ArrayList<>();
        this.simulation       = new GameDefinition.Builder();
        this.resourceProvider = null;
        this.replayFormats    = new ArrayList<>();
        this.saveFormats      = new ArrayList<>();

        this.closed = false;
    }

    /**
     * @return the registered application, or {@code null} when the mod registered none
     */
    @Nullable ModApplication getApplication() {
        return this.application;
    }

    /**
     * @return the registered factory of the first scene, or {@code null} when the mod registered none
     */
    @Nullable FirstSceneFactory getFirstScene() {
        return this.firstScene;
    }

    /**
     * @return the registered headless runner, or {@code null} when the mod registered none
     */
    @Nullable HeadlessRunner getHeadlessRunner() {
        return this.headlessRunner;
    }

    /**
     * @return the identity data in the order the mod added them
     */
    @NotNull List<byte @NotNull []> getIdentityData() {
        return List.copyOf(this.identityData);
    }

    /**
     * @return the registered resource provider, or {@code null} when the mod registered none
     */
    @Nullable ResourceProvider getResourceProvider() {
        return this.resourceProvider;
    }

    /**
     * @return the factories of the mod's replay formats in registration order
     */
    @NotNull List<@NotNull ReplayFormatFactory> getReplayFormats() {
        return List.copyOf(this.replayFormats);
    }

    /**
     * @return the factories of the mod's save formats in registration order
     */
    @NotNull List<@NotNull SaveFormatFactory> getSaveFormats() {
        return List.copyOf(this.saveFormats);
    }


    /**
     * Registers the application the runtime runs after the registration is closed, for a mod with its own application loop. A
     * mod registers at most one, and then neither a first scene nor a headless runner.
     *
     * @param application the application
     * @throws IllegalStateException if the registration is closed, or an application, a first scene or a headless runner is already registered
     */
    @Override
    public void registerApplication(@NotNull ModApplication application) {
        this.requireOpen();

        if (this.application != null) {
            throw Exceptions.newIllegalStateException("The mod already registered an application.");
        }

        if (this.firstScene != null || this.headlessRunner != null) {
            throw Exceptions.newIllegalStateException("The mod registered its first scene or headless runner; it runs no own application.");
        }

        this.application = application;
    }

    /**
     * Registers the factory of the first scene of the mod's client: the runtime opens the window and the devices, creates the scene and
     * runs its own main loop ({@link org.fogofwarlabs.sc4j.engine.client.app.WindowClient}). A mod registers at most one.
     *
     * @param factory the factory of the first scene
     * @throws IllegalStateException if the registration is closed, or a first scene or an application is already registered
     */
    @Override
    public void registerFirstScene(@NotNull FirstSceneFactory factory) {
        this.requireOpen();

        if (this.firstScene != null) {
            throw Exceptions.newIllegalStateException("The mod already registered its first scene.");
        }

        this.requireNoApplication();
        this.firstScene = factory;
    }

    /**
     * Registers the runner of the mod's {@code --headless} runs. A mod registers at most one; without one it cannot run headless.
     *
     * @param runner the runner
     * @throws IllegalStateException if the registration is closed, or a headless runner or an application is already registered
     */
    @Override
    public void registerHeadlessRunner(@NotNull HeadlessRunner runner) {
        this.requireOpen();

        if (this.headlessRunner != null) {
            throw Exceptions.newIllegalStateException("The mod already registered a headless runner.");
        }

        this.requireNoApplication();
        this.headlessRunner = runner;
    }

    /**
     * Adds data to the mod's identity, for example the build number of external data the mod checked. Two runs connect,
     * replay and compare only when their identities are equal; what to add is the mod's choice.
     *
     * @param data the data, copied
     * @throws IllegalStateException if the registration is closed
     */
    @Override
    public void addIdentityData(byte @NotNull [] data) {
        this.requireOpen();
        this.identityData.add(data.clone());
    }

    /**
     * Registers the provider of the mod's resources; the runtime hands it to the mod's run ({@link ModLaunch#getResourceProvider()}). A mod
     * registers at most one.
     *
     * @param provider the provider
     * @throws IllegalStateException if the registration is closed or a provider is already registered
     */
    @Override
    public void registerResourceProvider(@NotNull ResourceProvider provider) {
        this.requireOpen();

        if (this.resourceProvider != null) {
            throw Exceptions.newIllegalStateException("The mod already registered a resource provider.");
        }

        this.resourceProvider = provider;
    }

    /**
     * Registers a format of the mod's replays: the engine replay format ({@link EngineReplayFormat}) for a mod without its own, or the mod's
     * own. The runtime creates the format with the run's identity once the registration is closed and hands it to the mod's run
     * ({@link ModLaunch#getReplayFormats()}, in registration order); it finds the format of a {@code --replay} file among them. A mod without
     * replay format plays no replay from the command line.
     *
     * @param factory the factory of the format
     * @throws IllegalStateException if the registration is closed
     */
    @Override
    public void registerReplayFormat(@NotNull ReplayFormatFactory factory) {
        this.requireOpen();
        this.replayFormats.add(factory);
    }

    /**
     * Registers a format of the mod's saved games: the engine save format ({@link EngineSaveFormat}) for a mod without its own, or the mod's
     * own (one per kind of saved game). The runtime creates the format with the run's identity once the registration is closed and hands it
     * to the mod's run ({@link ModLaunch#getSaveFormats()}, in registration order).
     *
     * @param factory the factory of the format
     * @throws IllegalStateException if the registration is closed
     */
    @Override
    public void registerSaveFormat(@NotNull SaveFormatFactory factory) {
        this.requireOpen();
        this.saveFormats.add(factory);
    }

    /**
     * Returns the definition of the mod's games, open while the registration is open: the mod sets its random generator,
     * limits, slot policies, extension columns, systems and frame phases on it. The runtime builds the definition when
     * the registration is closed and hands it to the mod's run ({@link ModLaunch#getGameDefinition()}).
     *
     * @return the open definition builder of the mod's games
     * @throws IllegalStateException if the registration is closed
     */
    @Override
    public GameDefinition.@NotNull Builder getSimulation() {
        this.requireOpen();

        return this.simulation;
    }

    /**
     * Builds the definition of the mod's games from what the mod registered; the first call closes the simulation
     * registration.
     *
     * @return the definition
     * @throws IllegalStateException if a frame phase names a system the mod did not register
     */
    @NotNull GameDefinition buildGameDefinition() {
        return this.simulation.build();
    }

    private void requireNoApplication() {
        if (this.application != null) {
            throw Exceptions.newIllegalStateException("The mod registered its own application; it registers no first scene or headless runner.");
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw Exceptions.newIllegalStateException("The mod registration is closed.");
        }
    }


    /**
     * Closes the registration: later registrations fail.
     */
    void close() {
        this.closed = true;
    }
}
