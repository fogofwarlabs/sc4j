package org.fogofwarlabs.sc4j.engine.content.definition;

import org.fogofwarlabs.sc4j.engine.content.combat.DamageKind;
import org.fogofwarlabs.sc4j.engine.content.type.MotionProfile;
import org.fogofwarlabs.sc4j.engine.content.type.UpgradeType;
import org.fogofwarlabs.sc4j.engine.content.type.WeaponType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the initial values of one {@link WeaponType}: what each game copies into its weapon type table when it is
 * created. A definition is immutable; type references are identities that must be registered in the same content.
 */
public final class WeaponDefinition {
    /**
     * Builds {@link WeaponDefinition} instances. Every value starts at {@code 0}, {@code false} or {@code null}
     * unless its setter says otherwise.
     */
    public static final class Builder {
        private int damage;
        private int damageBonus;
        private int damageFactor;
        private @Nullable DamageKind damageKind;
        private @Nullable UpgradeType upgrade;
        private int cooldown;
        private int minimumRange;
        private int maximumRange;
        private int targets;
        private @Nullable MotionProfile projectile;
        private boolean homing;
        private int splashInnerRadius;
        private int splashMiddleRadius;
        private int splashOuterRadius;

        /**
         * Creates a {@link Builder} instance with the default values.
         */
        public Builder() {
            this.damage             = 0;
            this.damageBonus        = 0;
            this.damageFactor       = 0;
            this.damageKind         = null;
            this.upgrade            = null;
            this.cooldown           = 0;
            this.minimumRange       = 0;
            this.maximumRange       = 0;
            this.targets            = 0;
            this.projectile         = null;
            this.homing             = true;
            this.splashInnerRadius  = 0;
            this.splashMiddleRadius = 0;
            this.splashOuterRadius  = 0;
        }

        /**
         * Sets the base damage of one hit.
         *
         * @param damage the value
         * @return this instance for chaining
         */
        public @NotNull Builder setDamage(int damage) {
            this.damage = damage;

            return this;
        }

        /**
         * Sets the damage added per level of the weapon upgrade.
         *
         * @param damageBonus the value
         * @return this instance for chaining
         */
        public @NotNull Builder setDamageBonus(int damageBonus) {
            this.damageBonus = damageBonus;

            return this;
        }

        /**
         * Sets the number of hits of one attack.
         *
         * @param damageFactor the value
         * @return this instance for chaining
         */
        public @NotNull Builder setDamageFactor(int damageFactor) {
            this.damageFactor = damageFactor;

            return this;
        }

        /**
         * Sets the damage kind.
         *
         * @param damageKind the damage kind registered in the same content, or {@code null} for none
         * @return this instance for chaining
         */
        public @NotNull Builder setDamageKind(@Nullable DamageKind damageKind) {
            this.damageKind = damageKind;

            return this;
        }

        /**
         * Sets the upgrade that raises the damage.
         *
         * @param upgrade the type, or {@code null} for none
         * @return this instance for chaining
         */
        public @NotNull Builder setUpgrade(@Nullable UpgradeType upgrade) {
            this.upgrade = upgrade;

            return this;
        }

        /**
         * Sets the frames between two attacks.
         *
         * @param cooldown the value
         * @return this instance for chaining
         */
        public @NotNull Builder setCooldown(int cooldown) {
            this.cooldown = cooldown;

            return this;
        }

        /**
         * Sets the minimum range, in the distance unit of the mod.
         *
         * @param minimumRange the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMinimumRange(int minimumRange) {
            this.minimumRange = minimumRange;

            return this;
        }

        /**
         * Sets the maximum range, in the distance unit of the mod.
         *
         * @param maximumRange the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumRange(int maximumRange) {
            this.maximumRange = maximumRange;

            return this;
        }

        /**
         * Adds one of the targets the weapon can attack.
         *
         * @param target the target
         * @return this instance for chaining
         */
        public @NotNull Builder addTarget(@NotNull WeaponTarget target) {
            this.targets |= target.getMask();

            return this;
        }

        /**
         * Sets the motion profile of the projectile.
         *
         * @param projectile the type, or {@code null} for none
         * @return this instance for chaining
         */
        public @NotNull Builder setProjectile(@Nullable MotionProfile projectile) {
            this.projectile = projectile;

            return this;
        }

        /**
         * Sets whether the projectile follows its target: a homing projectile hits the target where it is, one that does not home lands where
         * the target stood when it was launched.
         *
         * @param homing the value, {@code true} by default
         * @return this instance for chaining
         */
        public @NotNull Builder setHoming(boolean homing) {
            this.homing = homing;

            return this;
        }

