package org.fogofwarlabs.sc4j.engine.content.table;

import org.fogofwarlabs.sc4j.engine.content.definition.UnitDefinition;
import org.fogofwarlabs.sc4j.engine.content.definition.UnitTrait;
import org.fogofwarlabs.sc4j.engine.content.economy.ResourceKind;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.registry.ContentDefinition;
import org.fogofwarlabs.sc4j.engine.content.registry.TableKind;
import org.fogofwarlabs.sc4j.engine.content.type.*;
import org.fogofwarlabs.sc4j.engine.game.ControllerKind;
import org.fogofwarlabs.sc4j.engine.state.StateHash;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotReader;
import org.fogofwarlabs.sc4j.engine.state.snapshot.StateSnapshotWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Holds the per-game values of every {@link UnitType} as one array per column, indexed by the type index.
 * <p>
 * The engine columns, in state hash order, are {@code faction}, {@code motionProfile}, {@code hitPoints}, {@code armor},
 * {@code armorUpgrade}, {@code sightRange}, {@code targetAcquisitionRange}, {@code groundWeapon},
 * {@code airWeapon}, {@code cost}, {@code buildTime}, {@code supplyProvided}, {@code supplyRequired},
 * {@code spaceRequired}, {@code spaceProvided}, {@code placementWidth}, {@code placementHeight},
 * {@code collisionLeft}, {@code collisionUp}, {@code collisionRight}, {@code collisionDown}, {@code traits} and
 * {@code idleOrder}. A cost column holds one value per type and resource kind, the idle order column one per type and
 * {@link ControllerKind}, both type-major. A type reference holds the index of the referenced type,
 * {@link ContentType#NO_INDEX} for none. The extension columns the mod registered for {@link TableKind#UNIT} follow
 * ({@link #getExtensionColumns()}).
 * <p>
 * The values start as the {@link UnitDefinition} of each type and may be written during the game; see {@link TypeTable}
 * for the write rules. Every accessor is a plain array access: an index outside the table throws
 * {@link ArrayIndexOutOfBoundsException}.
 */
public final class UnitTypeTable extends TypeTable {
    private final int resourceKindCount;
    private final int controllerKindCount;

    private final int @NotNull [] faction;
    private final int @NotNull [] motionProfile;
    private final int @NotNull [] hitPoints;
    private final int @NotNull [] armor;
    private final int @NotNull [] armorUpgrade;
    private final int @NotNull [] sightRange;
    private final int @NotNull [] targetAcquisitionRange;
    private final int @NotNull [] groundWeapon;
    private final int @NotNull [] airWeapon;
    private final int @NotNull [] cost;
    private final int @NotNull [] buildTime;
    private final int @NotNull [] supplyProvided;
    private final int @NotNull [] supplyRequired;
    private final int @NotNull [] spaceRequired;
    private final int @NotNull [] spaceProvided;
    private final int @NotNull [] placementWidth;
    private final int @NotNull [] placementHeight;
    private final int @NotNull [] collisionLeft;
    private final int @NotNull [] collisionUp;
    private final int @NotNull [] collisionRight;
    private final int @NotNull [] collisionDown;
    private final int @NotNull [] traits;
    private final int @NotNull [] idleOrder;

    /**
     * Creates a {@link UnitTypeTable} instance from the definitions of a content.
     *
     * @param content the content definition
     * @throws IllegalArgumentException if a definition refers to a type that is not registered in the content
     */
    UnitTypeTable(@NotNull ContentDefinition content) {
        super(content, content.getUnitTypes().getCount(), content.getColumns(TableKind.UNIT));

        DefinitionRegistry<UnitType, UnitDefinition> definitions = content.getUnitTypes();
        int count = this.getCount();

        this.resourceKindCount   = content.getResourceKinds().getCount();
        this.controllerKindCount = ControllerKind.values().length;

        this.faction                = new int[count];
        this.motionProfile          = new int[count];
        this.hitPoints              = new int[count];
        this.armor                  = new int[count];
        this.armorUpgrade           = new int[count];
        this.sightRange             = new int[count];
        this.targetAcquisitionRange = new int[count];
        this.groundWeapon           = new int[count];
        this.airWeapon              = new int[count];
        this.cost                   = new int[count * this.resourceKindCount];
        this.buildTime              = new int[count];
        this.supplyProvided         = new int[count];
        this.supplyRequired         = new int[count];
        this.spaceRequired          = new int[count];
        this.spaceProvided          = new int[count];
        this.placementWidth         = new int[count];
        this.placementHeight        = new int[count];
        this.collisionLeft          = new int[count];
        this.collisionUp            = new int[count];
        this.collisionRight         = new int[count];
        this.collisionDown          = new int[count];
        this.traits                 = new int[count];
        this.idleOrder              = new int[count * this.controllerKindCount];

        for (int index = 0; index < count; index++) {
            UnitDefinition definition = definitions.getDefinition(index);

            this.faction[index]                = content.getFactions().getTypes().requireIndex(definition.getFaction());
            this.motionProfile[index]          = content.getMotionProfiles().getTypes().requireIndex(definition.getMotionProfile());
            this.hitPoints[index]              = definition.getHitPoints();
            this.armor[index]                  = definition.getArmor();
            this.armorUpgrade[index]           = content.getUpgradeTypes().getTypes().requireIndex(definition.getArmorUpgrade());
            this.sightRange[index]             = definition.getSightRange();
            this.targetAcquisitionRange[index] = definition.getTargetAcquisitionRange();
            this.groundWeapon[index]           = content.getWeaponTypes().getTypes().requireIndex(definition.getGroundWeapon());
            this.airWeapon[index]              = content.getWeaponTypes().getTypes().requireIndex(definition.getAirWeapon());
            fillCost(content.getResourceKinds(), definition.getCost(), this.cost, index * this.resourceKindCount);
            this.buildTime[index]       = definition.getBuildTime();
            this.supplyProvided[index]  = definition.getSupplyProvided();
            this.supplyRequired[index]  = definition.getSupplyRequired();
            this.spaceRequired[index]   = definition.getSpaceRequired();
            this.spaceProvided[index]   = definition.getSpaceProvided();
            this.placementWidth[index]  = definition.getPlacementWidth();
            this.placementHeight[index] = definition.getPlacementHeight();
            this.collisionLeft[index]   = definition.getCollisionLeft();
            this.collisionUp[index]     = definition.getCollisionUp();
            this.collisionRight[index]  = definition.getCollisionRight();
            this.collisionDown[index]   = definition.getCollisionDown();
            this.traits[index]          = definition.getTraits();

            for (ControllerKind kind : ControllerKind.values()) {
                int idleOrder = content.getOrderTypes().getTypes().requireIndex(definition.getIdleOrder(kind));

                this.idleOrder[index * this.controllerKindCount + kind.ordinal()] = idleOrder;
            }
        }

        this.initializeExtensionColumns();
    }

    /**
     * @param type the unit type
     * @return the faction the unit type belongs to, or {@code null} for none
     * @throws IndexOutOfBoundsException if the stored index names no registered type
     */
    public @Nullable Faction getFaction(@NotNull UnitType type) {
        return resolve(this.getContent().getFactions().getTypes(), this.faction[type.getIndex()]);
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the index of the faction the unit type belongs to, or {@link ContentType#NO_INDEX} for none
     */
    public int getFactionIndex(int typeIndex) {
        return this.faction[typeIndex];
    }

    /**
     * Sets the faction the unit type belongs to.
     *
     * @param type  the unit type
     * @param value the faction registered in the same content, or {@code null} for none
     */
    public void setFaction(@NotNull UnitType type, @Nullable Faction value) {
        this.faction[type.getIndex()] = ContentType.indexOf(value);

        this.recordWrite();
    }

    /**
     * Sets the faction the unit type belongs to.
     *
     * @param typeIndex the index of the unit type
     * @param value     the index of the referenced type, or {@link ContentType#NO_INDEX} for none
     */
    public void setFactionIndex(int typeIndex, int value) {
        this.faction[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the motion profile that moves and draws the unit, or {@code null} for none
     * @throws IndexOutOfBoundsException if the stored index names no registered type
     */
    public @Nullable MotionProfile getMotionProfile(@NotNull UnitType type) {
        return resolve(this.getContent().getMotionProfiles().getTypes(), this.motionProfile[type.getIndex()]);
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the index of the motion profile that moves and draws the unit, or {@link ContentType#NO_INDEX} for none
     */
    public int getMotionProfileIndex(int typeIndex) {
        return this.motionProfile[typeIndex];
    }

    /**
     * Sets the motion profile that moves and draws the unit.
     *
     * @param type  the unit type
     * @param value the motion profile registered in the same content, or {@code null} for none
     */
    public void setMotionProfile(@NotNull UnitType type, @Nullable MotionProfile value) {
        this.motionProfile[type.getIndex()] = ContentType.indexOf(value);

        this.recordWrite();
    }

    /**
     * Sets the motion profile that moves and draws the unit.
     *
     * @param typeIndex the index of the unit type
     * @param value     the index of the referenced type, or {@link ContentType#NO_INDEX} for none
     */
    public void setMotionProfileIndex(int typeIndex, int value) {
        this.motionProfile[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the maximum hit points, in the hit point unit of the mod
     */
    public int getHitPoints(@NotNull UnitType type) {
        return this.hitPoints[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the maximum hit points, in the hit point unit of the mod
     */
    public int getHitPoints(int typeIndex) {
        return this.hitPoints[typeIndex];
    }

    /**
     * Sets the maximum hit points, in the hit point unit of the mod.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setHitPoints(@NotNull UnitType type, int value) {
        this.hitPoints[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the maximum hit points, in the hit point unit of the mod.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setHitPoints(int typeIndex, int value) {
        this.hitPoints[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the base armor
     */
    public int getArmor(@NotNull UnitType type) {
        return this.armor[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the base armor
     */
    public int getArmor(int typeIndex) {
        return this.armor[typeIndex];
    }

    /**
     * Sets the base armor.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setArmor(@NotNull UnitType type, int value) {
        this.armor[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the base armor.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setArmor(int typeIndex, int value) {
        this.armor[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the upgrade that raises the armor, or {@code null} for none
     * @throws IndexOutOfBoundsException if the stored index names no registered type
     */
    public @Nullable UpgradeType getArmorUpgrade(@NotNull UnitType type) {
        return resolve(this.getContent().getUpgradeTypes().getTypes(), this.armorUpgrade[type.getIndex()]);
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the index of the upgrade that raises the armor, or {@link ContentType#NO_INDEX} for none
     */
    public int getArmorUpgradeIndex(int typeIndex) {
        return this.armorUpgrade[typeIndex];
    }

    /**
     * Sets the upgrade that raises the armor.
     *
     * @param type  the unit type
     * @param value the upgrade type registered in the same content, or {@code null} for none
     */
    public void setArmorUpgrade(@NotNull UnitType type, @Nullable UpgradeType value) {
        this.armorUpgrade[type.getIndex()] = ContentType.indexOf(value);

        this.recordWrite();
    }

    /**
     * Sets the upgrade that raises the armor.
     *
     * @param typeIndex the index of the unit type
     * @param value     the index of the referenced type, or {@link ContentType#NO_INDEX} for none
     */
    public void setArmorUpgradeIndex(int typeIndex, int value) {
        this.armorUpgrade[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the sight range, in the distance unit of the mod
     */
    public int getSightRange(@NotNull UnitType type) {
        return this.sightRange[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the sight range, in the distance unit of the mod
     */
    public int getSightRange(int typeIndex) {
        return this.sightRange[typeIndex];
    }

    /**
     * Sets the sight range, in the distance unit of the mod.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setSightRange(@NotNull UnitType type, int value) {
        this.sightRange[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the sight range, in the distance unit of the mod.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setSightRange(int typeIndex, int value) {
        this.sightRange[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the range in which the unit picks targets by itself, in the distance unit of the mod
     */
    public int getTargetAcquisitionRange(@NotNull UnitType type) {
        return this.targetAcquisitionRange[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the range in which the unit picks targets by itself, in the distance unit of the mod
     */
    public int getTargetAcquisitionRange(int typeIndex) {
        return this.targetAcquisitionRange[typeIndex];
    }

    /**
     * Sets the range in which the unit picks targets by itself, in the distance unit of the mod.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setTargetAcquisitionRange(@NotNull UnitType type, int value) {
        this.targetAcquisitionRange[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the range in which the unit picks targets by itself, in the distance unit of the mod.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setTargetAcquisitionRange(int typeIndex, int value) {
        this.targetAcquisitionRange[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the weapon against ground targets, or {@code null} for none
     * @throws IndexOutOfBoundsException if the stored index names no registered type
     */
    public @Nullable WeaponType getGroundWeapon(@NotNull UnitType type) {
        return resolve(this.getContent().getWeaponTypes().getTypes(), this.groundWeapon[type.getIndex()]);
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the index of the weapon against ground targets, or {@link ContentType#NO_INDEX} for none
     */
    public int getGroundWeaponIndex(int typeIndex) {
        return this.groundWeapon[typeIndex];
    }

    /**
     * Sets the weapon against ground targets.
     *
     * @param type  the unit type
     * @param value the weapon type registered in the same content, or {@code null} for none
     */
    public void setGroundWeapon(@NotNull UnitType type, @Nullable WeaponType value) {
        this.groundWeapon[type.getIndex()] = ContentType.indexOf(value);

        this.recordWrite();
    }

    /**
     * Sets the weapon against ground targets.
     *
     * @param typeIndex the index of the unit type
     * @param value     the index of the referenced type, or {@link ContentType#NO_INDEX} for none
     */
    public void setGroundWeaponIndex(int typeIndex, int value) {
        this.groundWeapon[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the weapon against air targets, or {@code null} for none
     * @throws IndexOutOfBoundsException if the stored index names no registered type
     */
    public @Nullable WeaponType getAirWeapon(@NotNull UnitType type) {
        return resolve(this.getContent().getWeaponTypes().getTypes(), this.airWeapon[type.getIndex()]);
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the index of the weapon against air targets, or {@link ContentType#NO_INDEX} for none
     */
    public int getAirWeaponIndex(int typeIndex) {
        return this.airWeapon[typeIndex];
    }

    /**
     * Sets the weapon against air targets.
     *
     * @param type  the unit type
     * @param value the weapon type registered in the same content, or {@code null} for none
     */
    public void setAirWeapon(@NotNull UnitType type, @Nullable WeaponType value) {
        this.airWeapon[type.getIndex()] = ContentType.indexOf(value);

        this.recordWrite();
    }

    /**
     * Sets the weapon against air targets.
     *
     * @param typeIndex the index of the unit type
     * @param value     the index of the referenced type, or {@link ContentType#NO_INDEX} for none
     */
    public void setAirWeaponIndex(int typeIndex, int value) {
        this.airWeapon[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * Returns the amount of one resource kind of the cost of one unit.
     *
     * @param type the unit type
     * @param kind the resource kind
     * @return the amount
     */
    public int getCost(@NotNull UnitType type, @NotNull ResourceKind kind) {
        return this.cost[type.getIndex() * this.resourceKindCount + kind.getIndex()];
    }

    /**
     * Returns the amount of one resource kind of the cost of one unit.
     *
     * @param typeIndex         the index of the unit type
     * @param resourceKindIndex the index of the resource kind
     * @return the amount
     */
    public int getCost(int typeIndex, int resourceKindIndex) {
        return this.cost[typeIndex * this.resourceKindCount + resourceKindIndex];
    }

    /**
     * Sets the amount of one resource kind of the cost of one unit.
     *
     * @param type  the unit type
     * @param kind  the resource kind
     * @param value the amount
     */
    public void setCost(@NotNull UnitType type, @NotNull ResourceKind kind, int value) {
        this.cost[type.getIndex() * this.resourceKindCount + kind.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the amount of one resource kind of the cost of one unit.
     *
     * @param typeIndex         the index of the unit type
     * @param resourceKindIndex the index of the resource kind
     * @param value             the amount
     */
    public void setCost(int typeIndex, int resourceKindIndex, int value) {
        this.cost[typeIndex * this.resourceKindCount + resourceKindIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the build time in frames
     */
    public int getBuildTime(@NotNull UnitType type) {
        return this.buildTime[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the build time in frames
     */
    public int getBuildTime(int typeIndex) {
        return this.buildTime[typeIndex];
    }

    /**
     * Sets the build time in frames.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setBuildTime(@NotNull UnitType type, int value) {
        this.buildTime[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the build time in frames.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setBuildTime(int typeIndex, int value) {
        this.buildTime[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the supply the unit provides to its owner
     */
    public int getSupplyProvided(@NotNull UnitType type) {
        return this.supplyProvided[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the supply the unit provides to its owner
     */
    public int getSupplyProvided(int typeIndex) {
        return this.supplyProvided[typeIndex];
    }

    /**
     * Sets the supply the unit provides to its owner.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setSupplyProvided(@NotNull UnitType type, int value) {
        this.supplyProvided[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the supply the unit provides to its owner.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setSupplyProvided(int typeIndex, int value) {
        this.supplyProvided[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the supply the unit uses
     */
    public int getSupplyRequired(@NotNull UnitType type) {
        return this.supplyRequired[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the supply the unit uses
     */
    public int getSupplyRequired(int typeIndex) {
        return this.supplyRequired[typeIndex];
    }

    /**
     * Sets the supply the unit uses.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setSupplyRequired(@NotNull UnitType type, int value) {
        this.supplyRequired[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the supply the unit uses.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setSupplyRequired(int typeIndex, int value) {
        this.supplyRequired[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the transport space the unit takes
     */
    public int getSpaceRequired(@NotNull UnitType type) {
        return this.spaceRequired[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the transport space the unit takes
     */
    public int getSpaceRequired(int typeIndex) {
        return this.spaceRequired[typeIndex];
    }

    /**
     * Sets the transport space the unit takes.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setSpaceRequired(@NotNull UnitType type, int value) {
        this.spaceRequired[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the transport space the unit takes.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setSpaceRequired(int typeIndex, int value) {
        this.spaceRequired[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the transport space the unit offers
     */
    public int getSpaceProvided(@NotNull UnitType type) {
        return this.spaceProvided[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the transport space the unit offers
     */
    public int getSpaceProvided(int typeIndex) {
        return this.spaceProvided[typeIndex];
    }

    /**
     * Sets the transport space the unit offers.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setSpaceProvided(@NotNull UnitType type, int value) {
        this.spaceProvided[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the transport space the unit offers.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setSpaceProvided(int typeIndex, int value) {
        this.spaceProvided[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the width of the placement footprint in pixels
     */
    public int getPlacementWidth(@NotNull UnitType type) {
        return this.placementWidth[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the width of the placement footprint in pixels
     */
    public int getPlacementWidth(int typeIndex) {
        return this.placementWidth[typeIndex];
    }

    /**
     * Sets the width of the placement footprint in pixels.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setPlacementWidth(@NotNull UnitType type, int value) {
        this.placementWidth[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the width of the placement footprint in pixels.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setPlacementWidth(int typeIndex, int value) {
        this.placementWidth[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the height of the placement footprint in pixels
     */
    public int getPlacementHeight(@NotNull UnitType type) {
        return this.placementHeight[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the height of the placement footprint in pixels
     */
    public int getPlacementHeight(int typeIndex) {
        return this.placementHeight[typeIndex];
    }

    /**
     * Sets the height of the placement footprint in pixels.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setPlacementHeight(@NotNull UnitType type, int value) {
        this.placementHeight[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the height of the placement footprint in pixels.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setPlacementHeight(int typeIndex, int value) {
        this.placementHeight[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the extent of the collision box left of the position, in pixels
     */
    public int getCollisionLeft(@NotNull UnitType type) {
        return this.collisionLeft[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the extent of the collision box left of the position, in pixels
     */
    public int getCollisionLeft(int typeIndex) {
        return this.collisionLeft[typeIndex];
    }

    /**
     * Sets the extent of the collision box left of the position, in pixels.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setCollisionLeft(@NotNull UnitType type, int value) {
        this.collisionLeft[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the extent of the collision box left of the position, in pixels.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setCollisionLeft(int typeIndex, int value) {
        this.collisionLeft[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the extent of the collision box above the position, in pixels
     */
    public int getCollisionUp(@NotNull UnitType type) {
        return this.collisionUp[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the extent of the collision box above the position, in pixels
     */
    public int getCollisionUp(int typeIndex) {
        return this.collisionUp[typeIndex];
    }

    /**
     * Sets the extent of the collision box above the position, in pixels.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setCollisionUp(@NotNull UnitType type, int value) {
        this.collisionUp[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the extent of the collision box above the position, in pixels.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setCollisionUp(int typeIndex, int value) {
        this.collisionUp[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the extent of the collision box right of the position, in pixels
     */
    public int getCollisionRight(@NotNull UnitType type) {
        return this.collisionRight[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the extent of the collision box right of the position, in pixels
     */
    public int getCollisionRight(int typeIndex) {
        return this.collisionRight[typeIndex];
    }

    /**
     * Sets the extent of the collision box right of the position, in pixels.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setCollisionRight(@NotNull UnitType type, int value) {
        this.collisionRight[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the extent of the collision box right of the position, in pixels.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setCollisionRight(int typeIndex, int value) {
        this.collisionRight[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the extent of the collision box below the position, in pixels
     */
    public int getCollisionDown(@NotNull UnitType type) {
        return this.collisionDown[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the extent of the collision box below the position, in pixels
     */
    public int getCollisionDown(int typeIndex) {
        return this.collisionDown[typeIndex];
    }

    /**
     * Sets the extent of the collision box below the position, in pixels.
     *
     * @param type  the unit type
     * @param value the value
     */
    public void setCollisionDown(@NotNull UnitType type, int value) {
        this.collisionDown[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the extent of the collision box below the position, in pixels.
     *
     * @param typeIndex the index of the unit type
     * @param value     the value
     */
    public void setCollisionDown(int typeIndex, int value) {
        this.collisionDown[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * @param type the unit type
     * @return the traits, a mask of {@link UnitTrait#getMask()} bits
     */
    public int getTraits(@NotNull UnitType type) {
        return this.traits[type.getIndex()];
    }

    /**
     * @param typeIndex the index of the unit type
     * @return the traits, a mask of {@link UnitTrait#getMask()} bits
     */
    public int getTraits(int typeIndex) {
        return this.traits[typeIndex];
    }

    /**
     * Sets the traits.
     *
     * @param type  the unit type
     * @param value a mask of {@link UnitTrait#getMask()} bits
     */
    public void setTraits(@NotNull UnitType type, int value) {
        this.traits[type.getIndex()] = value;

        this.recordWrite();
    }

    /**
     * Sets the traits.
     *
     * @param typeIndex the index of the unit type
     * @param value     a mask of {@link UnitTrait#getMask()} bits
     */
    public void setTraits(int typeIndex, int value) {
        this.traits[typeIndex] = value;

        this.recordWrite();
    }

    /**
     * Checks one of the traits.
     *
     * @param type  the unit type
     * @param trait the trait
     * @return {@code true} if the type has the trait, {@code false} otherwise
     */
    public boolean hasTrait(@NotNull UnitType type, @NotNull UnitTrait trait) {
        return (this.traits[type.getIndex()] & trait.getMask()) != 0;
    }

    /**
     * Checks one of the traits.
     *
     * @param typeIndex the index of the unit type
     * @param trait     the trait
     * @return {@code true} if the type has the trait, {@code false} otherwise
     */
    public boolean hasTrait(int typeIndex, @NotNull UnitTrait trait) {
        return (this.traits[typeIndex] & trait.getMask()) != 0;
    }

    /**
     * Returns the order a unit of the type idles with when its owner has one controller kind: the order it starts with once completed.
     *
     * @param type the unit type
     * @param kind the controller kind of the owner
     * @return the order type, or {@code null} for none
     * @throws IndexOutOfBoundsException if the stored index names no registered type
     */
    public @Nullable OrderType getIdleOrder(@NotNull UnitType type, @NotNull ControllerKind kind) {
        return resolve(this.getContent().getOrderTypes().getTypes(), this.idleOrder[type.getIndex() * this.controllerKindCount + kind.ordinal()]);
    }

    /**
     * @param typeIndex the index of the unit type
     * @param kind      the controller kind of the owner
     * @return the index of the idle order, or {@link ContentType#NO_INDEX} for none
     */
    public int getIdleOrderIndex(int typeIndex, @NotNull ControllerKind kind) {
        return this.idleOrder[typeIndex * this.controllerKindCount + kind.ordinal()];
    }

    /**
     * Sets the order a unit of the type idles with when its owner has one controller kind.
     *
     * @param type  the unit type
     * @param kind  the controller kind of the owner
     * @param value the order type registered in the same content, or {@code null} for none
     */
    public void setIdleOrder(@NotNull UnitType type, @NotNull ControllerKind kind, @Nullable OrderType value) {
        this.idleOrder[type.getIndex() * this.controllerKindCount + kind.ordinal()] = ContentType.indexOf(value);

        this.recordWrite();
    }

    /**
     * Sets the order a unit of the type idles with when its owner has one controller kind.
     *
     * @param typeIndex the index of the unit type
     * @param kind      the controller kind of the owner
     * @param value     the index of the referenced type, or {@link ContentType#NO_INDEX} for none
     */
    public void setIdleOrderIndex(int typeIndex, @NotNull ControllerKind kind, int value) {
        this.idleOrder[typeIndex * this.controllerKindCount + kind.ordinal()] = value;

        this.recordWrite();
    }


    /**
     * Mixes the engine columns into the hash, column by column in the order of the class description, each in type
     * index order.
     *
     * @param hash the hash being computed
     */
    @Override
    void contributeEngineColumns(@NotNull StateHash hash) {
        mixColumn(hash, this.faction);
        mixColumn(hash, this.motionProfile);
        mixColumn(hash, this.hitPoints);
        mixColumn(hash, this.armor);
        mixColumn(hash, this.armorUpgrade);
        mixColumn(hash, this.sightRange);
        mixColumn(hash, this.targetAcquisitionRange);
        mixColumn(hash, this.groundWeapon);
        mixColumn(hash, this.airWeapon);
        mixColumn(hash, this.cost);
        mixColumn(hash, this.buildTime);
        mixColumn(hash, this.supplyProvided);
        mixColumn(hash, this.supplyRequired);
        mixColumn(hash, this.spaceRequired);
        mixColumn(hash, this.spaceProvided);
        mixColumn(hash, this.placementWidth);
        mixColumn(hash, this.placementHeight);
        mixColumn(hash, this.collisionLeft);
        mixColumn(hash, this.collisionUp);
        mixColumn(hash, this.collisionRight);
        mixColumn(hash, this.collisionDown);
        mixColumn(hash, this.traits);
        mixColumn(hash, this.idleOrder);
    }

    /**
     * Writes the engine columns, column by column in the order of their hash, each whole in type index order.
     *
     * @param writer the writer of the snapshot
     */
    @Override
    void writeEngineColumns(@NotNull StateSnapshotWriter writer) {
        writer.writeInts(this.faction);
        writer.writeInts(this.motionProfile);
        writer.writeInts(this.hitPoints);
        writer.writeInts(this.armor);
        writer.writeInts(this.armorUpgrade);
        writer.writeInts(this.sightRange);
        writer.writeInts(this.targetAcquisitionRange);
        writer.writeInts(this.groundWeapon);
        writer.writeInts(this.airWeapon);
        writer.writeInts(this.cost);
        writer.writeInts(this.buildTime);
        writer.writeInts(this.supplyProvided);
        writer.writeInts(this.supplyRequired);
        writer.writeInts(this.spaceRequired);
        writer.writeInts(this.spaceProvided);
        writer.writeInts(this.placementWidth);
        writer.writeInts(this.placementHeight);
        writer.writeInts(this.collisionLeft);
        writer.writeInts(this.collisionUp);
        writer.writeInts(this.collisionRight);
        writer.writeInts(this.collisionDown);
        writer.writeInts(this.traits);
        writer.writeInts(this.idleOrder);
    }

    /**
     * Reads the engine columns back, column by column in the order of their hash, replacing the current values.
     *
     * @param reader the reader of the snapshot
     * @throws IOException if the snapshot ends early or a column holds another number of values
     */
    @Override
    void readEngineColumns(@NotNull StateSnapshotReader reader) throws IOException {
        reader.readInts(this.faction);
        reader.readInts(this.motionProfile);
        reader.readInts(this.hitPoints);
        reader.readInts(this.armor);
        reader.readInts(this.armorUpgrade);
        reader.readInts(this.sightRange);
        reader.readInts(this.targetAcquisitionRange);
        reader.readInts(this.groundWeapon);
        reader.readInts(this.airWeapon);
        reader.readInts(this.cost);
        reader.readInts(this.buildTime);
        reader.readInts(this.supplyProvided);
        reader.readInts(this.supplyRequired);
        reader.readInts(this.spaceRequired);
        reader.readInts(this.spaceProvided);
        reader.readInts(this.placementWidth);
        reader.readInts(this.placementHeight);
        reader.readInts(this.collisionLeft);
        reader.readInts(this.collisionUp);
        reader.readInts(this.collisionRight);
        reader.readInts(this.collisionDown);
        reader.readInts(this.traits);
        reader.readInts(this.idleOrder);
    }
}
