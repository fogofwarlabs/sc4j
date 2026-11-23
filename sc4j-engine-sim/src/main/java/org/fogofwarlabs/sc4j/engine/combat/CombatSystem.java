package org.fogofwarlabs.sc4j.engine.combat;

import org.fogofwarlabs.sc4j.engine.content.type.WeaponType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.game.system.GameSystem;
import org.fogofwarlabs.sc4j.engine.game.system.SystemKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decides and carries out attacks: who is an enemy, whether a unit can attack a target and reach it with a weapon,
 * pursuing an attack target and dealing damage. Orders, animations (weapon fire), abilities and computer players call
 * it; the rules of a mod (damage types, armor, splash, retaliation) live in its implementation or its hooks. Starting an
 * attack is an order of the unit, not an operation of this system.
 * <p>
 * Determinism contract: the checks read the game state only, draw no random value and allocate nothing.
 * {@link #pursueTarget} and {@link #dealDamage} run at the point of the frame their caller runs, draw random values only
 * from the game generator with the sources the implementation documents (animation programs they start included,
 * bounded by the instruction limit) and visit affected units in spatial index order.
 */
public interface CombatSystem extends GameSystem {
    /**
     * The key of the combat system.
     */
    @NotNull SystemKey<CombatSystem> KEY = SystemKey.of("combat", CombatSystem.class);

    /**
     * Checks whether a unit treats another unit as an enemy.
     *
     * @param unit      the unit
     * @param otherUnit the other unit
     * @return {@code true} if the owners are enemies, {@code false} otherwise
     */
    boolean isEnemy(@NotNull Unit unit, @NotNull Unit otherUnit);

    /**
     * Checks whether a unit can attack a target at all: it has a weapon for the target and the target can be attacked.
     *
     * @param unit       the attacking unit
     * @param targetUnit the target unit, or {@code null} for none
     * @return {@code true} if the unit can attack the target, {@code false} otherwise or without target
     */
    boolean canAttack(@NotNull Unit unit, @Nullable Unit targetUnit);

    /**
     * Checks whether a target is within the range of the weapon a unit would use against it.
     *
     * @param unit       the attacking unit
     * @param targetUnit the target unit
     * @return {@code true} if the target is in range, {@code false} otherwise
     */
    boolean isInRange(@NotNull Unit unit, @NotNull Unit targetUnit);

    /**
     * Returns the weapon of a unit for a kind of target.
     *
     * @param unit the unit
     * @param slot the kind of target
     * @return the weapon, or {@code null} if the unit has no such weapon
     */
    @Nullable WeaponType getWeapon(@NotNull Unit unit, @NotNull WeaponSlot slot);

    /**
     * Keeps a unit after the target of its attack: it moves towards the target until the target is in range.
     *
     * @param unit             the attacking unit
     * @param targetUnit       the target unit
     * @param instructionLimit the instruction limit of each animation program run the pursuit starts
     * @return {@code true} if the pursuit goes on, {@code false} if it ended: the unit arrived without reaching the target, or another system took the unit over
     */
    boolean pursueTarget(@NotNull Unit unit, @NotNull Unit targetUnit, int instructionLimit);

    /**
     * Deals damage to a unit that armor and damage layers no longer reduce; a unit whose hit points run out dies.
     *
     * @param targetUnit       the damaged unit
     * @param amount           the damage, in the implementation's fixed-point unit
     * @param attackerUnit     the attacking unit, or {@code null} without attacker
     * @param instructionLimit the instruction limit of each animation program run the damage starts
     */
    void dealDamage(@NotNull Unit targetUnit, int amount, @Nullable Unit attackerUnit, int instructionLimit);

    /**
     * Deals the damage of one hit of a weapon to a unit: the weapon's damage after the attacker's modifiers, reduced by the unit's damage layers
     * ({@link org.fogofwarlabs.sc4j.engine.content.combat.DamageLayer}) and armor by the rules of the implementation, then {@link #dealDamage}. A
     * projectile system calls it when a projectile arrives.
     *
     * @param weapon           the weapon
     * @param attackerUnit     the attacking unit, or {@code null} if it no longer lives
     * @param targetUnit       the hit unit
     * @param instructionLimit the instruction limit of each animation program run the hit starts
     */
    void dealWeaponDamage(@NotNull WeaponType weapon, @Nullable Unit attackerUnit, @NotNull Unit targetUnit, int instructionLimit);

    /**
     * Deals the damage of one hit of a weapon at a point: the hit of {@link #dealWeaponDamage} on the target, if there is one, and, for an
     * implementation with splash damage, the units around the point. A projectile system that does not home calls it where its projectile
     * lands. The default hits only the target.
     *
     * @param weapon           the weapon
     * @param attackerUnit     the attacking unit, or {@code null} if it no longer lives
     * @param targetUnit       the unit hit at the point, or {@code null} if the hit lands on no unit
     * @param x                the x coordinate of the point
     * @param y                the y coordinate of the point
     * @param instructionLimit the instruction limit of each animation program run the hit starts
     */
    default void dealWeaponDamageAt(@NotNull WeaponType weapon, @Nullable Unit attackerUnit, @Nullable Unit targetUnit, int x, int y,
                                    int instructionLimit) {
        if (targetUnit != null) {
            this.dealWeaponDamage(weapon, attackerUnit, targetUnit, instructionLimit);
        }
    }
}
