package org.fogofwarlabs.sc4j.engine.game;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

/**
 * Writes and reads a {@link GameSetup} in the engine's wire form, for the network lobby's start and the engine replay: the map reference, the
 * player slots, the seed, the options and the pool sizes. Factions and options cross by their position in the codec's lists, which every machine
 * of a mod builds in the same order; option values are {@link Integer}, {@link Long}, {@link Boolean} or {@link String} objects. A setup
 * with a saved random state or its own random generator has no wire form: a saved game carries its random state itself. The map reference
 * and the faction have their own wire forms ({@link #writeMap}, {@link #writeFaction}), for a lobby that names them before the start.
 * <p>
 * Wire format ({@link DataOutput}, big-endian): the map name (modified UTF-8), the map digest (its length in four bytes, then its bytes), the
 * player count (one byte) and per player its slot index, controller kind ({@link ControllerKind} position), team and color index (four bytes
 * each) and its faction position (two bytes, {@code -1} for none); the seed (eight bytes); the option count (two bytes) and per option its
 * position (two bytes), its value kind (one byte: {@code 1} integer, {@code 2} long, {@code 3} boolean, {@code 4} string) and its value; then
 * a byte {@code 1} and the six pool sizes (units, sprites, images, orders, paths, projectiles, four bytes each) if the setup sets them, a byte
 * {@code 0} otherwise.
 */
public final class GameSetupCodec {
    private static final int MAXIMUM_DIGEST_LENGTH = 1 << 24;
    private static final int MAXIMUM_PLAYERS = 0xFF;
    private static final int MAXIMUM_OPTIONS = 0xFFFF;
    private static final int NO_FACTION = -1;
    private static final int INTEGER_VALUE = 1;
    private static final int LONG_VALUE = 2;
    private static final int BOOLEAN_VALUE = 3;
    private static final int STRING_VALUE = 4;

    private final @NotNull List<@NotNull Faction> factions;
    private final @NotNull List<@NotNull GameOption<?>> options;

    /**
     * Creates a {@link GameSetupCodec} instance.
     *
     * @param factions the factions a player slot may name, in the mod's order; copied
     * @param options  the options a setup may set, in the mod's order; copied
     */
    public GameSetupCodec(@NotNull List<@NotNull Faction> factions, @NotNull List<@NotNull GameOption<?>> options) {
        this.factions = List.copyOf(factions);
        this.options  = List.copyOf(options);
    }


    /**
     * Writes a map reference in the wire form: the map name (modified UTF-8), then the digest's length (four bytes) and bytes.
     *
     * @param map    the map reference
     * @param output the output
     * @throws IOException              if the output cannot be written
     * @throws IllegalArgumentException if the digest is longer than the wire form takes
     */
    public void writeMap(@NotNull MapReference map, @NotNull DataOutput output) throws IOException {
        byte[] digest = map.getDigest();

        if (digest.length > MAXIMUM_DIGEST_LENGTH) {
            throw Exceptions.newIllegalArgumentException("The map {0} has a digest of {1} bytes.", map.getName(), digest.length);
        }

        output.writeUTF(map.getName());
        output.writeInt(digest.length);
        output.write(digest);
    }

    /**
     * Reads a map reference in the wire form.
     *
     * @param input the input
     * @return the created {@link MapReference} instance
     * @throws IOException if the input ends early or the digest length is invalid
     */
    public @NotNull MapReference readMap(@NotNull DataInput input) throws IOException {
        String mapName = input.readUTF();
        int digestLength = input.readInt();

        if (digestLength < 0 || digestLength > MAXIMUM_DIGEST_LENGTH) {
            throw Exceptions.newIOException("Invalid map digest length of the map {0}: {1}", mapName, digestLength);
        }

        byte[] digest = new byte[digestLength];

        input.readFully(digest);

        return new MapReference(mapName, digest);
    }

    /**
     * Writes a faction in the wire form: its position in the codec's factions (two bytes), {@code -1} for none.
     *
     * @param faction the faction, or {@code null} for none
     * @param output  the output
     * @throws IOException              if the output cannot be written
     * @throws IllegalArgumentException if the codec does not know the faction
     */
    public void writeFaction(@Nullable Faction faction, @NotNull DataOutput output) throws IOException {
        int factionPosition = faction == null ? NO_FACTION : this.factions.indexOf(faction);

        if (faction != null && factionPosition < 0) {
            throw Exceptions.newIllegalArgumentException("The setup codec does not know the faction {0}.", faction);
        }

        output.writeShort(factionPosition);
    }

    /**
     * Reads a faction in the wire form.
     *
     * @param input the input
     * @return the faction, or {@code null} for none
     * @throws IOException if the input ends early or names no faction of the codec
     */
    public @Nullable Faction readFaction(@NotNull DataInput input) throws IOException {
        int factionPosition = input.readShort();

        if (factionPosition < NO_FACTION || factionPosition >= this.factions.size()) {
            throw Exceptions.newIOException("Invalid faction position: {0}", factionPosition);
        }

        return factionPosition == NO_FACTION ? null : this.factions.get(factionPosition);
    }

