package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.demo.content.DemoContent;
import org.fogofwarlabs.sc4j.demo.game.DemoGames;
import org.fogofwarlabs.sc4j.demo.map.DemoMaps;
import org.fogofwarlabs.sc4j.engine.client.app.SceneClient;
import org.fogofwarlabs.sc4j.engine.client.dialog.*;
import org.fogofwarlabs.sc4j.engine.client.game.GameSessionFactory;
import org.fogofwarlabs.sc4j.engine.client.text.BitmapFont;
import org.fogofwarlabs.sc4j.engine.client.text.TextColorHook;
import org.fogofwarlabs.sc4j.engine.combat.StandardCombatSystem;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.content.type.ContentType;
import org.fogofwarlabs.sc4j.engine.game.Game;
import org.fogofwarlabs.sc4j.engine.game.GameSetup;
import org.fogofwarlabs.sc4j.engine.game.PlayerSlot;
import org.fogofwarlabs.sc4j.engine.map.MapModel;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkAccount;
import org.fogofwarlabs.sc4j.engine.replay.EngineReplayFormat;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormat;
import org.fogofwarlabs.sc4j.engine.resource.ResourceKey;
import org.fogofwarlabs.sc4j.engine.spatial.TableUnitBounds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Holds the shared services of the demo's scenes on one client: the client, the demo's content, maps and games, the pack font, the unit sprites, the
 * notification sounds, the demo's look made of the pack's pictures ({@link DemoSkin}: screens, emblems, dialog look, HUD skins and cursors), the
 * dialogs of the pack, the shown names of the content, the music of the menus and the games ({@link #playMusic}) and the options
 * ({@link DemoOptions}, read from the demo's settings file and applied to the client when it opens). The main menu owns it and closes it when the
 * client ends.
 */
public final class DemoClient implements AutoCloseable {
    /**
     * The id of the pack font of the demo's texts; the pack also has its detail face at twice the size ({@code body-2x}) for scaled displays.
     */
    public static final @NotNull String FONT = "body";
    /**
     * The storage folder of the demo's replays.
     */
    public static final @NotNull String REPLAY_FOLDER = "replays";
    /**
     * The music of the menus: the pack ids of its pieces.
     */
    public static final @NotNull List<@NotNull ResourceKey> MENU_MUSIC = List.of(ResourceKey.of("music-hearth"));
    /**
     * The music of a game and a replay, the pieces in turn.
     */
    public static final @NotNull List<@NotNull ResourceKey> GAME_MUSIC = List.of(ResourceKey.of("music-ford"), ResourceKey.of("music-ridge"));
    /**
     * The team colours by colour index, packed {@code 0xRRGGBB}: the first player's blue, the second player's red and the neutral grey.
     */
    private static final int @NotNull [] TEAM_COLORS = {0x3C78D8, 0xD8503C, 0xC8C8C8};
    private static final char WORD_SEPARATOR = '-';
    private static final @NotNull String SLOT_PREFIX = "Player ";

    /**
     * Plays the click sound of an activated button before it hands the activation to the dialog's listener, and the hover sound of an enabled
     * button the pointer moves onto.
     */
    private static final class SoundingListener implements DialogListener {
        private final @NotNull DemoSounds sounds;
        private final @NotNull DialogListener listener;

        private SoundingListener(@NotNull DemoSounds sounds, @NotNull DialogListener listener) {
            this.sounds   = sounds;
            this.listener = listener;
        }

        /**
         * Called when a control is activated: a released press, Enter, Escape or a hotkey.
         * <p>
         * A button plays the click sound first.
         *
         * @param dialog  the dialog
         * @param control the activated control
         */
        @Override
        public void onActivated(@NotNull Dialog dialog, @NotNull Control control) {
            if (control instanceof Button) {
                this.sounds.play(DemoSounds.CLICK);
            }

            this.listener.onActivated(dialog, control);
        }

        /**
         * Called when the pointer moves onto a control that takes it, for example to play a hover sound. The default does nothing.
         * <p>
         * An enabled button that is not selected plays the hover sound.
         *
         * @param dialog  the dialog
         * @param control the hovered control
         */
        @Override
        public void onHovered(@NotNull Dialog dialog, @NotNull Control control) {
            if (control instanceof Button button && button.isEnabled() && !button.isSelected()) {
                this.sounds.play(DemoSounds.HOVER);
            }

            this.listener.onHovered(dialog, control);
        }

        /**
         * Called when Escape reaches a dialog without a cancel control.
         *
         * @param dialog the dialog
         */
        @Override
        public void onCancelled(@NotNull Dialog dialog) {
            this.listener.onCancelled(dialog);
        }
    }

    /**
     * Opens the shared services of the demo's scenes.
     *
     * @param client  the client
     * @param content the demo content
     * @param maps    the demo maps
     * @return the created {@link DemoClient} instance, closed by the caller
     * @throws IOException if the font, a sprite sheet, a sound or a picture of the pack cannot be read
     */
    public static @NotNull DemoClient open(@NotNull SceneClient client, @NotNull DemoContent content, @NotNull DemoMaps maps) throws IOException {
        DemoGames games = new DemoGames(client.getLaunch().getGameDefinition(), content, maps);
        DemoSprites sprites = DemoSprites.load(content, client.getLaunch().getGameDefinition().getContent());
        BitmapFont font = BitmapFont.load(client.getCanvas(), content.getPack(), ResourceKey.of(FONT), TextColorHook.NONE);
        DemoSounds sounds;

        try {
            sounds = DemoSounds.open(content, client.getAudio());
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    font::close
            );

            throw exception;
        }

        DemoOptions options = DemoOptions.read(client.getStorage().getSettingsFile());

        options.apply(client);

        try {
            return new DemoClient(client, content, maps, games, font, sprites, sounds, DemoSkin.open(client, content, font), options);
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    sounds::close,
                    font::close
            );

            throw exception;
        }
    }

    /**
     * Creates the factory of a game's sessions: the engine default view with the demo's team colours, and the game's frame runner applying
     * the commands. The client's games and replays and the headless runner's network games use it.
     *
     * @param content the demo content
     * @param game    the game
     * @param map     the game's map
     * @return the created {@link GameSessionFactory} instance
     */
    public static @NotNull GameSessionFactory createSessionFactory(@NotNull DemoContent content, @NotNull Game game, @NotNull MapModel map) {
        return new GameSessionFactory(map.getTerrain(), game.getModSystem(TableUnitBounds.KEY), StandardCombatSystem.FRACTION_BITS,
                                      content.getResourceKinds(), TEAM_COLORS);
    }

    /**
     * Names a playing slot by its faction, for the replay list; a slot without faction is named by its number.
     *
     * @param slot the slot
     * @return the shown name, for example {@code "Dawnhold"}
     */
    public static @NotNull String getSlotName(@NotNull PlayerSlot slot) {
        Faction faction = slot.getFaction();

        return faction != null ? getName(faction) : SLOT_PREFIX + (slot.getSlotIndex() + 1);
    }

    /**
     * Turns a content id into its shown name: words split at hyphens, each capitalized, for example {@code "muster-yard"} into
     * {@code "Muster Yard"}.
     *
     * @param type the content type
     * @return the shown name
     */
    public static @NotNull String getName(@NotNull ContentType type) {
        StringBuilder name = new StringBuilder();

        for (String word : type.getDebugName().split(String.valueOf(WORD_SEPARATOR))) {
            if (word.isEmpty()) {
                continue;
            }

            if (!name.isEmpty()) {
                name.append(' ');
            }

            name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }

        return name.toString();
    }

    private final @NotNull SceneClient client;
    private final @NotNull DemoContent content;
    private final @NotNull DemoMaps maps;
    private final @NotNull DemoGames games;

    private final @NotNull BitmapFont font;
    private final @NotNull DemoSprites sprites;
    private final @NotNull DemoSounds sounds;
    private final @NotNull DemoSkin skin;
    private final @NotNull DemoOptions options;

    private DemoClient(@NotNull SceneClient client, @NotNull DemoContent content, @NotNull DemoMaps maps, @NotNull DemoGames games,
                       @NotNull BitmapFont font, @NotNull DemoSprites sprites, @NotNull DemoSounds sounds, @NotNull DemoSkin skin,
                       @NotNull DemoOptions options) {
        this.client  = client;
        this.content = content;
        this.maps    = maps;
        this.games   = games;

        this.font    = font;
        this.sprites = sprites;
        this.sounds  = sounds;
        this.skin    = skin;
        this.options = options;
    }

    /**
     * @return the client the scenes run on
     */
    public @NotNull SceneClient getClient() {
        return this.client;
    }

    /**
     * @return the demo content
     */
    public @NotNull DemoContent getContent() {
        return this.content;
    }

    /**
     * @return the demo maps
     */
    public @NotNull DemoMaps getMaps() {
        return this.maps;
    }

    /**
     * @return the demo's games of the launch's game definition
     */
    public @NotNull DemoGames getGames() {
        return this.games;
    }

    /**
     * @return the pack font of the demo's texts
     */
    public @NotNull BitmapFont getFont() {
        return this.font;
    }

    /**
     * @return the look of the demo's dialogs, the skin's dialog look
     */
    public @NotNull DialogTheme getTheme() {
        return this.skin.getTheme();
    }

    /**
     * @return the unit sprites
     */
    public @NotNull DemoSprites getSprites() {
        return this.sprites;
    }

    /**
     * @return the notification sounds
     */
    public @NotNull DemoSounds getSounds() {
        return this.sounds;
    }

    /**
     * @return the demo's look made of the pack's pictures
     */
    public @NotNull DemoSkin getSkin() {
        return this.skin;
    }

    /**
     * @return the options, which the options screen changes
     */
    public @NotNull DemoOptions getOptions() {
        return this.options;
    }

    /**
     * @return the run's account, or {@code null} for a run without an account (a guest)
     */
    public @Nullable LinkAccount getAccount() {
        BeaconLink beaconLink = this.client.getLaunch().getBeaconLink();

        return beaconLink == null ? null : beaconLink.getAccount();
    }

    /**
     * @return the player's name in games, lobbies and Beacon.Link: the account's name, fixed when the account was made, or {@value BeaconLink#GUEST_NAME} for a guest
     */
    public @NotNull String getPlayerName() {
        LinkAccount account = this.getAccount();

        return account == null ? BeaconLink.GUEST_NAME : account.getName();
    }

    /**
     * @return the name the menus show: the account's name with its tag ({@code Name#abcd12}), or {@value BeaconLink#GUEST_NAME} for a guest
     */
    public @NotNull String getTaggedPlayerName() {
        LinkAccount account = this.getAccount();

        return account == null ? BeaconLink.GUEST_NAME : account.getTaggedName();
    }

    /**
     * @return the format the demo records its replays in: the engine replay format, the first the demo registers
     * @throws IllegalStateException if the launch has no engine replay format
     */
    public @NotNull EngineReplayFormat getReplayFormat() {
        for (ReplayFormat format : this.client.getLaunch().getReplayFormats()) {
            if (format instanceof EngineReplayFormat engine) {
                return engine;
            }
        }

        throw Exceptions.newIllegalStateException("The demo launch has no engine replay format.");
    }


    /**
     * Plays music of the pack on the client's music, unless it plays already: the menus' {@link #MENU_MUSIC} or the games'
     * {@link #GAME_MUSIC}.
     *
     * @param pieces the pack ids of the pieces, played in turn
     */
    public void playMusic(@NotNull List<@NotNull ResourceKey> pieces) {
        this.client.getMusic().play(this.content.getPack(), pieces);
    }

    /**
     * Builds a dialog of the pack; an activated button plays the click sound before the listener hears it, and an enabled button the pointer
     * moves onto plays the hover sound.
     *
     * @param id       the dialog's pack id
     * @param listener the receiver of its activations
     * @return the dialog
     * @throws IOException if the dialog cannot be read
     */
    public @NotNull Dialog createDialog(@NotNull String id, @NotNull DialogListener listener) throws IOException {
        return new Dialog(this.content.getPack().loadDialog(ResourceKey.of(id)), new DefaultControlFactory(this.font.getLineHeight()),
                          new SoundingListener(this.sounds, listener), false);
    }

    /**
     * Describes a game's setup for a list or a title: the map and the factions of the players.
     *
     * @param setup the setup
     * @return the description, for example {@code "Ashen Ford: Dawnhold against Cindermaw"}
     */
    public @NotNull String describe(@NotNull GameSetup setup) {
        StringBuilder text = new StringBuilder(setup.getMap().getName()).append(':');
        String separator = " ";

        for (PlayerSlot slot : setup.getPlayers()) {
            Faction faction = slot.getFaction();

            if (faction != null) {
                text.append(separator).append(getName(faction));
                separator = " against ";
            }
        }

        return text.toString();
    }

    /**
     * Disposes resources: releases the pictures of the skin, the sounds and the font texture.
     */
    @Override
    public void close() {
        IgnoreException.execute(
                this.skin::close,
                this.sounds::close,
                this.font::close
        );
    }
}
