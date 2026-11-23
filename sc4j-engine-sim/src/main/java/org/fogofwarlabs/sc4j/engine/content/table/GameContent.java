package org.fogofwarlabs.sc4j.engine.content.table;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.content.modifier.StatModifier;
import org.fogofwarlabs.sc4j.engine.content.registry.ContentDefinition;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.content.type.WeaponType;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.StateHashContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.SnapshotContributor;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the content of one game: the per-game type tables, the player progress table, the stat modifiers, created
 * from the content definition when the game is created, and the damage layers of each unit type, which the game's systems add.
 * <p>
 * Determinism contract: the tables are created in {@link org.fogofwarlabs.sc4j.engine.content.registry.TableKind} order
 * and then the progress table; {@link #contributeStateHash} mixes them in the same order. An empty content mixes
 * nothing. {@link #applyModifiers} applies the modifiers of a stat in registration order and allocates nothing: first the
 * modifiers of the definition, then the modifiers of this game ({@link #addModifier}), which its systems add while the game
 * is created. The damage layers of a unit type ({@link #getDamageLayers}) keep the order they were added in.
 */
public final class GameContent implements StateHashContributor, SnapshotContributor {
    /**
     * Creates a {@link GameContent} instance: every table from the definitions, in the order of the class description.
     *
     * @param definition  the content definition
     * @param playerCount the number of players of the progress table
     * @return the created {@link GameContent} instance
     * @throws IllegalArgumentException if the player count is negative or a definition refers to a type that is not registered in the content
     */
    public static @NotNull GameContent create(@NotNull ContentDefinition definition, int playerCount) {
        if (playerCount < 0) {
            throw Exceptions.newIllegalArgumentException("Invalid player count: {0}", playerCount);
        }

        return new GameContent(definition, playerCount);
    }

    private final @NotNull ContentDefinition definition;

    private final @NotNull UnitTypeTable unitTypes;
    private final @NotNull WeaponTypeTable weaponTypes;
    private final @NotNull MotionProfileTable motionProfiles;
    private final @NotNull SpriteTypeTable spriteTypes;
    private final @NotNull ImageTypeTable imageTypes;
    private final @NotNull UpgradeTypeTable upgradeTypes;
    private final @NotNull TechTypeTable techTypes;
    private final @NotNull OrderTypeTable orderTypes;
    private final @NotNull SoundTypeTable soundTypes;
    private final @NotNull PlayerProgressTable progress;

    private final @NotNull StatModifier @NotNull [] @NotNull [] modifiers;
    private final @NotNull List<@NotNull List<@NotNull DamageLayer>> damageLayers;
    private boolean sealed;

    private GameContent(@NotNull ContentDefinition definition, int playerCount) {
        int statCount = definition.getStats().getCount();

        this.definition = definition;

        this.unitTypes      = new UnitTypeTable(definition);
        this.weaponTypes    = new WeaponTypeTable(definition);
        this.motionProfiles = new MotionProfileTable(definition);
        this.spriteTypes    = new SpriteTypeTable(definition);
        this.imageTypes     = new ImageTypeTable(definition);
        this.upgradeTypes   = new UpgradeTypeTable(definition);
        this.techTypes      = new TechTypeTable(definition);
        this.orderTypes     = new OrderTypeTable(definition);
        this.soundTypes     = new SoundTypeTable(definition);
        this.progress       = new PlayerProgressTable(definition, playerCount);

        this.modifiers = new StatModifier[statCount][];

        for (int statIndex = 0; statIndex < statCount; statIndex++) {
            this.modifiers[statIndex] = definition.getModifiers(statIndex);
        }

        this.damageLayers = new ArrayList<>(Collections.nCopies(definition.getUnitTypes().getCount(), List.of()));

        this.sealed = false;
    }

    /**
     * @return the content definition the tables were created from; it also holds the factions, which have no table
     */
    public @NotNull ContentDefinition getDefinition() {
        return this.definition;
    }

    /**
     * @return the unit type table
     */
    public @NotNull UnitTypeTable getUnitTypes() {
        return this.unitTypes;
    }

    /**
     * @return the weapon type table
     */
    public @NotNull WeaponTypeTable getWeaponTypes() {
        return this.weaponTypes;
    }

    /**
     * @return the motion profile table
     */
    public @NotNull MotionProfileTable getMotionProfiles() {
        return this.motionProfiles;
    }

    /**
     * @return the sprite type table
     */
    public @NotNull SpriteTypeTable getSpriteTypes() {
        return this.spriteTypes;
    }

    /**
     * @return the image type table
     */
    public @NotNull ImageTypeTable getImageTypes() {
        return this.imageTypes;
    }

    /**
     * @return the upgrade type table
     */
    public @NotNull UpgradeTypeTable getUpgradeTypes() {
        return this.upgradeTypes;
    }

    /**
     * @return the tech type table
     */
    public @NotNull TechTypeTable getTechTypes() {
        return this.techTypes;
    }

    /**
     * @return the order type table
     */
    public @NotNull OrderTypeTable getOrderTypes() {
        return this.orderTypes;
    }

    /**
     * @return the sound type table
     */
    public @NotNull SoundTypeTable getSoundTypes() {
        return this.soundTypes;
    }

    /**
     * @return the upgrade levels, techs, availability and unit counts of every player
     */
    public @NotNull PlayerProgressTable getProgress() {
        return this.progress;
    }


    /**
     * Adds a modifier of this game only, applied after the modifiers of the definition and the game modifiers added before it.
     * A system adds the modifiers of its game while the game is created ({@code GameSystem.attach}), for an effect that reads
     * game state outside the content, such as the mod's own storage of the game; the game seals the content before it runs ({@link #seal}).
     * <p>
     * Determinism contract: every game of a definition adds the same modifiers in the same order.
     *
     * @param stat     the stat
     * @param modifier the modifier
     * @throws IllegalArgumentException if the stat is not registered in the content
     * @throws IllegalStateException    if the content is sealed
     */
    public void addModifier(@NotNull Stat stat, @NotNull StatModifier modifier) {
        if (this.sealed) {
            throw Exceptions.newIllegalStateException("The content of the game is sealed; {0} cannot get another modifier.", stat);
        }

        if (stat.getIndex() < 0 || stat.getIndex() >= this.modifiers.length) {
            throw Exceptions.newIllegalArgumentException("The stat {0} is not registered.", stat);
        }

        StatModifier[] statModifiers = this.modifiers[stat.getIndex()];
        StatModifier[] added = new StatModifier[statModifiers.length + 1];

        System.arraycopy(statModifiers, 0, added, 0, statModifiers.length);
        added[statModifiers.length] = modifier;

        this.modifiers[stat.getIndex()] = added;
    }

    /**
     * Returns the damage layers of a unit type in chain order: the layers that take a hit's damage before the hit points, first to last.
     *
     * @param type the unit type
     * @return the damage layers, empty for a type without any; the list cannot be changed
     * @throws IllegalArgumentException if the unit type is not registered in the content
     */
    public @NotNull List<@NotNull DamageLayer> getDamageLayers(@NotNull UnitType type) {
        return this.damageLayers.get(this.requireUnitTypeIndex(type));
    }

    /**
     * Adds a damage layer at the end of the chain of a unit type. A system adds the layers of its game while the game is created
     * ({@code GameSystem.attach}); the game seals the content before it runs ({@link #seal}).
     * <p>
     * Determinism contract: every game of a definition adds the same layers in the same order.
     *
     * @param type  the unit type
     * @param layer the damage layer
     * @throws IllegalArgumentException if the unit type is not registered in the content
     * @throws IllegalStateException    if the content is sealed
     */
    public void addDamageLayer(@NotNull UnitType type, @NotNull DamageLayer layer) {
        if (this.sealed) {
            throw Exceptions.newIllegalStateException("The content of the game is sealed; {0} cannot get another damage layer.", type);
        }

        int typeIndex = this.requireUnitTypeIndex(type);
        List<DamageLayer> layers = new ArrayList<>(this.damageLayers.get(typeIndex));

        layers.add(layer);

        this.damageLayers.set(typeIndex, List.copyOf(layers));
    }

    /**
     * Seals the stat modifiers and the damage layers: {@link #addModifier} and {@link #addDamageLayer} fail afterwards. The game calls it once
     * its systems are attached.
     */
    public void seal() {
        this.sealed = true;
    }

    /**
     * Applies the modifiers of a stat to a value, in registration order, each to the result of the previous one.
     *
     * @param stat       the stat
     * @param player     the owner
     * @param unitType   the unit type, or {@code null} for a weapon stat without a unit
     * @param weaponType the weapon type for a weapon stat, otherwise {@code null}
     * @param baseValue  the table value
     * @return the modified value
     */
    public int applyModifiers(@NotNull Stat stat,
                              @NotNull Player player, @Nullable UnitType unitType, @Nullable WeaponType weaponType,
                              int baseValue) {
        int value = baseValue;

        for (StatModifier modifier : this.modifiers[stat.getIndex()]) {
            value = modifier.apply(this, player, unitType, weaponType, value);
        }

        return value;
    }

    private int requireUnitTypeIndex(@NotNull UnitType type) {
        int typeIndex = type.getIndex();

        if (typeIndex < 0 || typeIndex >= this.damageLayers.size()) {
            throw Exceptions.newIllegalArgumentException("The unit type {0} is not registered.", type);
        }

        return typeIndex;
    }

    /**
     * Mixes the tables into the hash in the order of the class description.
     *
     * @param hash the hash being computed
     */
    @Override
    public void contributeStateHash(@NotNull StateHash hash) {
        this.unitTypes.contributeStateHash(hash);
        this.weaponTypes.contributeStateHash(hash);
        this.motionProfiles.contributeStateHash(hash);
        this.spriteTypes.contributeStateHash(hash);
        this.imageTypes.contributeStateHash(hash);
        this.upgradeTypes.contributeStateHash(hash);
        this.techTypes.contributeStateHash(hash);
        this.orderTypes.contributeStateHash(hash);
        this.soundTypes.contributeStateHash(hash);
        this.progress.contributeStateHash(hash);
    }

    /**
     * Writes the tables in the order of the class description.
     *
     * @param writer the writer of the snapshot
     */
    @Override
    public void writeSnapshot(@NotNull StateSnapshotWriter writer) {
        this.unitTypes.writeSnapshot(writer);
        this.weaponTypes.writeSnapshot(writer);
        this.motionProfiles.writeSnapshot(writer);
        this.spriteTypes.writeSnapshot(writer);
        this.imageTypes.writeSnapshot(writer);
        this.upgradeTypes.writeSnapshot(writer);
        this.techTypes.writeSnapshot(writer);
        this.orderTypes.writeSnapshot(writer);
        this.soundTypes.writeSnapshot(writer);
        this.progress.writeSnapshot(writer);
    }

    /**
     * Reads the tables back in the order of the class description, replacing the current values.
     *
     * @param reader the reader of the snapshot
     * @throws IOException if the snapshot ends early or a table holds another number of types or columns
     */
    @Override
    public void readSnapshot(@NotNull StateSnapshotReader reader) throws IOException {
        this.unitTypes.readSnapshot(reader);
        this.weaponTypes.readSnapshot(reader);
        this.motionProfiles.readSnapshot(reader);
        this.spriteTypes.readSnapshot(reader);
        this.imageTypes.readSnapshot(reader);
        this.upgradeTypes.readSnapshot(reader);
        this.techTypes.readSnapshot(reader);
        this.orderTypes.readSnapshot(reader);
        this.soundTypes.readSnapshot(reader);
        this.progress.readSnapshot(reader);
    }
}
