package org.fogofwarlabs.sc4j.engine.content.registry;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.combat.DamageKind;
import org.fogofwarlabs.sc4j.engine.content.definition.*;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceKind;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.faction.FactionDefinition;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.content.modifier.StatModifier;
import org.fogofwarlabs.sc4j.engine.content.type.*;
import org.fogofwarlabs.sc4j.engine.entity.column.Column;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Receives the content a mod registers for its games: the type identities with their definitions, the factions, the
 * resource kinds, the stats, the extension columns of the type tables and the stat modifiers. A mod reaches it through
 * {@code ModRegistration.getContent()}; {@link #build()} freezes every registry and closes the registration.
 * <p>
 * Every content starts with the engine stats registered ({@link Stat#getEngineStats()}). The mod registers each kind in
 * a fixed order, so the registration-order indices are the same on every machine.
 */
public final class ContentRegistry {
    /**
     * The player count of a content that takes the number of player slots from the game setup.
     */
    public static final int PLAYER_COUNT_FROM_SETUP = 0;

    private static <C extends Column> void addColumnTo(@NotNull List<@NotNull TypeColumn<?>> tableColumns, @NotNull TableKind table,
                                                       @NotNull ColumnKey<C> key, @NotNull TypeColumnInitializer<? super C> initializer) {
        for (TypeColumn<?> added : tableColumns) {
            if (added.getKey() == key) {
                throw Exceptions.newIllegalArgumentException("The column {0} is already added to the {1} table.", key, table);
            }
        }

        tableColumns.add(new TypeColumn<>(key, initializer));
    }

    private final @NotNull DefinitionRegistry<UnitType, UnitDefinition> unitTypes;
    private final @NotNull DefinitionRegistry<WeaponType, WeaponDefinition> weaponTypes;
    private final @NotNull DefinitionRegistry<MotionProfile, MotionProfileDefinition> motionProfiles;
    private final @NotNull DefinitionRegistry<SpriteType, SpriteDefinition> spriteTypes;
    private final @NotNull DefinitionRegistry<ImageType, ImageDefinition> imageTypes;
    private final @NotNull DefinitionRegistry<UpgradeType, UpgradeDefinition> upgradeTypes;
    private final @NotNull DefinitionRegistry<TechType, TechDefinition> techTypes;
    private final @NotNull DefinitionRegistry<OrderType, OrderDefinition> orderTypes;
    private final @NotNull DefinitionRegistry<SoundType, SoundDefinition> soundTypes;
    private final @NotNull DefinitionRegistry<Faction, FactionDefinition> factions;
    private final @NotNull TypeRegistry<ResourceKind> resourceKinds;
    private final @NotNull TypeRegistry<DamageKind> damageKinds;
    private final @NotNull TypeRegistry<Stat> stats;

    private final @NotNull List<@NotNull List<@NotNull TypeColumn<?>>> columns;
    private final @NotNull List<@NotNull List<@NotNull TypeColumn<?>>> playerColumns;
    private final @NotNull List<@NotNull StatModifier @NotNull []> modifiers;
    private int playerCount;

    private @Nullable ContentDefinition definition;

    /**
     * Creates an open {@link ContentRegistry} instance with the engine stats and nothing else registered, taking the
     * player count from the game setup.
     */
    public ContentRegistry() {
        TableKind[] tableKinds = TableKind.values();

        this.unitTypes      = new DefinitionRegistry<>("unit type");
        this.weaponTypes    = new DefinitionRegistry<>("weapon type");
        this.motionProfiles = new DefinitionRegistry<>("motion profile");
        this.spriteTypes    = new DefinitionRegistry<>("sprite type");
        this.imageTypes     = new DefinitionRegistry<>("image type");
        this.upgradeTypes   = new DefinitionRegistry<>("upgrade type");
        this.techTypes      = new DefinitionRegistry<>("tech type");
        this.orderTypes     = new DefinitionRegistry<>("order type");
        this.soundTypes     = new DefinitionRegistry<>("sound type");
        this.factions       = new DefinitionRegistry<>("faction");
        this.resourceKinds  = new TypeRegistry<>("resource kind");
        this.damageKinds    = new TypeRegistry<>("damage kind");
        this.stats          = new TypeRegistry<>("stat");

        this.columns       = new ArrayList<>(tableKinds.length);
        this.playerColumns = new ArrayList<>(tableKinds.length);
        this.modifiers     = new ArrayList<>();
        this.playerCount   = PLAYER_COUNT_FROM_SETUP;

        this.definition = null;

        for (TableKind ignored : tableKinds) {
            this.columns.add(new ArrayList<>());
            this.playerColumns.add(new ArrayList<>());
        }

        for (Stat stat : Stat.getEngineStats()) {
            this.stats.register(stat);
        }
    }

    /**
     * @return the unit types and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<UnitType, UnitDefinition> getUnitTypes() {
        this.requireOpen();

        return this.unitTypes;
    }

    /**
     * @return the weapon types and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<WeaponType, WeaponDefinition> getWeaponTypes() {
        this.requireOpen();

        return this.weaponTypes;
    }

    /**
     * @return the motion profiles and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<MotionProfile, MotionProfileDefinition> getMotionProfiles() {
        this.requireOpen();

        return this.motionProfiles;
    }

    /**
     * @return the sprite types and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<SpriteType, SpriteDefinition> getSpriteTypes() {
        this.requireOpen();

        return this.spriteTypes;
    }

    /**
     * @return the image types and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<ImageType, ImageDefinition> getImageTypes() {
        this.requireOpen();

        return this.imageTypes;
    }

    /**
     * @return the upgrade types and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<UpgradeType, UpgradeDefinition> getUpgradeTypes() {
        this.requireOpen();

        return this.upgradeTypes;
    }

    /**
     * @return the tech types and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<TechType, TechDefinition> getTechTypes() {
        this.requireOpen();

        return this.techTypes;
    }

    /**
     * @return the order types and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<OrderType, OrderDefinition> getOrderTypes() {
        this.requireOpen();

        return this.orderTypes;
    }

    /**
     * @return the sound types and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<SoundType, SoundDefinition> getSoundTypes() {
        this.requireOpen();

        return this.soundTypes;
    }

    /**
     * @return the factions and their definitions
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull DefinitionRegistry<Faction, FactionDefinition> getFactions() {
        this.requireOpen();

        return this.factions;
    }

    /**
     * @return the resource kinds
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull TypeRegistry<ResourceKind> getResourceKinds() {
        this.requireOpen();

        return this.resourceKinds;
    }

    /**
     * @return the damage kinds of the weapon types
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull TypeRegistry<DamageKind> getDamageKinds() {
        this.requireOpen();

        return this.damageKinds;
    }

    /**
     * Returns the stats: the engine stats first, then the stats of the mod. A stat registered here can be given
     * modifiers.
     *
     * @return the stats
     * @throws IllegalStateException if the registration is closed
     */
    public @NotNull TypeRegistry<Stat> getStats() {
        this.requireOpen();

        return this.stats;
    }

    /**
     * Sets the number of players the per-player tables hold, for a mod whose rules use a fixed number of player slots;
     * every slot index of a game setup must be below it.
     *
     * @param playerCount the player count, at least {@code 1}, or {@link #PLAYER_COUNT_FROM_SETUP} to use the highest slot index of each game setup plus one
     * @throws IllegalArgumentException if the count is negative
     * @throws IllegalStateException    if the registration is closed
     */
    public void setPlayerCount(int playerCount) {
        this.requireOpen();

        if (playerCount < 0) {
            throw Exceptions.newIllegalArgumentException("Invalid player count: {0}", playerCount);
        }

        this.playerCount = playerCount;
    }


    /**
     * Adds an extension column to a type table; columns are created and hashed in the order they are added.
     *
     * @param <C>         the column type
     * @param table       the type table
     * @param key         the column key
     * @param initializer sets the initial values of each new table
     * @throws IllegalArgumentException if the key is already added to the table
     * @throws IllegalStateException    if the registration is closed
     */
    public <C extends Column> void addColumn(@NotNull TableKind table,
                                             @NotNull ColumnKey<C> key,
                                             @NotNull TypeColumnInitializer<? super C> initializer) {
        this.requireOpen();

        addColumnTo(this.columns.get(table.ordinal()), table, key, initializer);
    }

    /**
     * Adds a per-player extension column for the types of a table to the player progress table: one value per player and
     * type, player-major ({@code player * typeCount + typeIndex}); columns are created and hashed in the order they are
     * added.
     *
     * @param <C>         the column type
     * @param table       the type table whose types index the column
     * @param key         the column key
     * @param initializer sets the initial values of each new progress table; the column length is the player count times the type count
     * @throws IllegalArgumentException if the key is already added for the table
     * @throws IllegalStateException    if the registration is closed
     */
    public <C extends Column> void addPlayerColumn(@NotNull TableKind table,
                                                   @NotNull ColumnKey<C> key,
                                                   @NotNull TypeColumnInitializer<? super C> initializer) {
        this.requireOpen();

        addColumnTo(this.playerColumns.get(table.ordinal()), table, key, initializer);
    }

    /**
     * Adds a modifier to a stat; the modifiers of a stat are applied in the order they are added.
     *
     * @param stat     the stat
     * @param modifier the modifier
     * @throws IllegalArgumentException if the stat is not registered
     * @throws IllegalStateException    if the registration is closed
     */
    public void addModifier(@NotNull Stat stat, @NotNull StatModifier modifier) {
        this.requireOpen();

        if (!this.stats.contains(stat)) {
            throw Exceptions.newIllegalArgumentException("The stat {0} is not registered.", stat);
        }

        this.addMissingModifierLists();

        StatModifier[] statModifiers = this.modifiers.get(stat.getIndex());
        StatModifier[] extended = new StatModifier[statModifiers.length + 1];

        System.arraycopy(statModifiers, 0, extended, 0, statModifiers.length);
        extended[statModifiers.length] = modifier;

        this.modifiers.set(stat.getIndex(), extended);
    }

    /**
     * Freezes every registry and builds the {@link ContentDefinition} instance; the first call closes the registration
     * and later calls return the same instance.
     *
     * @return the built {@link ContentDefinition} instance
     */
    public @NotNull ContentDefinition build() {
        ContentDefinition built = this.definition;

        if (built != null) {
            return built;
        }

        this.unitTypes.freeze();
        this.weaponTypes.freeze();
        this.motionProfiles.freeze();
        this.spriteTypes.freeze();
        this.imageTypes.freeze();
        this.upgradeTypes.freeze();
        this.techTypes.freeze();
        this.orderTypes.freeze();
        this.soundTypes.freeze();
        this.factions.freeze();
        this.resourceKinds.freeze();
        this.damageKinds.freeze();
        this.stats.freeze();

        this.addMissingModifierLists();

        List<List<TypeColumn<?>>> frozenColumns = new ArrayList<>(this.columns.size());
        List<List<TypeColumn<?>>> frozenPlayerColumns = new ArrayList<>(this.playerColumns.size());

        for (List<TypeColumn<?>> tableColumns : this.columns) {
            frozenColumns.add(List.copyOf(tableColumns));
        }

        for (List<TypeColumn<?>> tableColumns : this.playerColumns) {
            frozenPlayerColumns.add(List.copyOf(tableColumns));
        }

        built = new ContentDefinition(this.unitTypes, this.weaponTypes, this.motionProfiles, this.spriteTypes, this.imageTypes, this.upgradeTypes,
                                      this.techTypes, this.orderTypes, this.soundTypes,
                                      this.factions, this.resourceKinds, this.damageKinds, this.stats,
                                      List.copyOf(frozenColumns), List.copyOf(frozenPlayerColumns), this.modifiers.toArray(new StatModifier[0][]),
                                      this.playerCount);

        this.definition = built;

        return built;
    }

    private void addMissingModifierLists() {
        while (this.modifiers.size() < this.stats.getCount()) {
            this.modifiers.add(new StatModifier[0]);
        }
    }

    private void requireOpen() {
        if (this.definition != null) {
            throw Exceptions.newIllegalStateException("The content is built; the registration is closed.");
        }
    }
}
