package org.fogofwarlabs.sc4j.engine.combat;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.collision.CollisionSystem;
import org.fogofwarlabs.sc4j.engine.content.combat.DamageHit;
import org.fogofwarlabs.sc4j.engine.content.combat.DamageKind;
import org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer;
import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.definition.WeaponTarget;
import org.fogofwarlabs.sc4j.engine.content.modifier.Stat;
import org.fogofwarlabs.sc4j.engine.content.table.GameContent;
import org.fogofwarlabs.sc4j.engine.content.table.UnitTypeTable;
import org.fogofwarlabs.sc4j.engine.content.table.WeaponTypeTable;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.content.type.WeaponType;
import org.fogofwarlabs.sc4j.engine.economy.EconomySystem;
import org.fogofwarlabs.sc4j.engine.entity.EntityKind;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.entity.pool.SlotList;
import org.fogofwarlabs.sc4j.engine.game.ControllerKind;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.game.PlayerSlot;
import org.fogofwarlabs.sc4j.engine.game.frame.FrameClock;
import org.fogofwarlabs.sc4j.engine.hook.HookComposition;
import org.fogofwarlabs.sc4j.engine.hook.HookList;
import org.fogofwarlabs.sc4j.engine.hook.HookPoint;
import org.fogofwarlabs.sc4j.engine.lifecycle.UnitAccountingUtils;
import org.fogofwarlabs.sc4j.engine.lifecycle.UnitLifecycle;
import org.fogofwarlabs.sc4j.engine.movement.MovementSystem;
import org.fogofwarlabs.sc4j.engine.order.OrderSystem;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.projectile.ProjectileSystem;
import org.fogofwarlabs.sc4j.engine.spatial.SpatialIndex;
import org.fogofwarlabs.sc4j.engine.spatial.UnitFilter;
import org.fogofwarlabs.sc4j.engine.spatial.UnitList;
import org.fogofwarlabs.sc4j.engine.visibility.VisibilitySystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Attacks and deals damage with the weapons of the unit type table: the engine default {@link CombatSystem}. A mod registers it under
 * {@link CombatSystem#KEY}, adds its unit columns to the game definition ({@link #addColumns}) and registers an {@link AttackBehavior} for its
 * attack order and a {@link GuardBehavior} for the idle order of its fighting units.
 * {@snippet :
 * StandardCombatSystem.addColumns(builder);
 * builder.useSystem(CombatSystem.KEY, context -> new StandardCombatSystem.Builder().setSplashTargets(SplashTargets.ALL).build());
 *}
 * <p>
 * Hit points are the engine {@link Unit} field with eight fraction bits ({@link #FRACTION_BITS}), as are the points of the damage layers; the table
 * values are whole points. Two units are enemies when both owners have a player slot in the setup that is not {@link ControllerKind#NEUTRAL} and the
 * slots' teams differ. A unit can attack only a target its owner detects ({@link VisibilitySystem#isDetected}), when the game has a visibility
 * system. A flying target ({@link UnitTrait#FLYER}) is attacked with the air weapon, any other with the ground weapon, if the weapon has the target
 * kind ({@link WeaponTarget#AIR} or {@link WeaponTarget#GROUND}). The range is the Euclidean distance between the collision bounds of the two unit
 * types around the unit positions ({@code 0} when they touch; a unit without type is its position), from the weapon's minimum range up to its maximum
 * range after the {@link Stat#WEAPON_RANGE} modifiers. A weapon fires when its unit column says it is ready; it is ready again after its cooldown
 * after the {@link Stat#WEAPON_COOLDOWN} modifiers, at least one frame. A unit acquires the nearest unit it can attack within its target acquisition
 * range after the {@link Stat#TARGET_ACQUISITION_RANGE} modifiers ({@link #findTarget}).
 * <p>
 * A weapon hit ({@link #dealWeaponDamage}): the damage is the weapon damage after the {@link Stat#WEAPON_DAMAGE} modifiers times the damage factor.
 * The damage layers of the hit unit's type ({@link GameContent#getDamageLayers}) take their parts first, in chain order, each by its own rules
 * ({@link DamageLayer#absorbDamage}), and lose the absorbed points at once; the armor ({@link Stat#ARMOR}) is taken off the rest, at most down to
 * {@code 0}. The {@link #DAMAGE_SCALE} hooks then change the rest by the weapon's damage kind, and a hit no layer absorbed any of deals at least the
 * minimum damage of the settings ({@link Builder#setMinimumDamage}, {@code 0} unless the mod sets one). Damage that leaves no hit point kills the
 * unit ({@link UnitAccountingUtils#removeUnit} as a death): the unit lifecycle announces its death ({@link UnitLifecycle#notifyDying},
 * {@link UnitLifecycle#notifyRemoved}), the owner loses its supply ({@link EconomySystem}) and its unit counts, the unit leaves the spatial index,
 * its orders ({@link OrderSystem#removeOrders}) and its movement, loses its owner and its pool slot is freed. A unit that kills another, by a hit or
 * a projectile it launched while it still lives, counts the kill in its {@link #KILL_COUNT} column ({@link #getKillCount}).
 * <p>
 * A hit first asks the {@link #MISS} hooks in registration order whether it misses its target; without an answer it hits. A missed hit deals no
 * damage at all. A weapon with an outer splash radius above {@code 0} ({@link WeaponTypeTable#getSplashOuterRadius}) hits every unit around the point
 * of the hit (the target's position, or where a projectile lands) whose position is within the outer radius, with its share of the damage before
 * damage layers and armor: the inner share within the inner radius, the middle share within the middle radius and the outer share beyond
 * ({@link Builder#setSplashShares}; the whole damage in every radius unless the mod sets shares); the units are the ones the weapon has the target
 * kind of, other than the attacker, that the splash targets of the settings reach ({@link SplashTargets}); the target itself always takes at least
 * the inner share. The target comes first, then the other units in slot order; the distance is the Euclidean distance between the positions.
 * <p>
 * Determinism contract: the system draws no random value and allocates nothing after it is attached; the miss and damage scale hooks run in
 * registration order. Its state is the engine unit fields and its unit columns, which the unit pool mixes into the state hash.
 */
public final class StandardCombatSystem implements CombatSystem {
    /**
     * The number of fraction bits of hit points, damage layer points and damage.
     */
    public static final int FRACTION_BITS = 8;
    /**
     * The point a weapon hit's damage after armor is changed at by the weapon's damage kind ({@link HookComposition#CHAINED}; the first hook
     * receives the damage after armor). It runs only for a weapon with a damage kind.
     */
    public static final @NotNull HookPoint<DamageScaleHook> DAMAGE_SCALE = HookPoint.of("combat.damage-scale", DamageScaleHook.class,
                                                                                        HookComposition.CHAINED);
    /**
     * The point a hit asks whether it misses its target at ({@link HookComposition#FIRST_ANSWER}; the default is that it hits).
     */
    public static final @NotNull HookPoint<MissHook> MISS = HookPoint.of("combat.miss", MissHook.class, HookComposition.FIRST_ANSWER);
    /**
     * The unit column holding the frame from which the ground weapon of a unit is ready, {@code 0} when it is.
     */
    public static final @NotNull ColumnKey<IntColumn> GROUND_WEAPON_READY_FRAME = ColumnKey.ofInt("combat.ground-weapon-ready-frame", 0,
                                                                                                  ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the frame from which the air weapon of a unit is ready, {@code 0} when it is.
     */
    public static final @NotNull ColumnKey<IntColumn> AIR_WEAPON_READY_FRAME = ColumnKey.ofInt("combat.air-weapon-ready-frame", 0,
                                                                                               ColumnReset.ON_ALLOCATE);
    /**
     * The unit column holding the number of units a unit killed, {@code 0} for a new unit.
     */
    public static final @NotNull ColumnKey<IntColumn> KILL_COUNT = ColumnKey.ofInt("combat.kill-count", 0, ColumnReset.ON_ALLOCATE);

    /**
     * The team of a unit owner without a player slot in the setup.
     */
    private static final int NO_TEAM = -1;
    /**
     * The smallest number of frames between two shots of a weapon.
     */
    private static final int MINIMUM_COOLDOWN = 1;
    /**
     * The whole in percent.
     */
    private static final int PERCENT = 100;

    /**
     * Builds {@link StandardCombatSystem} instances: the settings of the splash damage and the minimum damage.
     */
    public static final class Builder {
        private int innerSplashPercent;
        private int middleSplashPercent;
        private int outerSplashPercent;
        private @NotNull SplashTargets splashTargets;
        private int minimumDamage;

        /**
         * Creates a {@link Builder} instance with the neutral settings: the whole damage in every splash radius, the splash reaching the
         * enemies of the attacker and no minimum damage.
         */
        public Builder() {
            this.innerSplashPercent  = PERCENT;
            this.middleSplashPercent = PERCENT;
            this.outerSplashPercent  = PERCENT;
            this.splashTargets       = SplashTargets.ENEMIES;
            this.minimumDamage       = 0;
        }

        /**
         * Sets the shares of a hit's damage the units around the hit take.
         *
         * @param innerSplashPercent  the share in percent within the inner radius, at least {@code 0}
         * @param middleSplashPercent the share in percent within the middle radius, at least {@code 0}
         * @param outerSplashPercent  the share in percent within the outer radius, at least {@code 0}
         * @return this instance for chaining
         * @throws IllegalArgumentException if a share is negative
         */
        public @NotNull Builder setSplashShares(int innerSplashPercent, int middleSplashPercent, int outerSplashPercent) {
            if (innerSplashPercent < 0 || middleSplashPercent < 0 || outerSplashPercent < 0) {
                throw Exceptions.newIllegalArgumentException("Invalid splash shares: {0}, {1}, {2}", innerSplashPercent, middleSplashPercent,
                                                             outerSplashPercent);
            }

            this.innerSplashPercent  = innerSplashPercent;
            this.middleSplashPercent = middleSplashPercent;
            this.outerSplashPercent  = outerSplashPercent;

            return this;
        }

        /**
         * Sets which units around a hit the splash reaches.
         *
         * @param splashTargets the splash targets
         * @return this instance for chaining
         */
        public @NotNull Builder setSplashTargets(@NotNull SplashTargets splashTargets) {
            this.splashTargets = splashTargets;

            return this;
        }

        /**
         * Sets the smallest damage a hit deals to the hit points when no damage layer absorbed any of it.
         *
         * @param minimumDamage the damage with {@link #FRACTION_BITS} fraction bits, at least {@code 0}
         * @return this instance for chaining
         * @throws IllegalArgumentException if the damage is negative
         */
        public @NotNull Builder setMinimumDamage(int minimumDamage) {
            if (minimumDamage < 0) {
                throw Exceptions.newIllegalArgumentException("Invalid minimum damage: {0}", minimumDamage);
            }

            this.minimumDamage = minimumDamage;

            return this;
        }

        /**
         * Builds a {@link StandardCombatSystem} instance with these settings.
         *
         * @return the built {@link StandardCombatSystem} instance
         */
        public @NotNull StandardCombatSystem build() {
            return new StandardCombatSystem(this);
        }
    }

    /**
     * Adds the unit columns of this system to a game definition; a game that registers this system needs them.
     *
     * @param builder the game definition builder
     * @throws IllegalArgumentException if a column is already added
     * @throws IllegalStateException    if the builder is closed
     */
    public static void addColumns(@NotNull GameDefinition.Builder builder) {
        builder.addColumn(EntityKind.UNIT, GROUND_WEAPON_READY_FRAME);
        builder.addColumn(EntityKind.UNIT, AIR_WEAPON_READY_FRAME);
        builder.addColumn(EntityKind.UNIT, KILL_COUNT);
    }

    private static <T> @NotNull T requireAttached(@Nullable T value) {
        if (value == null) {
            throw Exceptions.newIllegalStateException("The standard combat system is not attached.");
        }

        return value;
    }


    private final int innerSplashPercent;
    private final int middleSplashPercent;
    private final int outerSplashPercent;
    private final @NotNull SplashTargets splashTargets;
    private final int minimumDamage;

    private @Nullable Game game;
    private @Nullable GameContent content;
    private @Nullable EntityPool<Unit> units;
    private @Nullable IntColumn groundReadyFrames;
    private @Nullable IntColumn airReadyFrames;
    private @Nullable IntColumn killCounts;
    private @Nullable HookList<DamageScaleHook> damageScaleHooks;
    private @Nullable HookList<MissHook> missHooks;
    private @Nullable VisibilitySystem visibility;
    private @NotNull UnitList splashUnits;
    private int @NotNull [] teams;
    private final @NotNull DamageHit hit;

    private final @NotNull UnitFilter targetFilter;
    private @Nullable Unit searchingUnit;

    /**
     * Creates a {@link StandardCombatSystem} instance with the default settings of {@link Builder}; it serves nothing until it is attached.
     */
    public StandardCombatSystem() {
        this(new Builder());
    }

    private StandardCombatSystem(@NotNull Builder builder) {
        this.innerSplashPercent  = builder.innerSplashPercent;
        this.middleSplashPercent = builder.middleSplashPercent;
        this.outerSplashPercent  = builder.outerSplashPercent;
        this.splashTargets       = builder.splashTargets;
        this.minimumDamage       = builder.minimumDamage;

        this.game              = null;
        this.content           = null;
        this.units             = null;
        this.groundReadyFrames = null;
        this.airReadyFrames    = null;
        this.killCounts        = null;
        this.damageScaleHooks  = null;
        this.missHooks         = null;
        this.visibility        = null;
        this.splashUnits       = new UnitList(1);
        this.teams             = new int[0];
        this.hit               = new DamageHit();

        this.targetFilter  = this::isTargetCandidate;
        this.searchingUnit = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the content, the unit pool with this system's columns, the miss and damage scale hooks and the visibility system if the game has
     * one, holds the list of the units a splash reaches, and reads the team of each player slot that is not neutral.
     *
     * @param game the game
     * @throws IllegalArgumentException if the unit pool lacks a column of this system ({@link #addColumns})
     */
    @Override
    public void attach(@NotNull Game game) {
        this.game              = game;
        this.content           = game.getContent();
        this.units             = game.getUnits();
        this.groundReadyFrames = game.getUnits().getColumn(GROUND_WEAPON_READY_FRAME);
        this.airReadyFrames    = game.getUnits().getColumn(AIR_WEAPON_READY_FRAME);
        this.killCounts        = game.getUnits().getColumn(KILL_COUNT);
        this.damageScaleHooks  = game.getHooks(DAMAGE_SCALE);
        this.missHooks         = game.getHooks(MISS);
        this.visibility        = game.hasSystem(VisibilitySystem.KEY) ? game.getVisibility() : null;
        this.splashUnits       = new UnitList(game.getUnits().getCapacity());
        this.teams             = new int[game.getPlayerCount()];

        Arrays.fill(this.teams, NO_TEAM);

        for (PlayerSlot slot : game.getPlayers()) {
            if (slot.getSlotIndex() < this.teams.length && slot.getControllerKind() != ControllerKind.NEUTRAL) {
                this.teams[slot.getSlotIndex()] = slot.getTeam();
            }
        }
    }

    /**
     * Checks whether a unit treats another unit as an enemy.
     * <p>
     * The standard rule compares the teams of the owners' player slots.
     *
     * @param unit      the unit
     * @param otherUnit the other unit
     * @return {@code true} if the owners are enemies, {@code false} otherwise
     */
    @Override
    public boolean isEnemy(@NotNull Unit unit, @NotNull Unit otherUnit) {
        int team = this.getTeam(unit.getOwner());
        int otherTeam = this.getTeam(otherUnit.getOwner());

        return team != NO_TEAM && otherTeam != NO_TEAM && team != otherTeam;
    }

    /**
     * Checks whether a unit can attack a target at all: it has a weapon for the target and the target can be attacked.
     * <p>
     * The standard rule needs another living enemy unit the owner detects and a weapon of the unit for the target's kind that has that kind among
     * its targets.
     *
     * @param unit       the attacking unit
     * @param targetUnit the target unit, or {@code null} for none
     * @return {@code true} if the unit can attack the target, {@code false} otherwise or without target
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean canAttack(@NotNull Unit unit, @Nullable Unit targetUnit) {
        if (targetUnit == null || targetUnit == unit || targetUnit.getHitPoints() <= 0 || !this.isEnemy(unit, targetUnit)) {
            return false;
        }

        VisibilitySystem attachedVisibility = this.visibility;
        Player owner = unit.getOwner();

        if (attachedVisibility != null && owner != null && !attachedVisibility.isDetected(owner, targetUnit)) {
            return false;
        }

        return this.getTargetWeapon(unit, targetUnit) != null;
    }

    /**
     * Checks whether a target is within the range of the weapon a unit would use against it.
     *
     * @param unit       the attacking unit
     * @param targetUnit the target unit
     * @return {@code true} if the target is in range, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean isInRange(@NotNull Unit unit, @NotNull Unit targetUnit) {
        WeaponType weapon = this.getTargetWeapon(unit, targetUnit);

        if (weapon == null) {
            return false;
        }

        GameContent attachedContent = requireAttached(this.content);
        WeaponTypeTable weapons = attachedContent.getWeaponTypes();
        long minimumRange = weapons.getMinimumRange(weapon);
        long maximumRange = weapons.getMaximumRange(weapon);
        Player owner = unit.getOwner();

        if (owner != null) {
            maximumRange = attachedContent.applyModifiers(Stat.WEAPON_RANGE, owner, unit.getType(), weapon, (int) maximumRange);
        }

        UnitTypeTable unitTypes = attachedContent.getUnitTypes();
        UnitType type = unit.getType();
        UnitType targetType = targetUnit.getType();
        long distanceX = Math.max(0, Math.max(getLeft(unitTypes, targetUnit, targetType) - getRight(unitTypes, unit, type),
                                              getLeft(unitTypes, unit, type) - getRight(unitTypes, targetUnit, targetType)));
        long distanceY = Math.max(0, Math.max(getTop(unitTypes, targetUnit, targetType) - getBottom(unitTypes, unit, type),
                                              getTop(unitTypes, unit, type) - getBottom(unitTypes, targetUnit, targetType)));
        long distanceSquare = distanceX * distanceX + distanceY * distanceY;

        return distanceSquare >= minimumRange * minimumRange && distanceSquare <= maximumRange * maximumRange;
    }

    private static int getLeft(@NotNull UnitTypeTable unitTypes, @NotNull Unit unit, @Nullable UnitType type) {
        return type != null ? unit.getPositionX() - unitTypes.getCollisionLeft(type) : unit.getPositionX();
    }

    private static int getRight(@NotNull UnitTypeTable unitTypes, @NotNull Unit unit, @Nullable UnitType type) {
        return type != null ? unit.getPositionX() + unitTypes.getCollisionRight(type) : unit.getPositionX();
    }

    private static int getTop(@NotNull UnitTypeTable unitTypes, @NotNull Unit unit, @Nullable UnitType type) {
        return type != null ? unit.getPositionY() - unitTypes.getCollisionUp(type) : unit.getPositionY();
    }

    private static int getBottom(@NotNull UnitTypeTable unitTypes, @NotNull Unit unit, @Nullable UnitType type) {
        return type != null ? unit.getPositionY() + unitTypes.getCollisionDown(type) : unit.getPositionY();
    }

    /**
     * Returns the weapon of a unit for a kind of target.
     *
     * @param unit the unit
     * @param slot the kind of target
     * @return the weapon, or {@code null} if the unit has no such weapon
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public @Nullable WeaponType getWeapon(@NotNull Unit unit, @NotNull WeaponSlot slot) {
        UnitType type = unit.getType();

        if (type == null) {
            return null;
        }

        UnitTypeTable unitTypes = requireAttached(this.content).getUnitTypes();

        return slot == WeaponSlot.GROUND ? unitTypes.getGroundWeapon(type) : unitTypes.getAirWeapon(type);
    }

    /**
     * Keeps a unit after the target of its attack: it moves towards the target until the target is in range.
     * <p>
     * The standard pursuit stops a unit that has the target in range, in a game with a collision system as an immovable unit
     * ({@link CollisionSystem#makeImmovable}), so that units pressing in from behind go around it instead of pushing it while it fights; otherwise
     * it moves the unit after the target ({@link MovementSystem#setTargetUnit}), which makes it movable again. The pursuit ends for a unit that
     * cannot move (top speed {@code 0}).
     *
     * @param unit             the attacking unit
     * @param targetUnit       the target unit
     * @param instructionLimit the instruction limit of each animation program run the pursuit starts
     * @return {@code true} if the pursuit goes on, {@code false} if it ended: the unit arrived without reaching the target, or another system took the unit over
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public boolean pursueTarget(@NotNull Unit unit, @NotNull Unit targetUnit, int instructionLimit) {
        Game attachedGame = requireAttached(this.game);
        MovementSystem movement = attachedGame.getMovement();

        if (this.isInRange(unit, targetUnit)) {
            if (attachedGame.hasSystem(CollisionSystem.KEY)) {
                attachedGame.getCollision().makeImmovable(unit);
            } else {
                movement.stop(unit);
            }

            return true;
        }

        if (unit.getTopSpeed() == 0) {
            return false;
        }

        movement.setTargetUnit(unit, targetUnit);

        return true;
    }

    /**
     * Deals damage to a unit that armor and damage layers no longer reduce; a unit whose hit points run out dies, and a living attacker counts
     * the kill.
     * <p>
     * The standard damage has eight fraction bits; a unit without hit points takes none.
     *
     * @param targetUnit       the damaged unit
     * @param amount           the damage, in the implementation's fixed-point unit
     * @param attackerUnit     the attacking unit, or {@code null} without attacker
     * @param instructionLimit the instruction limit of each animation program run the damage starts
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void dealDamage(@NotNull Unit targetUnit, int amount, @Nullable Unit attackerUnit, int instructionLimit) {
        if (targetUnit.getHitPoints() <= 0) {
            return;
        }

        if (amount < targetUnit.getHitPoints()) {
            targetUnit.setHitPoints(targetUnit.getHitPoints() - amount);

            return;
        }

        targetUnit.setHitPoints(0);

        if (attackerUnit != null && attackerUnit != targetUnit && attackerUnit.getHitPoints() > 0) {
            IntColumn kills = requireAttached(this.killCounts);

            kills.set(attackerUnit.getIndex(), kills.get(attackerUnit.getIndex()) + 1);
        }

        UnitAccountingUtils.removeUnit(requireAttached(this.game), targetUnit, true);
    }

    /**
     * Returns the weapon a unit uses against a target: the air weapon against a flying target, the ground weapon against any other, if that
     * weapon has the target's kind among its targets.
     *
     * @param unit       the attacking unit
     * @param targetUnit the target unit
     * @return the weapon, or {@code null} if the unit has none for the target
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable WeaponType getTargetWeapon(@NotNull Unit unit, @NotNull Unit targetUnit) {
        WeaponSlot slot = this.getTargetSlot(targetUnit);
        WeaponType weapon = this.getWeapon(unit, slot);

        if (weapon == null) {
            return null;
        }

        WeaponTarget target = slot == WeaponSlot.AIR ? WeaponTarget.AIR : WeaponTarget.GROUND;

        return requireAttached(this.content).getWeaponTypes().hasTarget(weapon, target) ? weapon : null;
    }

    /**
     * Returns the kind of target a unit is: air for a flying unit type, ground otherwise.
     *
     * @param targetUnit the target unit
     * @return the weapon slot used against the unit
     * @throws IllegalStateException if the system is not attached
     */
    public @NotNull WeaponSlot getTargetSlot(@NotNull Unit targetUnit) {
        UnitType type = targetUnit.getType();

        if (type != null && requireAttached(this.content).getUnitTypes().hasTrait(type, UnitTrait.FLYER)) {
            return WeaponSlot.AIR;
        }

        return WeaponSlot.GROUND;
    }

    /**
     * Finds the target a unit acquires on its own: the nearest unit it can attack ({@link #canAttack}) within its target acquisition range
     * after the {@link Stat#TARGET_ACQUISITION_RANGE} modifiers, in pixels. With a spatial index the distance is measured to the unit bounds
     * and equally near units go in index order ({@link SpatialIndex#findNearest}); without one the units of the pool are visited in slot order
     * and the Euclidean distance between the positions counts, the first of equally near ones winning.
     *
     * @param unit the searching unit
     * @return the target, or {@code null} if no unit is in range or the unit has no type or owner
     * @throws IllegalStateException if the system is not attached
     */
    public @Nullable Unit findTarget(@NotNull Unit unit) {
        GameContent attachedContent = requireAttached(this.content);
        Game attachedGame = requireAttached(this.game);
        UnitType type = unit.getType();
        Player owner = unit.getOwner();

        if (type == null || owner == null) {
            return null;
        }

        int range = attachedContent.applyModifiers(Stat.TARGET_ACQUISITION_RANGE, owner, type, null,
                                                   attachedContent.getUnitTypes().getTargetAcquisitionRange(type));

        if (attachedGame.hasSystem(SpatialIndex.KEY)) {
            this.searchingUnit = unit;

            try {
                return attachedGame.getSpatialIndex().findNearest(unit.getPositionX(), unit.getPositionY(), range, this.targetFilter);
            } finally {
                this.searchingUnit = null;
            }
        }

        EntityPool<Unit> pool = requireAttached(this.units);
        SlotList used = pool.getUsedSlots();
        Unit nearest = null;
        long nearestDistance = (long) range * range;

        for (int index = used.getFirst(); index != SlotList.NONE; index = used.getNext(index)) {
            Unit candidate = pool.get(index);
            long distanceX = candidate.getPositionX() - unit.getPositionX();
            long distanceY = candidate.getPositionY() - unit.getPositionY();
            long distance = distanceX * distanceX + distanceY * distanceY;

            if (distance <= nearestDistance && (nearest == null || distance < nearestDistance) && this.canAttack(unit, candidate)) {
                nearest         = candidate;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    /**
     * Returns how many frames a weapon of a unit still needs before it is ready.
     *
     * @param unit the unit
     * @param slot the weapon slot
     * @return the remaining frames, {@code 0} if the weapon is ready
     * @throws IllegalStateException if the system is not attached
     */
    public int getRemainingCooldown(@NotNull Unit unit, @NotNull WeaponSlot slot) {
        int readyFrame = this.getReadyFrames(slot).get(unit.getIndex());

        return Math.max(0, readyFrame - requireAttached(this.game).getClock().getFrame());
    }

    /**
     * Returns how many units a unit killed since it was made.
     *
     * @param unit the unit
     * @return the kill count
     * @throws IllegalStateException if the system is not attached
     */
    public int getKillCount(@NotNull Unit unit) {
        return requireAttached(this.killCounts).get(unit.getIndex());
    }

    /**
     * Fires the weapon a unit uses against a target if the weapon is ready and the target can be attacked and is in range: a weapon with a
     * projectile launches it when the game has a projectile system ({@link ProjectileSystem#launch}), which hits on arrival; otherwise the target
     * takes the weapon hit at once ({@link #dealWeaponDamage}). The weapon then waits for its cooldown.
     *
     * @param unit             the attacking unit
     * @param targetUnit       the target unit
     * @param instructionLimit the instruction limit of each animation program run the hit starts
     * @return {@code true} if the weapon fired, {@code false} otherwise
     * @throws IllegalStateException if the system is not attached
     */
    public boolean fireWeapon(@NotNull Unit unit, @NotNull Unit targetUnit, int instructionLimit) {
        if (!this.canAttack(unit, targetUnit) || !this.isInRange(unit, targetUnit)) {
            return false;
        }

        WeaponSlot slot = this.getTargetSlot(targetUnit);

        if (this.getRemainingCooldown(unit, slot) != 0) {
            return false;
        }

        WeaponType weapon = this.getTargetWeapon(unit, targetUnit);

        if (weapon == null) {
            return false;
        }

        GameContent attachedContent = requireAttached(this.content);
        Game attachedGame = requireAttached(this.game);
        FrameClock clock = attachedGame.getClock();
        Player owner = unit.getOwner();
        int cooldown = attachedContent.getWeaponTypes().getCooldown(weapon);

        if (owner != null) {
            cooldown = attachedContent.applyModifiers(Stat.WEAPON_COOLDOWN, owner, unit.getType(), weapon, cooldown);
        }

        this.getReadyFrames(slot).set(unit.getIndex(), clock.getFrame() + Math.max(MINIMUM_COOLDOWN, cooldown));

        ProjectileSystem projectiles = attachedGame.findModSystem(ProjectileSystem.KEY);

        if (projectiles == null || attachedContent.getWeaponTypes().getProjectile(weapon) == null ||
            !projectiles.launch(weapon, unit, targetUnit, instructionLimit)) {
            this.dealWeaponDamage(weapon, unit, targetUnit, instructionLimit);
        }

        return true;
    }

    /**
     * Deals the damage of one hit of a weapon to a unit: the weapon's damage after the attacker's modifiers, reduced by the unit's damage layers
     * ({@link DamageLayer}) and armor by the rules of the implementation, then {@link #dealDamage}. A projectile system calls it when a projectile
     * arrives.
     * <p>
     * The standard hit: the miss hooks, weapon damage and factor, the splash share, the damage layers, armor, the damage scale hooks and the
     * minimum damage, as the class description says; without attacker the weapon's table damage counts. A splash weapon hits around the
     * target's position.
     *
     * @param weapon           the weapon
     * @param attackerUnit     the attacking unit, or {@code null} if it no longer lives
     * @param targetUnit       the hit unit
     * @param instructionLimit the instruction limit of each animation program run the hit starts
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void dealWeaponDamage(@NotNull WeaponType weapon, @Nullable Unit attackerUnit, @NotNull Unit targetUnit, int instructionLimit) {
        this.dealWeaponDamageAt(weapon, attackerUnit, targetUnit, targetUnit.getPositionX(), targetUnit.getPositionY(), instructionLimit);
    }

    /**
     * Deals the damage of one hit of a weapon at a point: the hit of {@link #dealWeaponDamage} on the target, if there is one, and, for an
     * implementation with splash damage, the units around the point. A projectile system that does not home calls it where its projectile
     * lands. The default hits only the target.
     * <p>
     * The standard hit asks the miss hooks for a target, then hits the target alone, or with a splash weapon every unit around the point the
     * splash reaches, as the class description says.
     *
     * @param weapon           the weapon
     * @param attackerUnit     the attacking unit, or {@code null} if it no longer lives
     * @param targetUnit       the unit hit at the point, or {@code null} if the hit lands on no unit
     * @param x                the x coordinate of the point
     * @param y                the y coordinate of the point
     * @param instructionLimit the instruction limit of each animation program run the hit starts
     * @throws IllegalStateException if the system is not attached
     */
    @Override
    public void dealWeaponDamageAt(@NotNull WeaponType weapon, @Nullable Unit attackerUnit, @Nullable Unit targetUnit, int x, int y,
                                   int instructionLimit) {
        if (targetUnit != null && this.isMiss(weapon, attackerUnit, targetUnit)) {
            return;
        }

        WeaponTypeTable weapons = requireAttached(this.content).getWeaponTypes();
        int outerRadius = weapons.getSplashOuterRadius(weapon);

        if (outerRadius <= 0) {
            if (targetUnit != null) {
                this.dealHit(weapon, attackerUnit, targetUnit, PERCENT, instructionLimit);
            }

            return;
        }

        UnitList found = this.collectSplashUnits(weapon, attackerUnit, targetUnit, x, y, outerRadius);
        long innerRadius = weapons.getSplashInnerRadius(weapon);
        long middleRadius = weapons.getSplashMiddleRadius(weapon);

        for (int index = 0; index < found.size(); index++) {
            Unit unit = found.get(index);
            long distanceX = unit.getPositionX() - x;
            long distanceY = unit.getPositionY() - y;
            long distance = distanceX * distanceX + distanceY * distanceY;
            int share;

            if (distance <= innerRadius * innerRadius) {
                share = this.innerSplashPercent;
            } else if (distance <= middleRadius * middleRadius) {
                share = this.middleSplashPercent;
            } else {
                share = this.outerSplashPercent;
            }

            this.dealHit(weapon, attackerUnit, unit, unit == targetUnit ? Math.max(share, this.innerSplashPercent) : share, instructionLimit);
        }
    }

    /**
     * Collects the units a splash around a point reaches: the target first, then the other units in slot order.
     */
    private @NotNull UnitList collectSplashUnits(@NotNull WeaponType weapon, @Nullable Unit attackerUnit, @Nullable Unit targetUnit, int x, int y,
                                                 int outerRadius) {
        UnitList found = this.splashUnits;
        long radius = outerRadius;

        found.clear();

        if (targetUnit != null) {
            found.add(targetUnit);
        }

        EntityPool<Unit> pool = requireAttached(this.units);
        SlotList used = pool.getUsedSlots();

        for (int index = used.getFirst(); index != SlotList.NONE && !found.isFull(); index = used.getNext(index)) {
            Unit unit = pool.get(index);
            long distanceX = unit.getPositionX() - x;
            long distanceY = unit.getPositionY() - y;

            if (unit != targetUnit && distanceX * distanceX + distanceY * distanceY <= radius * radius &&
                this.isSplashTarget(weapon, attackerUnit, unit)) {
                found.add(unit);
            }
        }

        return found;
    }

    private boolean isSplashTarget(@NotNull WeaponType weapon, @Nullable Unit attackerUnit, @NotNull Unit unit) {
        if (unit == attackerUnit || unit.getHitPoints() <= 0 || unit.getType() == null) {
            return false;
        }

        WeaponTarget target = this.getTargetSlot(unit) == WeaponSlot.AIR ? WeaponTarget.AIR : WeaponTarget.GROUND;

        if (!requireAttached(this.content).getWeaponTypes().hasTarget(weapon, target)) {
            return false;
        }

        return this.splashTargets == SplashTargets.ALL || attackerUnit == null || this.isEnemy(attackerUnit, unit);
    }

    private boolean isMiss(@NotNull WeaponType weapon, @Nullable Unit attackerUnit, @NotNull Unit targetUnit) {
        HookList<MissHook> hooks = requireAttached(this.missHooks);

        for (int index = 0; index < hooks.size(); index++) {
            MissAnswer answer = hooks.get(index).answerMiss(weapon, attackerUnit, targetUnit);

            if (answer != MissAnswer.NONE) {
                return answer == MissAnswer.MISS;
            }
        }

        return false;
    }

    /**
     * Deals one hit's share of a weapon's damage to a unit, reduced by its damage layers and armor and changed by the damage scale hooks.
     */
    private void dealHit(@NotNull WeaponType weapon, @Nullable Unit attackerUnit, @NotNull Unit targetUnit, int share, int instructionLimit) {
        if (targetUnit.getHitPoints() <= 0) {
            return;
        }

        GameContent attachedContent = requireAttached(this.content);
        WeaponTypeTable weapons = attachedContent.getWeaponTypes();
        Player attackerOwner = attackerUnit != null ? attackerUnit.getOwner() : null;
        Player targetOwner = targetUnit.getOwner();
        UnitType targetType = targetUnit.getType();
        int damage = weapons.getDamage(weapon);

        if (attackerUnit != null && attackerOwner != null) {
            damage = attachedContent.applyModifiers(Stat.WEAPON_DAMAGE, attackerOwner, attackerUnit.getType(), weapon, damage);
        }

        damage = (damage * weapons.getDamageFactor(weapon) << FRACTION_BITS) * share / PERCENT;

        DamageKind damageKind = weapons.getDamageKind(weapon);
        int absorbed = 0;

        if (targetType != null) {
            List<DamageLayer> layers = attachedContent.getDamageLayers(targetType);

            this.hit.set(targetUnit, attackerUnit, damageKind, damage);

            for (int index = 0; index < layers.size(); index++) {
                DamageLayer layer = layers.get(index);
                int layerDamage = layer.absorbDamage(this.hit);

                layer.setPoints(targetUnit, layer.getPoints(targetUnit) - layerDamage);
                absorbed += layerDamage;
            }

            damage = this.hit.getDamage();
        }

        int armor = this.getUnitStat(Stat.ARMOR, targetOwner, targetType,
                                     targetType != null ? attachedContent.getUnitTypes().getArmor(targetType) : 0) << FRACTION_BITS;

        damage = damage > armor ? damage - armor : 0;

        if (damageKind != null) {
            HookList<DamageScaleHook> hooks = requireAttached(this.damageScaleHooks);

            for (int index = 0; index < hooks.size(); index++) {
                damage = hooks.get(index).scaleDamage(damageKind, targetUnit, damage);
            }
        }

        if (absorbed == 0 && damage < this.minimumDamage) {
            damage = this.minimumDamage;
        }

        this.dealDamage(targetUnit, damage, attackerUnit, instructionLimit);
    }

    private boolean isTargetCandidate(@NotNull Unit candidate) {
        Unit unit = this.searchingUnit;

        return unit != null && this.canAttack(unit, candidate);
    }

    private int getUnitStat(@NotNull Stat stat, @Nullable Player owner, @Nullable UnitType type, int value) {
        return owner != null && type != null ? requireAttached(this.content).applyModifiers(stat, owner, type, null, value) : value;
    }

    private int getTeam(@Nullable Player owner) {
        return owner != null && owner.getIndex() < this.teams.length ? this.teams[owner.getIndex()] : NO_TEAM;
    }

    private @NotNull IntColumn getReadyFrames(@NotNull WeaponSlot slot) {
        return requireAttached(slot == WeaponSlot.GROUND ? this.groundReadyFrames : this.airReadyFrames);
    }
}
