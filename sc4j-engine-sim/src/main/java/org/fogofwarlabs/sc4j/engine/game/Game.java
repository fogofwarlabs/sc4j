package org.fogofwarlabs.sc4j.engine.game;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.animation.system.AnimationSystem;
import org.fogofwarlabs.sc4j.engine.collision.CollisionSystem;
import org.fogofwarlabs.sc4j.engine.combat.CombatSystem;
import org.fogofwarlabs.sc4j.engine.computer.ComputerPlayerSystem;
import org.fogofwarlabs.sc4j.engine.content.registry.ContentRegistry;
import org.fogofwarlabs.sc4j.engine.content.requirement.RequirementSystem;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.economy.EconomySystem;
import org.fogofwarlabs.sc4j.engine.entity.*;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.game.frame.FrameClock;
import org.fogofwarlabs.sc4j.engine.game.frame.FrameRunner;
import org.fogofwarlabs.sc4j.engine.game.system.GameSystem;
import org.fogofwarlabs.sc4j.engine.game.system.SystemKey;
import org.fogofwarlabs.sc4j.engine.game.system.SystemSet;
import org.fogofwarlabs.sc4j.engine.hook.HookList;
import org.fogofwarlabs.sc4j.engine.hook.HookPoint;
import org.fogofwarlabs.sc4j.engine.hook.HookTable;
import org.fogofwarlabs.sc4j.engine.lifecycle.UnitLifecycle;
import org.fogofwarlabs.sc4j.engine.movement.MovementSystem;
import org.fogofwarlabs.sc4j.engine.order.OrderSystem;
import org.fogofwarlabs.sc4j.engine.path.PathfindingSystem;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.production.ProductionSystem;
import org.fogofwarlabs.sc4j.engine.random.DeterministicRandom;
import org.fogofwarlabs.sc4j.engine.random.RandomState;
import org.fogofwarlabs.sc4j.engine.spatial.SpatialIndex;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.StateHashContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.fogofwarlabs.sc4j.engine.trigger.TriggerSystem;
import org.fogofwarlabs.sc4j.engine.visibility.VisibilitySystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds one running game: the composition root of the simulation. It owns the parts and exposes them; the rules are in
 * the systems and frame phases the mod registered.
 * <p>
 * {@link #create} builds a game in this fixed order: the random generator from the setup seed, or the setup's existing
 * generator (then the setup's saved random state, if any), the frame clock, the content tables, one {@link Player} per
 * player slot of the content, the unit, sprite, image and projectile pools with their columns (empty for a kind the mod
 * pools itself), the systems in registration order, the hooks in registration order, the frame phases, the frame runner,
 * then the attach of every system in registration order, the attach of every hook that is a system in registration
 * order, and last the sealing of the stat modifiers.
 * <p>
 * The engine systems a mod may register under their keys ({@link MovementSystem#KEY}, {@link PathfindingSystem#KEY} and the
 * others of the getters below) are resolved once, when the game is created, into fields of the game; a system keeps what it
 * needs from them in {@link GameSystem#attach}. A getter of a system the mod did not register fails.
 * <p>
 * {@link #computeStateHash()} mixes, in this order: the frame clock, the random generator, the content tables (nothing
 * for an empty content), the unit, sprite, image and projectile pools, then every system that is a
 * {@link StateHashContributor}, in registration order.
 * <p>
 * {@link #writeSnapshot} writes the state snapshot ({@link org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshot}) in the same order: the
 * frame clock, the random generator's saved state, the content tables, the four pools, then every system that is a
 * {@link SnapshotContributor}, in registration order. A system that contributes to the state hash but not to the snapshot makes the game
 * unable to write or read one.
 */
public final class Game implements SnapshotContributor {
    /**
     * Creates a {@link Game} instance from a definition and a setup, in the order of the class description, and attaches
     * its systems. The setup or definition is rejected if the setup's saved random state does not fit the generator, a phase
     * system is not registered, a player slot index is not below the content's player count or a definition refers to a type
     * that is not registered in the content.
     *
     * @param definition the definition the mod registered
     * @param setup      the inputs of this game
     * @return the created {@link Game} instance
     * @throws IllegalArgumentException if the setup or the definition is rejected
     * @throws IllegalStateException    if a system factory creates a system of another type
     */
    public static @NotNull Game create(@NotNull GameDefinition definition, @NotNull GameSetup setup) {
        Game game = new Game(definition, setup);

        game.systems.attach(game);
        game.hooks.attach(game);
        game.content.seal();

        return game;
    }

    private static <S extends GameSystem> @NotNull S requireSystem(@Nullable S system, @NotNull SystemKey<S> key) {
        if (system == null) {
            throw Exceptions.newIllegalStateException("The system {0} is not registered.", key);
        }

        return system;
    }

    /**
     * Checks that a system that writes no snapshot holds no state the snapshot would miss: it contributes nothing to the state hash.
     */
    private static void checkWithoutState(@NotNull GameSystem system) {
        if (system instanceof StateHashContributor) {
            throw Exceptions.newIllegalStateException("The system {0} contributes to the state hash but not to the state snapshot.", system);
        }
    }

    private static @NotNull StateHashContributor @NotNull [] collectContributors(@NotNull SystemSet systems) {
        List<StateHashContributor> contributors = new ArrayList<>();

        for (int index = 0; index < systems.getCount(); index++) {
            if (systems.get(index) instanceof StateHashContributor contributor) {
                contributors.add(contributor);
            }
        }

        return contributors.toArray(new StateHashContributor[0]);
    }

    /**
     * Returns the player count of the content tables: the content's fixed count, or the highest setup slot index plus one.
     *
     * @param contentPlayerCount the player count of the content, or {@link ContentRegistry#PLAYER_COUNT_FROM_SETUP}
     * @param setup              the game setup
     * @return the player count
     * @throws IllegalArgumentException if a slot index is not below the fixed count
     */
    private static int resolvePlayerCount(int contentPlayerCount, @NotNull GameSetup setup) {
        List<PlayerSlot> players = setup.getPlayers();
        int setupPlayerCount = players.isEmpty() ? 0 : players.getLast().getSlotIndex() + 1;

        if (contentPlayerCount == ContentRegistry.PLAYER_COUNT_FROM_SETUP) {
            return setupPlayerCount;
        }

        if (setupPlayerCount > contentPlayerCount) {
            throw Exceptions.newIllegalArgumentException("The player slot {0} is not below the content player count {1}.",
                                                         setupPlayerCount - 1, contentPlayerCount);
        }

        return contentPlayerCount;
    }

    private final @NotNull GameDefinition definition;
    private final @NotNull GameSetup setup;
    private final @NotNull EngineLimits limits;

    private final @NotNull DeterministicRandom random;
    private final @NotNull FrameClock clock;
    private final @NotNull GameContent content;
    private final @NotNull Player @NotNull [] players;

    private final @NotNull EntityPool<@NotNull Unit> units;
    private final @NotNull EntityPool<@NotNull Sprite> sprites;
    private final @NotNull EntityPool<@NotNull Image> images;
    private final @NotNull EntityPool<@NotNull Projectile> projectiles;

    private final @NotNull SystemSet systems;
    private final @NotNull StateHashContributor @NotNull [] systemContributors;
    private final @NotNull HookTable hooks;
    private final @NotNull UnitLifecycle unitLifecycle;
    private final @NotNull FrameRunner frameRunner;

    private final @Nullable MovementSystem movement;
    private final @Nullable PathfindingSystem pathfinding;
    private final @Nullable CollisionSystem collision;
    private final @Nullable SpatialIndex spatialIndex;
    private final @Nullable VisibilitySystem visibility;
    private final @Nullable AnimationSystem animation;
    private final @Nullable CombatSystem combat;
    private final @Nullable EconomySystem economy;
    private final @Nullable ProductionSystem production;
    private final @Nullable RequirementSystem requirements;
    private final @Nullable OrderSystem orders;
    private final @Nullable TriggerSystem triggers;
    private final @Nullable ComputerPlayerSystem computerPlayers;

    private final @NotNull StateHash stateHash;

    private Game(@NotNull GameDefinition definition, @NotNull GameSetup setup) {
        EngineLimits setupLimits = setup.getLimits();
        DeterministicRandom setupRandom = setup.getRandom();
        RandomState initialRandomState = setup.getInitialRandomState();

        this.definition = definition;
        this.setup      = setup;
        this.limits     = setupLimits != null ? setupLimits : definition.getLimits();

        this.random = setupRandom != null ? setupRandom : definition.getRandomFactory().create(setup.getSeed());
        this.clock  = new FrameClock();

        if (initialRandomState != null) {
            this.random.restoreState(initialRandomState);
        }

        this.content = GameContent.create(definition.getContent(), resolvePlayerCount(definition.getContent().getPlayerCount(), setup));
        this.players = new Player[this.content.getProgress().getPlayerCount()];

        for (int index = 0; index < this.players.length; index++) {
            this.players[index] = new Player(index);
        }

        this.units       = new EntityPool<>(this.getEnginePoolCapacity(EntityKind.UNIT), Unit::new,
                                            definition.getSlotPolicy(EntityKind.UNIT), definition.getColumns(EntityKind.UNIT));
        this.sprites     = new EntityPool<>(this.getEnginePoolCapacity(EntityKind.SPRITE), Sprite::new,
                                            definition.getSlotPolicy(EntityKind.SPRITE), definition.getColumns(EntityKind.SPRITE));
        this.images      = new EntityPool<>(this.getEnginePoolCapacity(EntityKind.IMAGE), Image::new,
                                            definition.getSlotPolicy(EntityKind.IMAGE), definition.getColumns(EntityKind.IMAGE));
        this.projectiles = new EntityPool<>(this.getEnginePoolCapacity(EntityKind.PROJECTILE), Projectile::new,
                                            definition.getSlotPolicy(EntityKind.PROJECTILE), definition.getColumns(EntityKind.PROJECTILE));

        this.systems            = definition.createSystems(setup);
        this.systemContributors = collectContributors(this.systems);
        this.hooks              = definition.createHooks(setup, this.systems);
        this.unitLifecycle      = new UnitLifecycle(this.hooks);
        this.frameRunner        = new FrameRunner(this, definition.createFramePhases(this.systems));

        this.movement        = this.systems.find(MovementSystem.KEY);
        this.pathfinding     = this.systems.find(PathfindingSystem.KEY);
        this.collision       = this.systems.find(CollisionSystem.KEY);
        this.spatialIndex    = this.systems.find(SpatialIndex.KEY);
        this.visibility      = this.systems.find(VisibilitySystem.KEY);
        this.animation       = this.systems.find(AnimationSystem.KEY);
        this.combat          = this.systems.find(CombatSystem.KEY);
        this.economy         = this.systems.find(EconomySystem.KEY);
        this.production      = this.systems.find(ProductionSystem.KEY);
        this.requirements    = this.systems.find(RequirementSystem.KEY);
        this.orders          = this.systems.find(OrderSystem.KEY);
        this.triggers        = this.systems.find(TriggerSystem.KEY);
        this.computerPlayers = this.systems.find(ComputerPlayerSystem.KEY);

        this.stateHash = new StateHash();
    }

    private int getEnginePoolCapacity(@NotNull EntityKind kind) {
        return this.definition.isModPool(kind) ? 0 : this.limits.getCapacity(kind);
    }

    /**
     * @return the definition the game was created from
     */
    public @NotNull GameDefinition getDefinition() {
        return this.definition;
    }

    /**
     * @return the inputs the game was created from
     */
    public @NotNull GameSetup getSetup() {
        return this.setup;
    }

    /**
     * @return the pool sizes of this game: the setup's limits, or the definition's when the setup sets none
     */
    public @NotNull EngineLimits getLimits() {
        return this.limits;
    }

    /**
     * @return the setups of the player slots, in ascending slot index order
     */
    public @NotNull List<@NotNull PlayerSlot> getPlayers() {
        return this.setup.getPlayers();
    }

    /**
     * @return the number of players: the player slots of the content, setup players and the slots the mod adds
     */
    public int getPlayerCount() {
        return this.players.length;
    }

    /**
     * Returns the player of a slot index. This is the decoding point for an index read from a command, a replay or a
     * saved game; every engine API names players by the returned object.
     *
     * @param index the player slot index, {@code 0} up to {@link #getPlayerCount()} minus one
     * @return the player
     * @throws IndexOutOfBoundsException if no player has the index
     */
    public @NotNull Player getPlayer(int index) {
        if (index < 0 || index >= this.players.length) {
            throw Exceptions.newIndexOutOfBoundsException("No player {0}; the game has {1}.", index, this.players.length);
        }

        return this.players[index];
    }

    /**
     * @return the random generator, the only source of random values of the simulation
     */
    public @NotNull DeterministicRandom getRandom() {
        return this.random;
    }

    /**
     * @return the frame clock: the frame counter and the paused flag
     */
    public @NotNull FrameClock getClock() {
        return this.clock;
    }

    /**
     * @return the content of this game: the per-game type tables, the player progress and the stat modifiers
     */
    public @NotNull GameContent getContent() {
        return this.content;
    }

    /**
     * @return the runner of the frames
     */
    public @NotNull FrameRunner getFrameRunner() {
        return this.frameRunner;
    }

    /**
     * @return the unit pool, empty if the mod pools its units itself
     */
    public @NotNull EntityPool<@NotNull Unit> getUnits() {
        return this.units;
    }

    /**
     * @return the sprite pool, empty if the mod pools its sprites itself
     */
    public @NotNull EntityPool<@NotNull Sprite> getSprites() {
        return this.sprites;
    }

    /**
     * @return the image pool, empty if the mod pools its images itself
     */
    public @NotNull EntityPool<@NotNull Image> getImages() {
        return this.images;
    }

    /**
     * @return the projectile pool, empty if the mod pools its projectiles itself
     */
    public @NotNull EntityPool<@NotNull Projectile> getProjectiles() {
        return this.projectiles;
    }

    /**
     * @return the systems in registration order
     */
    public @NotNull SystemSet getSystems() {
        return this.systems;
    }

    /**
     * @return the movement system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link MovementSystem#KEY}
     */
    public @NotNull MovementSystem getMovement() {
        return requireSystem(this.movement, MovementSystem.KEY);
    }

    /**
     * @return the path finding system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link PathfindingSystem#KEY}
     */
    public @NotNull PathfindingSystem getPathfinding() {
        return requireSystem(this.pathfinding, PathfindingSystem.KEY);
    }

    /**
     * @return the collision system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link CollisionSystem#KEY}
     */
    public @NotNull CollisionSystem getCollision() {
        return requireSystem(this.collision, CollisionSystem.KEY);
    }

    /**
     * @return the spatial index, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link SpatialIndex#KEY}
     */
    public @NotNull SpatialIndex getSpatialIndex() {
        return requireSystem(this.spatialIndex, SpatialIndex.KEY);
    }

    /**
     * @return the visibility system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link VisibilitySystem#KEY}
     */
    public @NotNull VisibilitySystem getVisibility() {
        return requireSystem(this.visibility, VisibilitySystem.KEY);
    }

    /**
     * @return the animation system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link AnimationSystem#KEY}
     */
    public @NotNull AnimationSystem getAnimation() {
        return requireSystem(this.animation, AnimationSystem.KEY);
    }

    /**
     * @return the combat system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link CombatSystem#KEY}
     */
    public @NotNull CombatSystem getCombat() {
        return requireSystem(this.combat, CombatSystem.KEY);
    }

    /**
     * @return the economy system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link EconomySystem#KEY}
     */
    public @NotNull EconomySystem getEconomy() {
        return requireSystem(this.economy, EconomySystem.KEY);
    }

    /**
     * @return the production system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link ProductionSystem#KEY}
     */
    public @NotNull ProductionSystem getProduction() {
        return requireSystem(this.production, ProductionSystem.KEY);
    }

    /**
     * @return the requirement system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link RequirementSystem#KEY}
     */
    public @NotNull RequirementSystem getRequirements() {
        return requireSystem(this.requirements, RequirementSystem.KEY);
    }

    /**
     * @return the order system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link OrderSystem#KEY}
     */
    public @NotNull OrderSystem getOrders() {
        return requireSystem(this.orders, OrderSystem.KEY);
    }

    /**
     * @return the trigger system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link TriggerSystem#KEY}
     */
    public @NotNull TriggerSystem getTriggers() {
        return requireSystem(this.triggers, TriggerSystem.KEY);
    }

    /**
     * @return the computer player system, resolved when the game was created
     * @throws IllegalStateException if the mod registered no system under {@link ComputerPlayerSystem#KEY}
     */
    public @NotNull ComputerPlayerSystem getComputerPlayers() {
        return requireSystem(this.computerPlayers, ComputerPlayerSystem.KEY);
    }

    /**
     * Checks whether the mod registered a system.
     *
     * @param key the key of the system
     * @return {@code true} if the key is registered, {@code false} otherwise
     */
    public boolean hasSystem(@NotNull SystemKey<?> key) {
        return this.systems.find(key) != null;
    }

    /**
     * @return the unit lifecycle of the game, which calls the hooks of its points
     */
    public @NotNull UnitLifecycle getUnitLifecycle() {
        return this.unitLifecycle;
    }

    /**
     * Returns the hooks of a point. A system calls this in {@link GameSystem#attach} and keeps the result; it is not meant
     * for the frame.
     *
     * @param <H>   the hook interface
     * @param point the hook point
     * @return the hooks of the point in registration order, empty if the mod registered none
     */
    public <H> @NotNull HookList<H> getHooks(@NotNull HookPoint<H> point) {
        return this.hooks.get(point);
    }

    /**
     * Returns a system the mod registered. A system calls this in {@link GameSystem#attach} and keeps the result; it is
     * not meant for the frame.
     *
     * @param <S> the system type
     * @param key the key of the system
     * @return the system
     * @throws IllegalArgumentException if the key is not registered
     */
    public <S extends GameSystem> @NotNull S getModSystem(@NotNull SystemKey<S> key) {
        return this.systems.get(key);
    }

    /**
     * Finds a system the mod may have registered.
     *
     * @param <S> the system type
     * @param key the key of the system
     * @return the system, or {@code null} if the key is not registered
     */
    public <S extends GameSystem> @Nullable S findModSystem(@NotNull SystemKey<S> key) {
        return this.systems.find(key);
    }


    /**
     * Computes the state hash in the order of the class description. The computation reuses one {@link StateHash} and
     * allocates nothing.
     *
     * @return the state hash
     */
    public long computeStateHash() {
        StateHash hash = this.stateHash.reset();

        this.clock.contributeStateHash(hash);
        this.random.contributeStateHash(hash);
        this.content.contributeStateHash(hash);
        this.units.contributeStateHash(hash);
        this.sprites.contributeStateHash(hash);
        this.images.contributeStateHash(hash);
        this.projectiles.contributeStateHash(hash);

        for (StateHashContributor contributor : this.systemContributors) {
            contributor.contributeStateHash(hash);
        }

        return hash.getValue();
    }

    /**
     * Writes the state of the game in the order of the class description.
     *
     * @param writer the writer of the snapshot
     * @throws IllegalStateException if a system contributes to the state hash but writes no snapshot
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        this.clock.writeSnapshot(writer);
        this.random.writeSnapshot(writer);
        this.content.writeSnapshot(writer);
        this.units.writeSnapshot(writer);
        this.sprites.writeSnapshot(writer);
        this.images.writeSnapshot(writer);
        this.projectiles.writeSnapshot(writer);

        for (int index = 0; index < this.systems.getCount(); index++) {
            GameSystem system = this.systems.get(index);

            if (system instanceof SnapshotContributor contributor) {
                contributor.writeSnapshot(writer);
            } else {
                checkWithoutState(system);
            }
        }
    }

    /**
     * Reads the state of the game back in the order of the class description, replacing the current state.
     *
     * @param reader the reader of the snapshot
     * @throws IOException           if the snapshot ends early or its values do not fit this game
     * @throws IllegalStateException if a system contributes to the state hash but reads no snapshot
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        this.clock.readSnapshot(reader);
        this.random.readSnapshot(reader);
        this.content.readSnapshot(reader);
        this.units.readSnapshot(reader);
        this.sprites.readSnapshot(reader);
        this.images.readSnapshot(reader);
        this.projectiles.readSnapshot(reader);

        for (int index = 0; index < this.systems.getCount(); index++) {
            GameSystem system = this.systems.get(index);

            if (system instanceof SnapshotContributor contributor) {
                contributor.readSnapshot(reader);
            } else {
                checkWithoutState(system);
            }
        }
    }
}