        /**
         * Sets the radius of full splash damage, in pixels.
         *
         * @param splashInnerRadius the value
         * @return this instance for chaining
         */
        public @NotNull Builder setSplashInnerRadius(int splashInnerRadius) {
            this.splashInnerRadius = splashInnerRadius;

            return this;
        }

        /**
         * Sets the radius of half splash damage, in pixels.
         *
         * @param splashMiddleRadius the value
         * @return this instance for chaining
         */
        public @NotNull Builder setSplashMiddleRadius(int splashMiddleRadius) {
            this.splashMiddleRadius = splashMiddleRadius;

            return this;
        }

        /**
         * Sets the radius of quarter splash damage, in pixels.
         *
         * @param splashOuterRadius the value
         * @return this instance for chaining
         */
        public @NotNull Builder setSplashOuterRadius(int splashOuterRadius) {
            this.splashOuterRadius = splashOuterRadius;

            return this;
        }


        /**
         * Builds a {@link WeaponDefinition} instance from the current values.
         *
         * @return the built {@link WeaponDefinition} instance
         */
        public @NotNull WeaponDefinition build() {
            return new WeaponDefinition(this);
        }
    }

    private final int damage;
    private final int damageBonus;
    private final int damageFactor;
    private final @Nullable DamageKind damageKind;
    private final @Nullable UpgradeType upgrade;
    private final int cooldown;
    private final int minimumRange;
    private final int maximumRange;
    private final int targets;
    private final @Nullable MotionProfile projectile;
    private final boolean homing;
    private final int splashInnerRadius;
    private final int splashMiddleRadius;
    private final int splashOuterRadius;

    private WeaponDefinition(@NotNull Builder builder) {
        this.damage             = builder.damage;
        this.damageBonus        = builder.damageBonus;
        this.damageFactor       = builder.damageFactor;
        this.damageKind         = builder.damageKind;
        this.upgrade            = builder.upgrade;
        this.cooldown           = builder.cooldown;
        this.minimumRange       = builder.minimumRange;
        this.maximumRange       = builder.maximumRange;
        this.targets            = builder.targets;
        this.projectile         = builder.projectile;
        this.homing             = builder.homing;
        this.splashInnerRadius  = builder.splashInnerRadius;
        this.splashMiddleRadius = builder.splashMiddleRadius;
        this.splashOuterRadius  = builder.splashOuterRadius;
    }

    /**
     * @return the base damage of one hit
     */
    public int getDamage() {
        return this.damage;
    }

    /**
     * @return the damage added per level of the weapon upgrade
     */
    public int getDamageBonus() {
        return this.damageBonus;
    }

    /**
     * @return the number of hits of one attack
     */
    public int getDamageFactor() {
        return this.damageFactor;
    }

    /**
     * @return the damage kind, or {@code null} for none
     */
    public @Nullable DamageKind getDamageKind() {
        return this.damageKind;
    }

    /**
     * @return the upgrade that raises the damage, or {@code null} for none
     */
    public @Nullable UpgradeType getUpgrade() {
        return this.upgrade;
    }

    /**
     * @return the frames between two attacks
     */
    public int getCooldown() {
        return this.cooldown;
    }

    /**
     * @return the minimum range, in the distance unit of the mod
     */
    public int getMinimumRange() {
        return this.minimumRange;
    }

    /**
     * @return the maximum range, in the distance unit of the mod
     */
    public int getMaximumRange() {
        return this.maximumRange;
    }

    /**
     * @return the targets the weapon can attack, a mask of {@link WeaponTarget#getMask()} bits
     */
    public int getTargets() {
        return this.targets;
    }

    /**
     * @return the motion profile of the projectile, or {@code null} for none
     */
    public @Nullable MotionProfile getProjectile() {
        return this.projectile;
    }

    /**
     * @return {@code true} if the projectile follows its target, {@code false} otherwise; a projectile that does not home lands where the target stood at its launch
     */
    public boolean isHoming() {
        return this.homing;
    }

    /**
     * @return the radius of full splash damage, in pixels
     */
    public int getSplashInnerRadius() {
        return this.splashInnerRadius;
    }

    /**
     * @return the radius of half splash damage, in pixels
     */
    public int getSplashMiddleRadius() {
        return this.splashMiddleRadius;
    }

    /**
     * @return the radius of quarter splash damage, in pixels
     */
    public int getSplashOuterRadius() {
        return this.splashOuterRadius;
    }
}
