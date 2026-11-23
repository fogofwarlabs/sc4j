package org.fogofwarlabs.sc4j.engine.mod;

import org.fogofwarlabs.sc4j.engine.client.app.FirstSceneFactory;
import org.fogofwarlabs.sc4j.engine.content.registry.ContentRegistry;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.replay.EngineReplayFormat;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormatFactory;
import org.fogofwarlabs.sc4j.engine.resource.ResourceProvider;
import org.fogofwarlabs.sc4j.engine.save.EngineSaveFormat;
import org.fogofwarlabs.sc4j.engine.save.SaveFormatFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Receives what a mod adds to the engine during {@link Mod#register}. The runtime closes the registration when
 * {@link Mod#register} returns; later calls fail.
 * <p>
 * The mod registers its simulation ({@link #getSimulation()}: random generator, limits, systems, frame phases and
 * extension columns), its content ({@link #getContent()}: types, definitions, table columns and modifiers), how it runs, the
 * {@link ResourceProvider} of its resources, the formats of its replays and saved games, and may add data to its identity.
 * <p>
 * How it runs: an engine-only mod registers the first scene of its client ({@link #registerFirstScene}), which the runtime opens on its
 * own window and main loop, and a {@link HeadlessRunner} for {@code --headless} runs ({@link #registerHeadlessRunner}). A mod with its own
 * application loop registers a {@link ModApplication} instead ({@link #registerApplication}); a mod registers either the application or the
 * first scene and headless runner, not both.
 */
public interface ModRegistration {
    /**
     * Registers the application the runtime runs after the registration is closed, for a mod with its own application loop. A
     * mod registers at most one, and then neither a first scene nor a headless runner.
     *
     * @param application the application
     * @throws IllegalStateException if the registration is closed, or an application, a first scene or a headless runner is already registered
     */
    void registerApplication(@NotNull ModApplication application);

    /**
     * Registers the factory of the first scene of the mod's client: the runtime opens the window and the devices, creates the scene and
     * runs its own main loop ({@link org.fogofwarlabs.sc4j.engine.client.app.WindowClient}). A mod registers at most one.
     *
     * @param factory the factory of the first scene
     * @throws IllegalStateException if the registration is closed, or a first scene or an application is already registered
     */
    void registerFirstScene(@NotNull FirstSceneFactory factory);

    /**
     * Registers the runner of the mod's {@code --headless} runs. A mod registers at most one; without one it cannot run headless.
     *
     * @param runner the runner
     * @throws IllegalStateException if the registration is closed, or a headless runner or an application is already registered
     */
    void registerHeadlessRunner(@NotNull HeadlessRunner runner);

    /**
     * Adds data to the mod's identity, for example the build number of external data the mod checked. Two runs connect,
     * replay and compare only when their identities are equal; what to add is the mod's choice.
     *
     * @param data the data, copied
     * @throws IllegalStateException if the registration is closed
     */
    void addIdentityData(byte @NotNull [] data);

    /**
     * Registers the provider of the mod's resources; the runtime hands it to the mod's run ({@link ModLaunch#getResourceProvider()}). A mod
     * registers at most one.
     *
     * @param provider the provider
     * @throws IllegalStateException if the registration is closed or a provider is already registered
     */
    void registerResourceProvider(@NotNull ResourceProvider provider);

    /**
     * Registers a format of the mod's replays: the engine replay format ({@link EngineReplayFormat}) for a mod without its own, or the mod's
     * own. The runtime creates the format with the run's identity once the registration is closed and hands it to the mod's run
     * ({@link ModLaunch#getReplayFormats()}, in registration order); it finds the format of a {@code --replay} file among them. A mod without
     * replay format plays no replay from the command line.
     *
     * @param factory the factory of the format
     * @throws IllegalStateException if the registration is closed
     */
    void registerReplayFormat(@NotNull ReplayFormatFactory factory);

    /**
     * Registers a format of the mod's saved games: the engine save format ({@link EngineSaveFormat}) for a mod without its own, or the mod's
     * own (one per kind of saved game). The runtime creates the format with the run's identity once the registration is closed and hands it
     * to the mod's run ({@link ModLaunch#getSaveFormats()}, in registration order).
     *
     * @param factory the factory of the format
     * @throws IllegalStateException if the registration is closed
     */
    void registerSaveFormat(@NotNull SaveFormatFactory factory);

    /**
     * Returns the definition of the mod's games, open while the registration is open: the mod sets its random generator,
     * limits, slot policies, extension columns, systems and frame phases on it. The runtime builds the definition when
     * the registration is closed and hands it to the mod's run ({@link ModLaunch#getGameDefinition()}).
     *
     * @return the open definition builder of the mod's games
     * @throws IllegalStateException if the registration is closed
     */
    GameDefinition.@NotNull Builder getSimulation();

    /**
     * Returns the content of the mod's games, part of the simulation: the mod registers its types with their
     * definitions, the extension columns of the type tables and the stat modifiers, in a fixed order.
     *
     * @return the open content registry of the mod's games
     * @throws IllegalStateException if the registration is closed
     */
    default @NotNull ContentRegistry getContent() {
        return this.getSimulation().getContent();
    }
}