    /**
     * Writes a setup in the wire form.
     *
     * @param setup  the setup
     * @param output the output
     * @throws IOException              if the output cannot be written
     * @throws IllegalArgumentException if the setup has no wire form or names a faction, an option or an option value the codec cannot write
     */
    public void write(@NotNull GameSetup setup, @NotNull DataOutput output) throws IOException {
        if (setup.getInitialRandomState() != null || setup.getRandom() != null) {
            throw Exceptions.newIllegalArgumentException("A setup with a saved random state or its own random generator has no wire form.");
        }

        List<PlayerSlot> players = setup.getPlayers();
        GameOptions options = setup.getOptions();
        EngineLimits limits = setup.getLimits();

        if (players.size() > MAXIMUM_PLAYERS) {
            throw Exceptions.newIllegalArgumentException("The setup has {0} players.", players.size());
        }

        for (PlayerSlot player : players) {
            Faction faction = player.getFaction();

            if (faction != null && !this.factions.contains(faction)) {
                throw Exceptions.newIllegalArgumentException("The setup codec does not know the faction {0}.", faction);
            }
        }

        this.writeMap(setup.getMap(), output);
        output.writeByte(players.size());

        for (PlayerSlot player : players) {
            output.writeInt(player.getSlotIndex());
            output.writeByte(player.getControllerKind().ordinal());
            output.writeInt(player.getTeam());
            output.writeInt(player.getColorIndex());
            this.writeFaction(player.getFaction(), output);
        }

        output.writeLong(setup.getSeed());
        output.writeShort(options.getCount());

        for (int index = 0; index < options.getCount(); index++) {
            this.writeOption(options, options.getOption(index), output);
        }

        output.writeBoolean(limits != null);

        if (limits != null) {
            output.writeInt(limits.getUnits());
            output.writeInt(limits.getSprites());
            output.writeInt(limits.getImages());
            output.writeInt(limits.getOrders());
            output.writeInt(limits.getPaths());
            output.writeInt(limits.getProjectiles());
        }
    }

    /**
     * Reads a setup in the wire form.
     *
     * @param input the input
     * @return the created {@link GameSetup} instance
     * @throws IOException if the input ends early or holds no setup of the wire form with the codec's factions and options
     */
    public @NotNull GameSetup read(@NotNull DataInput input) throws IOException {
        MapReference map = this.readMap(input);
        String mapName = map.getName();
        GameSetup.Builder builder = new GameSetup.Builder(map);
        int playerCount = input.readUnsignedByte();
        ControllerKind[] kinds = ControllerKind.values();

        try {
            for (int index = 0; index < playerCount; index++) {
                int slotIndex = input.readInt();
                int kind = input.readUnsignedByte();
                int team = input.readInt();
                int colorIndex = input.readInt();
                int factionPosition = input.readShort();

                if (kind >= kinds.length || factionPosition < NO_FACTION || factionPosition >= this.factions.size()) {
                    throw Exceptions.newIOException("Invalid player slot {0} of the setup: kind {1}, faction {2}", slotIndex, kind, factionPosition);
                }

                builder.addPlayer(new PlayerSlot(slotIndex, kinds[kind], team, colorIndex,
                                                 factionPosition == NO_FACTION ? null : this.factions.get(factionPosition)));
            }

            builder.setSeed(input.readLong());
            builder.setOptions(this.readOptions(input));

            if (input.readBoolean()) {
                builder.setLimits(new EngineLimits.Builder().setUnits(input.readInt()).setSprites(input.readInt()).setImages(input.readInt())
                                                            .setOrders(input.readInt()).setPaths(input.readInt())
                                                            .setProjectiles(input.readInt()).build());
            }
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "Invalid setup of the map {0}", mapName);
        }

        return builder.build();
    }

    private <T> void writeOption(@NotNull GameOptions options, @NotNull GameOption<T> option, @NotNull DataOutput output) throws IOException {
        int position = this.options.indexOf(option);
        Object value = options.get(option);

        if (position < 0 || position > MAXIMUM_OPTIONS) {
            throw Exceptions.newIllegalArgumentException("The setup codec does not know the option {0}.", option);
        }

        output.writeShort(position);

        switch (value) {
            case Integer number -> {
                output.writeByte(INTEGER_VALUE);
                output.writeInt(number);
            }
            case Long number -> {
                output.writeByte(LONG_VALUE);
                output.writeLong(number);
            }
            case Boolean flag -> {
                output.writeByte(BOOLEAN_VALUE);
                output.writeBoolean(flag);
            }
            case String text -> {
                output.writeByte(STRING_VALUE);
                output.writeUTF(text);
            }
            default -> throw Exceptions.newIllegalArgumentException("The value of the option {0} has no wire form.", option);
        }
    }

    private @NotNull GameOptions readOptions(@NotNull DataInput input) throws IOException {
        GameOptions.Builder builder = new GameOptions.Builder();
        int count = input.readUnsignedShort();

        for (int index = 0; index < count; index++) {
            int position = input.readUnsignedShort();
            int kind = input.readUnsignedByte();

            if (position >= this.options.size()) {
                throw Exceptions.newIOException("Invalid option position of the setup: {0}", position);
            }

            GameOption<?> option = this.options.get(position);
            Object value = switch (kind) {
                case INTEGER_VALUE -> input.readInt();
                case LONG_VALUE -> input.readLong();
                case BOOLEAN_VALUE -> input.readBoolean();
                case STRING_VALUE -> input.readUTF();
                default -> throw Exceptions.newIOException("Invalid value kind {0} of the option {1}", kind, option);
            };

            this.setOption(builder, option, value);
        }

        return builder.build();
    }

    private <T> void setOption(@NotNull GameOptions.Builder builder, @NotNull GameOption<T> option, @NotNull Object value) throws IOException {
        if (!option.getType().isInstance(value)) {
            throw Exceptions.newIOException("The option {0} has a value of another type: {1}", option, value);
        }

        builder.set(option, option.getType().cast(value));
    }
}
