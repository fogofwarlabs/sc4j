package org.fogofwarlabs.sc4j.demo.game;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.demo.content.DemoContent;
import org.fogofwarlabs.sc4j.demo.map.DemoMaps;
import org.fogofwarlabs.sc4j.demo.order.DemoOrders;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.faction.FactionDefinition;
import org.fogofwarlabs.sc4j.engine.content.type.UnitType;
import org.fogofwarlabs.sc4j.engine.entity.Unit;
import org.fogofwarlabs.sc4j.engine.entity.pool.EntityPool;
import org.fogofwarlabs.sc4j.engine.entity.pool.SlotList;
import org.fogofwarlabs.sc4j.engine.game.*;
import org.fogofwarlabs.sc4j.engine.map.MapModel;
import org.fogofwarlabs.sc4j.engine.map.MapPlacementUtils;
import org.fogofwarlabs.sc4j.engine.map.StandardMapData;
import org.fogofwarlabs.sc4j.engine.map.StartLocation;
import org.fogofwarlabs.sc4j.engine.net.lobby.LobbyMember;
import org.fogofwarlabs.sc4j.engine.order.IssueOrderCommand;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.engine.trigger.PlayerOutcome;
import org.fogofwarlabs.sc4j.engine.trigger.StandardTriggerSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Creates the demo's games from the demo's game definition: the setups of the menu's game kinds and new games of a setup in their start
 * state, and reads how a game ends.
 * <p>
 * The start state: every unit type of the content is available to every player, each player has {@link #START_GOLD} gold, and the map places
 * its gold mines for the neutral slot, then the town hall and the start workers of each player's faction at the player's start location.
 */
public final class DemoGames {
    /**
     * The gold every player starts with.
     */
    public static final int START_GOLD = 50;
    /**
     * The slot of the first player, at the first start location.
     */
    public static final int FIRST_SLOT = 0;
    /**
     * The slot of the second player, at the second start location.
     */
    public static final int SECOND_SLOT = 1;

    /**
     * Checks whether a game of the demo is decided.
     *
     * @param game the game
     * @return {@code true} if every player has an outcome, {@code false} otherwise
     */
    public static boolean isOver(@NotNull Game game) {
        return ((StandardTriggerSystem) game.getTriggers()).isGameOver();
    }

    /**
     * Returns the outcome of a player of a game of the demo.
     *
     * @param game   the game
     * @param player the player
     * @return the outcome, {@link PlayerOutcome#PLAYING} while the player plays on
     */
    public static @NotNull PlayerOutcome getOutcome(@NotNull Game game, @NotNull Player player) {
        return ((StandardTriggerSystem) game.getTriggers()).getOutcome(player);
    }

    private static @Nullable LobbyMember findMember(@NotNull List<@NotNull LobbyMember> members, int slotIndex) {
        for (LobbyMember member : members) {
            if (member.getSlotIndex() == slotIndex) {
                return member;
            }
        }

        return null;
    }


    private final @NotNull GameDefinition definition;
    private final @NotNull DemoContent content;
    private final @NotNull DemoMaps maps;

    /**
     * Creates a {@link DemoGames} instance.
     *
     * @param definition the game definition of the demo, built from {@link org.fogofwarlabs.sc4j.demo.rules.DemoRules}
     * @param content    the demo's content
     * @param maps       the demo's maps
     */
    public DemoGames(@NotNull GameDefinition definition, @NotNull DemoContent content, @NotNull DemoMaps maps) {
        this.definition = definition;
        this.content    = content;
        this.maps       = maps;
    }

    /**
     * Creates the setup of a game of two computer players on a two-player map: Dawnhold in the first slot, Cindermaw in the second, and the
     * map's neutral slot.
     *
     * @param map  the map reference, one of {@link DemoMaps#getReferences()}
     * @param seed the seed of the game's random generator
     * @return the created {@link GameSetup} instance
     * @throws IllegalArgumentException if the demo has no such map
     */
    public @NotNull GameSetup createComputerSetup(@NotNull MapReference map, long seed) {
        int neutralSlot = this.maps.require(map).requireModData(StandardMapData.class).getNeutralSlotIndex();

        return new GameSetup.Builder(map)
                .addPlayer(new PlayerSlot(FIRST_SLOT, ControllerKind.COMPUTER, FIRST_SLOT, FIRST_SLOT, this.content.getDawnhold()))
                .addPlayer(new PlayerSlot(SECOND_SLOT, ControllerKind.COMPUTER, SECOND_SLOT, SECOND_SLOT, this.content.getCindermaw()))
                .addPlayer(new PlayerSlot(neutralSlot, ControllerKind.NEUTRAL, neutralSlot, neutralSlot, null))
                .setSeed(seed)
                .build();
    }

    /**
     * Creates the setup of a game of the person at this machine against a computer player on a two-player map: the person in the first slot,
     * the computer in the second, and the map's neutral slot.
     *
     * @param map             the map reference, one of {@link DemoMaps#getReferences()}
     * @param faction         the person's faction
     * @param opponentFaction the computer player's faction
     * @param seed            the seed of the game's random generator
     * @return the created {@link GameSetup} instance
     * @throws IllegalArgumentException if the demo has no such map
     */
    public @NotNull GameSetup createPersonSetup(@NotNull MapReference map, @NotNull Faction faction, @NotNull Faction opponentFaction, long seed) {
        int neutralSlot = this.maps.require(map).requireModData(StandardMapData.class).getNeutralSlotIndex();

        return new GameSetup.Builder(map)
                .addPlayer(new PlayerSlot(FIRST_SLOT, ControllerKind.HUMAN, FIRST_SLOT, FIRST_SLOT, faction))
                .addPlayer(new PlayerSlot(SECOND_SLOT, ControllerKind.COMPUTER, SECOND_SLOT, SECOND_SLOT, opponentFaction))
                .addPlayer(new PlayerSlot(neutralSlot, ControllerKind.NEUTRAL, neutralSlot, neutralSlot, null))
                .setSeed(seed)
                .build();
    }

    /**
     * Creates the setup of a network game on a two-player map from the members of its room: each member with a slot plays it with the faction
     * it chose (Dawnhold in the first slot and Cindermaw in the second if it chose none), a slot no member takes is a computer player of its
     * slot's faction, and the map's neutral slot. The members' slots are {@link ControllerKind#HUMAN} for people, or
     * {@link ControllerKind#COMPUTER} for computer players every machine runs.
     *
     * @param map     the map reference, one of {@link DemoMaps#getReferences()}
     * @param members the room's members; observers take no slot
     * @param kind    the controller kind of the members' slots
     * @param seed    the seed of the game's random generator
     * @return the created {@link GameSetup} instance
     * @throws IllegalArgumentException if the demo has no such map, or a member takes another slot than the first or the second
     */
    public @NotNull GameSetup createNetworkSetup(@NotNull MapReference map, @NotNull List<@NotNull LobbyMember> members, @NotNull ControllerKind kind,
                                                 long seed) {
        int neutralSlot = this.maps.require(map).requireModData(StandardMapData.class).getNeutralSlotIndex();
        GameSetup.Builder builder = new GameSetup.Builder(map);
        Faction[] defaultFactions = {this.content.getDawnhold(), this.content.getCindermaw()};

        for (LobbyMember member : members) {
            if (!member.isObserver() && member.getSlotIndex() != FIRST_SLOT && member.getSlotIndex() != SECOND_SLOT) {
                throw Exceptions.newIllegalArgumentException("{0} takes no slot of a two-player game.", member);
            }
        }

        for (int slot = FIRST_SLOT; slot <= SECOND_SLOT; slot++) {
            LobbyMember member = findMember(members, slot);
            Faction faction = member != null && member.getFaction() != null ? member.getFaction() : defaultFactions[slot];

            builder.addPlayer(new PlayerSlot(slot, member != null ? kind : ControllerKind.COMPUTER, slot, slot, faction));
        }

        return builder.addPlayer(new PlayerSlot(neutralSlot, ControllerKind.NEUTRAL, neutralSlot, neutralSlot, null))
                      .setSeed(seed)
                      .build();
    }

    /**
     * Creates the order of a player to send its first worker, in unit slot order, to the first start location of the map that is not the
     * player's: a person scouting. The move order replaces the worker's orders, so a game that runs it differs from one that does not.
     *
     * @param game   the game, whose map is one of the demo's maps
     * @param player the player
     * @return the created {@link IssueOrderCommand} instance, or {@code null} if the player has no faction, no worker type or no worker, or the map no other start location
     * @throws IllegalArgumentException if the demo has no map of the game's setup
     */
    public @Nullable IssueOrderCommand createScoutOrder(@NotNull Game game, @NotNull Player player) {
        Faction faction = null;

        for (PlayerSlot slot : game.getSetup().getPlayers()) {
            if (slot.getSlotIndex() == player.getIndex()) {
                faction = slot.getFaction();
            }
        }

        FactionDefinition definition = faction == null ? null : this.definition.getContent().getFactions().getDefinition(faction);
        UnitType worker = definition == null ? null : definition.getWorker();
        StartLocation target = null;

        for (StartLocation location : this.maps.require(game.getSetup()).getStartLocations()) {
            if (target == null && location.getSlotIndex() != player.getIndex()) {
                target = location;
            }
        }

        EntityPool<Unit> units = game.getUnits();
        SlotList used = units.getUsedSlots();

        for (int index = used.getFirst(); index != SlotList.NONE && worker != null && target != null; index = used.getNext(index)) {
            Unit unit = units.get(index);

            if (unit.getOwner() == player && unit.getType() == worker) {
                DemoOrders orders = this.content.getOrders();

                return orders.getOrderCommand().create(game, unit, orders.getMove(), target.getX(), target.getY(), null, false);
            }
        }

        return null;
    }

    /**
     * Creates a game of a setup in its start state, ready for its first frame.
     *
     * @param setup the setup, whose map is one of the demo's maps
     * @return the created {@link Game} instance
     * @throws IllegalArgumentException if the demo has no map of the setup's name and digest
     */
    public @NotNull Game createGame(@NotNull GameSetup setup) {
        MapModel map = this.maps.require(setup);
        Game game = Game.create(this.definition, setup);

        for (int index = 0; index < game.getPlayerCount(); index++) {
            Player player = game.getPlayer(index);

            for (UnitType type : this.content.getUnitTypes()) {
                game.getContent().getProgress().setUnitAvailable(player, type, true);
            }

            game.getEconomy().setAmount(player, this.content.getGold(), START_GOLD);
        }

        MapPlacementUtils.placeUnits(game, map);

        return game;
    }
}
