package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.demo.net.DemoLobby;
import org.fogofwarlabs.sc4j.demo.net.DemoNetworkSettings;
import org.fogofwarlabs.sc4j.engine.client.community.ChatLogBox;
import org.fogofwarlabs.sc4j.engine.client.community.RoomFilter;
import org.fogofwarlabs.sc4j.engine.client.community.TableBox;
import org.fogofwarlabs.sc4j.engine.client.community.TableRow;
import org.fogofwarlabs.sc4j.engine.client.dialog.Button;
import org.fogofwarlabs.sc4j.engine.client.dialog.ComboBox;
import org.fogofwarlabs.sc4j.engine.client.dialog.Control;
import org.fogofwarlabs.sc4j.engine.client.dialog.Dialog;
import org.fogofwarlabs.sc4j.engine.client.lobby.RecentMaps;
import org.fogofwarlabs.sc4j.engine.game.MapReference;
import org.fogofwarlabs.sc4j.engine.net.NetworkClock;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChannelSummary;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntry;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.CommunityText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows the demo's network screen on its Beacon.Link tab: the games of the install's Beacon.Link nodes and the Beacon.Link community, all through the
 * engine's community parts. Opening the tab opens a Beacon.Link session (the run's key and node list); once a node registered it searches the rooms
 * and enters the channel chat. The left side lists the rooms (name, map, players, turn rate, the room's mod and version, its state: open, private,
 * full, started or closed for another version, and latency bars) with their filter (search text, latency, largest player count, closed games); Host
 * opens the create popup ({@value #CREATE_DIALOG}: room name, map with the recently hosted maps first, turn rate, public or private with a password)
 * and publishes the room, Join joins the selected room of this mod, asking for the password of a private room ({@link DemoPopup#PROMPT}), Refresh
 * searches again once the last search is complete. The right side shows the channel's users (name, mod, status, latency), the channel list or the
 * friends, with the chat log and its input under them; typed lines go to the channel, slash commands ({@code /help}) whisper, join channels and
 * manage friends. The presence choice shows this player online or away, asking for an optional away text. Every name shows with its tag. The other
 * tab returns to the games on this computer ({@link NetworkScene}), and Back to the main menu. A room shows on the room screen
 * ({@link NetworkRoomScene}), which takes the session; other participants see this computer's address.
 */
public final class BeaconLinkScene extends DemoMenuScene {
    /**
     * The pack id of the Beacon.Link tab's dialog.
     */
    public static final @NotNull String DIALOG = "network-beaconlink";
    /**
     * The id of the label that names the player: the account's name with its tag.
     */
    public static final int NAME_ID = 10;
    /**
     * The id of the node line.
     */
    public static final int NODES_ID = 11;
    /**
     * The id of the room table.
     */
    public static final int ROOMS_ID = 12;
    /**
     * The id of the status line.
     */
    public static final int STATUS_ID = 13;
    /**
     * The id of the host button.
     */
    public static final int HOST_ID = 20;
    /**
     * The id of the join button.
     */
    public static final int JOIN_ID = 21;
    /**
     * The id of the refresh button.
     */
    public static final int REFRESH_ID = 22;
    /**
     * The id of the back button.
     */
    public static final int BACK_ID = 23;
    /**
     * The id of the search input of the room filter.
     */
    public static final int SEARCH_ID = 40;
    /**
     * The id of the latency choice of the room filter.
     */
    public static final int LATENCY_ID = 41;
    /**
     * The id of the largest player count choice of the room filter.
     */
    public static final int PLAYERS_ID = 42;
    /**
     * The id of the other versions choice of the room filter.
     */
    public static final int VERSIONS_ID = 43;
    /**
     * The id of the presence choice: online or away.
     */
    public static final int PRESENCE_ID = 44;
    /**
     * The id of the channel heading.
     */
    public static final int CHANNEL_ID = 50;
    /**
     * The id of the users tab of the side table.
     */
    public static final int USERS_TAB_ID = 51;
    /**
     * The id of the channels tab of the side table.
     */
    public static final int CHANNELS_TAB_ID = 52;
    /**
     * The id of the friends tab of the side table.
     */
    public static final int FRIENDS_TAB_ID = 53;
    /**
     * The id of the side table: users, channels or friends.
     */
    public static final int SIDE_ID = 54;
    /**
     * The id of the side table's first action: Whisper, or Join for a channel.
     */
    public static final int FIRST_ACTION_ID = 55;
    /**
     * The id of the side table's second action: Add friend, or Remove for a friend.
     */
    public static final int SECOND_ACTION_ID = 56;
    /**
     * The id of the chat log.
     */
    public static final int CHAT_LOG_ID = 57;
    /**
     * The id of the chat input.
     */
    public static final int CHAT_INPUT_ID = 58;
    /**
     * The id of the send button, the dialog's default control.
     */
    public static final int SEND_ID = 59;
    /**
     * The pack id of the create popup's dialog.
     */
    public static final @NotNull String CREATE_DIALOG = "network-create";
    /**
     * The id of the create popup's room name input.
     */
    public static final int CREATE_NAME_ID = 10;
    /**
     * The id of the create popup's map choice.
     */
    public static final int CREATE_MAP_ID = 11;
    /**
     * The id of the create popup's turn rate choice.
     */
    public static final int CREATE_TURN_RATE_ID = 12;
    /**
     * The id of the create popup's privacy choice: public or private.
     */
    public static final int CREATE_PRIVACY_ID = 13;
    /**
     * The id of the create popup's password input, shown for a private room.
     */
    public static final int CREATE_PASSWORD_ID = 14;
    /**
     * The id of the create popup's status line.
     */
    public static final int CREATE_STATUS_ID = 15;
    /**
     * The id of the create popup's create button, its default control.
     */
    public static final int CREATE_ID = 20;
    /**
     * The id of the create popup's cancel button.
     */
    public static final int CREATE_CANCEL_ID = 21;
    /**
     * The file of the recently hosted maps in the mod storage.
     */
    public static final @NotNull String RECENT_MAPS_FILE = "recent-maps.txt";

    private static final @NotNull Logger LOGGER = Logger.of(BeaconLinkScene.class);
    /**
     * The id of the create popup's password label, shown with the password input.
     */
    private static final int CREATE_PASSWORD_LABEL_ID = 7;
    private static final @NotNull String PASSWORD_TEXT = "Password";
    private static final @NotNull String AWAY_TEXT = "Away";
    private static final @NotNull String AWAY_QUESTION = "Away text (optional)";
    private static final @NotNull String AWAY_COMMAND = "/away ";
    private static final @NotNull String ENTER_NAME = "Enter a name.";
    private static final @NotNull String ENTER_PASSWORD = "Enter a password.";
    private static final @NotNull List<String> PRIVACY_CHOICES = List.of("Public", "Private");
    private static final int PRIVATE_CHOICE = 1;
    private static final int ONLINE_CHOICE = 0;
    private static final int AWAY_CHOICE = 1;
    private static final int GOOD_LATENCY_CHOICE = 1;
    private static final int FAIR_LATENCY_CHOICE = 2;
    private static final @NotNull String OPEN_STATE = "Open";
    private static final @NotNull String PRIVATE_STATE = "Private";
    private static final @NotNull String FULL_STATE = "Full";
    private static final @NotNull String STARTED_STATE = "Started";
    private static final @NotNull String CLOSED_STATE = "Closed";
    /**
     * The demo's texts are English.
     */
    private static final @NotNull Locale TEXT_LOCALE = Locale.ENGLISH;
    private static final @NotNull String MOD_SEPARATOR = " ";
    private static final @NotNull String COUNT_FORMAT = "%s (%d)";
    /**
     * The room table's columns: name, map, players, turn rate, mod, state; and the side tables' columns.
     */
    private static final float @NotNull [] ROOM_COLUMNS = {0.205f, 0.19f, 0.135f, 0.17f, 0.16f, 0.14f};
    private static final float @NotNull [] USER_COLUMNS = {0.47f, 0.28f, 0.25f};
    private static final float @NotNull [] CHANNEL_COLUMNS = {0.75f, 0.25f};
    private static final @NotNull List<String> ROOM_HEADERS = List.of("Name", "Map", "Players", "Turn rate", "Mod", "Status");
    private static final @NotNull List<String> USER_HEADERS = List.of("Name", "Mod", "Status");
    private static final @NotNull List<String> CHANNEL_HEADERS = List.of("Channel", "Users");
    private static final @NotNull List<String> FRIEND_HEADERS = List.of("Name", "Status", "Mod");
    /**
     * The largest player counts the filter offers after Any.
     */
    private static final int @NotNull [] PLAYER_COUNTS = {2, 4, 8};
    /**
     * The colours of the chat log's entries and of the side table's rows.
     */
    private static final int SAY_COLOR = 0xE0E0E0FF;
    private static final int EMOTE_COLOR = 0xFF9050FF;
    private static final int WHISPER_COLOR = 0xE070E0FF;
    private static final int INFO_COLOR = 0xF0D040FF;
    private static final int ERROR_COLOR = 0xFF5050FF;
    private static final int SELF_COLOR = 0xFFFFFFFF;
    private static final int FRIEND_COLOR = INFO_COLOR;
    private static final int PLAIN_COLOR = SAY_COLOR;
    private static final int GREY_COLOR = 0x909090FF;

    /**
     * Lists what the side table shows.
     */
    public enum SidePanel {
        /**
         * The users of the channel.
         */
        USERS,
        /**
         * The known channels.
         */
        CHANNELS,
        /**
         * The friends.
         */
        FRIENDS
    }

    private final @NotNull MainMenuScene menu;
    private final @NotNull DemoNetworkSettings settings;
    private final @NotNull String reason;

    private @Nullable BeaconLinkSession session;
    private @Nullable RoomSearch search;
    private @NotNull List<@NotNull FoundRoom> shownRooms;
    private final @NotNull RoomFilter filter;

    private @Nullable ChannelChat chat;
    private int shownPresence;
    private @NotNull SidePanel sidePanel;
    /**
     * The players or channel names of the side table's rows, in its order.
     */
    private @NotNull List<@NotNull LinkPlayer> sidePlayers;
    private @NotNull List<@NotNull String> sideChannels;

    private @Nullable DemoPopup popup;

    /**
     * Creates a {@link BeaconLinkScene} instance.
     *
     * @param demo     the demo's shared services
     * @param menu     the main menu the back button returns to
     * @param settings the settings of the games on this computer, for the other tab
     * @param reason   why the last room ended, shown in the status line, or an empty text
     */
    public BeaconLinkScene(@NotNull DemoClient demo, @NotNull MainMenuScene menu, @NotNull DemoNetworkSettings settings, @NotNull String reason) {
        super(demo, DIALOG);

        this.menu     = menu;
        this.settings = settings;
        this.reason   = reason;

        this.session    = null;
        this.search     = null;
        this.shownRooms = List.of();
        this.filter     = new RoomFilter();

        this.chat          = null;
        this.shownPresence = ONLINE_CHOICE;
        this.sidePanel     = SidePanel.USERS;
        this.sidePlayers   = List.of();
        this.sideChannels  = List.of();

        this.popup = null;
    }

    /**
     * @return the rooms the room table shows, in its order
     */
    public @NotNull List<@NotNull FoundRoom> getShownRooms() {
        return this.shownRooms;
    }

    /**
     * @return the tab's Beacon.Link session, or {@code null} before it opened, if it cannot open or after a room took it
     */
    public @Nullable BeaconLinkSession getSession() {
        return this.session;
    }

    /**
     * @return the channel chat, or {@code null} before a node registered the session
     */
    public @Nullable ChannelChat getChat() {
        return this.chat;
    }

    /**
     * @return what the side table shows
     */
    public @NotNull SidePanel getSidePanel() {
        return this.sidePanel;
    }

    /**
     * @return the open popup of the tab (create, password or away text), or {@code null} while none is open
     */
    public @Nullable DemoPopup getPopup() {
        DemoPopup current = this.popup;

        return current != null && current.isOpen() ? current : null;
    }


    /**
     * Called when the scene opened and its dialog is built: fills the dialog's controls. The default fills nothing.
     * <p>
     * Puts the room table, the side table and the chat log in place, fills the name and the filter choices and opens the Beacon.Link
     * session; the rooms are searched and the chat entered once a node registered.
     */
    @Override
    protected void onOpened() {
        BeaconLink beaconLink = this.getDemo().getClient().getLaunch().getBeaconLink();
        Dialog dialog = this.requireDialog();
        int rowHeight = this.getDemo().getFont().getLineHeight();

        dialog.replaceControl(this.requireControl(ROOMS_ID),
                              new TableBox(this.requireControl(ROOMS_ID).getDefinition(), rowHeight, ROOM_COLUMNS, ROOM_HEADERS, true));
        dialog.replaceControl(this.requireControl(CHAT_LOG_ID),
                              new ChatLogBox(this.requireControl(CHAT_LOG_ID).getDefinition(), ChannelChat.HISTORY));
        this.showSidePanel(SidePanel.USERS);

        this.requireControl(NAME_ID).setText(this.getDemo().getTaggedPlayerName());
        this.showStatus(this.reason);
        this.fillChoices();

        if (beaconLink == null || beaconLink.getAccount() == null) {
            this.showUnavailable(CommunityText.NEEDS_ACCOUNT.get(TEXT_LOCALE));

            return;
        }

        try {
            this.session = beaconLink.open();
        } catch (IOException | IllegalArgumentException exception) {
            this.showUnavailable(exception.getMessage() == null ? CommunityText.UNKNOWN.get(TEXT_LOCALE) : exception.getMessage());
        }
    }

    private void fillChoices() {
        List<String> latencies = List.of(CommunityText.ANY.get(TEXT_LOCALE), CommunityText.LATENCY_GOOD.get(TEXT_LOCALE),
                                         CommunityText.LATENCY_FAIR.get(TEXT_LOCALE));
        List<String> players = new ArrayList<>();

        players.add(CommunityText.ANY.get(TEXT_LOCALE));

        for (int count : PLAYER_COUNTS) {
            players.add(Integer.toString(count));
        }

        this.fillChoice(LATENCY_ID, latencies);
        this.fillChoice(PLAYERS_ID, players);
        this.fillChoice(VERSIONS_ID, List.of(CommunityText.SHOW.get(TEXT_LOCALE), CommunityText.HIDE.get(TEXT_LOCALE)));
        this.fillChoice(PRESENCE_ID, List.of(CommunityText.STATUS_AVAILABLE.get(TEXT_LOCALE), CommunityText.STATUS_AWAY.get(TEXT_LOCALE)));
    }

    private void fillChoice(int id, @NotNull List<String> entries) {
        fillChoice(this.requireControl(id), entries, 0);
    }

    private static void fillChoice(@NotNull Control control, @NotNull List<String> entries, int selection) {
        ComboBox choice = (ComboBox) control;

        choice.setEntries(entries);
        choice.setSelection(selection);
    }

    /**
     * Updates the scene at the supplied time.
     * <p>
     * Polls the session, shows its nodes, searches the rooms and enters the chat once a node registered, and shows the filtered rooms, the side table
     * and this player's presence; while the create popup is open, its password input shows for a private room only.
     *
     * @param time the client time in milliseconds
     */
    @Override
    public void update(long time) {
        BeaconLinkSession current = this.session;
        DemoPopup shownPopup = this.getPopup();

        if (shownPopup != null && shownPopup.getDialogId().equals(CREATE_DIALOG)) {
            boolean privateRoom = ((ComboBox) shownPopup.requireControl(CREATE_PRIVACY_ID)).getSelection() == PRIVATE_CHOICE;

            shownPopup.requireControl(CREATE_PASSWORD_ID).setVisible(privateRoom);
            shownPopup.requireControl(CREATE_PASSWORD_LABEL_ID).setVisible(privateRoom);
        }

        if (current == null) {
            return;
        }

        try {
            current.poll();
        } catch (IOException exception) {
            LOGGER.warning("BEACON.LINK: {0}", exception.getMessage());

            return;
        }

        this.showNodes(current);

        if (current.getObservedAddress() == null) {
            return;
        }

        if (this.chat == null) {
            this.enterChat(current);
        }

        this.showPresence();

        RoomSearch running = this.search;

        if (running == null) {
            this.search = current.searchRooms();
        } else {
            this.showRooms(running, current);
        }

        this.showSide();
    }

    /**
     * Called when a control of the dialog is activated.
     * <p>
     * Host publishes a room, Join joins the selected room, Refresh searches again, the side tabs switch the side table, the actions whisper,
     * add or remove a friend or join a channel, Send runs the typed line, the other tab shows the games on this computer, Back returns to
     * the main menu.
     *
     * @param id the id of the activated control
     */
    @Override
    protected void onActivated(int id) {
        switch (id) {
            case HOST_ID -> this.openCreatePopup();
            case JOIN_ID -> this.join();
            case REFRESH_ID -> this.refresh();
            case USERS_TAB_ID -> this.showSidePanel(SidePanel.USERS);
            case CHANNELS_TAB_ID -> this.showSidePanel(SidePanel.CHANNELS);
            case FRIENDS_TAB_ID -> this.showSidePanel(SidePanel.FRIENDS);
            case FIRST_ACTION_ID -> this.runFirstAction();
            case SECOND_ACTION_ID -> this.runSecondAction();
            case SEND_ID -> this.send();
            case NetworkScene.LOCAL_TAB_ID -> this.getDemo().getClient().getScenes().replace(new NetworkScene(this.getDemo(), this.menu, this.settings));
            case BACK_ID -> this.menu.show();
            default -> {
                // Labels, inputs, choices and tables do nothing when activated.
            }
        }
    }

    private void enterChat(@NotNull BeaconLinkSession current) {
        ChannelChat opened = new ChannelChat(current.getCommunity(), TEXT_LOCALE, this.getDemo().getClient().getLaunch().getIdentity().getModId());
        ChatLogBox log = (ChatLogBox) this.requireControl(CHAT_LOG_ID);

        this.chat = opened;
        opened.addLog(entry -> log.addLine(entry.getPrefix() + entry.getText(), getEntryColor(entry)), true);
        opened.open();
    }

    private static int getEntryColor(@NotNull ChatEntry entry) {
        return switch (entry.getKind()) {
            case SAY -> SAY_COLOR;
            case EMOTE -> EMOTE_COLOR;
            case WHISPER -> WHISPER_COLOR;
            case INFO -> INFO_COLOR;
            case ERROR -> ERROR_COLOR;
        };
    }

    /**
     * Stops the chat listening; the room the session goes to keeps the player in the channel.
     */
    private void leaveChat() {
        ChannelChat current = this.chat;

        this.chat = null;

        if (current != null) {
            current.close();
        }
    }

    /**
     * Opens the create popup: the room name ({@code Name's game}), the maps with the recently hosted ones first, the turn rates with the
     * default chosen, public or private and the password.
     */
    private void openCreatePopup() {
        DemoClient demo = this.getDemo();
        List<String> turnRates = new ArrayList<>();

        if (this.session == null || this.getPopup() != null) {
            return;
        }

        for (int turnRate : DemoNetworkSettings.TURN_RATES) {
            turnRates.add(Integer.toString(turnRate));
        }

        try {
            DemoPopup created = DemoPopup.open(demo, CREATE_DIALOG, this::onCreateActivated);

            created.requireControl(CREATE_NAME_ID).setText(DemoLobby.createRoomName(demo.getPlayerName()));
            fillChoice(created.requireControl(CREATE_MAP_ID), this.listCreateMaps().stream().map(MapReference::getName).toList(), 0);
            fillChoice(created.requireControl(CREATE_TURN_RATE_ID), turnRates,
                       DemoNetworkSettings.TURN_RATES.indexOf(DemoNetworkSettings.DEFAULT_TURN_RATE));
            fillChoice(created.requireControl(CREATE_PRIVACY_ID), PRIVACY_CHOICES, 0);
            this.popup = created;
        } catch (IOException exception) {
            this.showStatus("Cannot host: " + exception.getMessage());
        }
    }

    /**
     * Lists the maps of the create popup: the recently hosted maps the demo has, newest first, then the demo's other maps in its order.
     */
    private @NotNull List<@NotNull MapReference> listCreateMaps() {
        DemoClient demo = this.getDemo();
        List<MapReference> all = demo.getMaps().getReferences();
        List<MapReference> maps = new ArrayList<>(all.size());

        for (String name : RecentMaps.read(this.getRecentMapsFile()).getMaps()) {
            for (MapReference map : all) {
                if (map.getName().equals(name) && !maps.contains(map)) {
                    maps.add(map);
                }
            }
        }

        for (MapReference map : all) {
            if (!maps.contains(map)) {
                maps.add(map);
            }
        }

        return maps;
    }

    private @NotNull Path getRecentMapsFile() {
        return this.getDemo().getClient().getStorage().resolve(RECENT_MAPS_FILE);
    }

    /**
     * Creates the room of the create popup: a name and, for a private room, a password are needed; Cancel closes the popup.
     */
    private void onCreateActivated(@NotNull DemoPopup created, int id) {
        if (id == CREATE_CANCEL_ID) {
            created.close();

            return;
        }

        if (id != CREATE_ID) {
            return;
        }

        String roomName = created.requireControl(CREATE_NAME_ID).getText().strip();
        boolean privateRoom = ((ComboBox) created.requireControl(CREATE_PRIVACY_ID)).getSelection() == PRIVATE_CHOICE;
        String password = privateRoom ? created.requireControl(CREATE_PASSWORD_ID).getText() : "";
        int mapIndex = Math.max(((ComboBox) created.requireControl(CREATE_MAP_ID)).getSelection(), 0);
        int turnRateIndex = Math.max(((ComboBox) created.requireControl(CREATE_TURN_RATE_ID)).getSelection(), 0);

        if (roomName.isEmpty()) {
            created.requireControl(CREATE_STATUS_ID).setText(ENTER_NAME);

            return;
        }

        if (privateRoom && password.isEmpty()) {
            created.requireControl(CREATE_STATUS_ID).setText(ENTER_PASSWORD);

            return;
        }

        created.close();
        this.host(roomName, this.listCreateMaps().get(mapIndex), DemoNetworkSettings.TURN_RATES.get(turnRateIndex), password);
    }

    private void host(@NotNull String roomName, @NotNull MapReference map, int turnRate, @NotNull String password) {
        DemoClient demo = this.getDemo();
        BeaconLinkSession current = this.session;

        if (current == null) {
            return;
        }

        try {
            DemoLobby lobby = DemoLobby.host(current, demo.getClient().getLaunch().getIdentity(), demo.getContent().createSetupCodec(),
                                             demo.getMaps(), demo.getTaggedPlayerName(), roomName, map,
                                             DemoNetworkSettings.ROOM_CAPACITY, turnRate, password, NetworkClock.SYSTEM);

            RecentMaps.read(this.getRecentMapsFile()).add(map.getName());
            this.leaveChat();
            this.session = null;
            demo.getClient().getScenes().replace(new NetworkRoomScene(demo, this.menu, this.settings, lobby));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.showStatus("Cannot host: " + exception.getMessage());
        }
    }

    /**
     * Joins the selected joinable room; a private room first asks for its password.
     */
    private void join() {
        BeaconLinkSession current = this.session;
        int selection = ((TableBox) this.requireControl(ROOMS_ID)).getSelection();

        if (current == null || this.getPopup() != null || selection < 0 || selection >= this.shownRooms.size() ||
            !this.shownRooms.get(selection).isJoinable()) {
            return;
        }

        FoundRoom room = this.shownRooms.get(selection);

        if (!room.isPrivate()) {
            this.join(room, "");

            return;
        }

        try {
            this.popup = DemoPopup.openPrompt(this.getDemo(), PASSWORD_TEXT, room.getSettings().getRoomName(), true, (prompt, id) -> {
                prompt.close();

                if (id == DemoPopup.OK_ID) {
                    this.join(room, prompt.requireControl(DemoPopup.INPUT_ID).getText());
                }
            });
        } catch (IOException exception) {
            this.showStatus("Cannot join " + room.getSettings().getRoomName() + ": " + exception.getMessage());
        }
    }

    private void join(@NotNull FoundRoom room, @NotNull String password) {
        DemoClient demo = this.getDemo();
        BeaconLinkSession current = this.session;

        if (current == null) {
            return;
        }

        try {
            DemoLobby lobby = DemoLobby.join(current, room, demo.getClient().getLaunch().getIdentity(), demo.getContent().createSetupCodec(),
                                             demo.getMaps(), demo.getTaggedPlayerName(), password, NetworkClock.SYSTEM);

            this.leaveChat();
            this.session = null;
            demo.getClient().getScenes().replace(new NetworkRoomScene(demo, this.menu, this.settings, lobby));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            this.showStatus("Cannot join " + room.getSettings().getRoomName() + ": " + exception.getMessage());
        }
    }

    /**
     * Follows the presence choice: Away asks for an optional away text and shows this player away (Cancel keeps it online), Online shows the
     * player online again; an away status set by {@code /away} shows in the choice.
     */
    private void showPresence() {
        ChannelChat current = this.chat;
        ComboBox choice = (ComboBox) this.requireControl(PRESENCE_ID);
        int selection = choice.getSelection();

        if (current == null || this.getPopup() != null) {
            return;
        }

        if (selection != this.shownPresence) {
            this.shownPresence = selection;

            if (selection == AWAY_CHOICE && !current.isAway()) {
                this.askAwayText(current, choice);
            } else if (selection == ONLINE_CHOICE && current.isAway()) {
                current.setAway(false);
            }

            return;
        }

        int shown = current.isAway() ? AWAY_CHOICE : ONLINE_CHOICE;

        if (selection != shown) {
            choice.setSelection(shown);
            this.shownPresence = shown;
        }
    }

    private void askAwayText(@NotNull ChannelChat current, @NotNull ComboBox choice) {
        try {
            this.popup = DemoPopup.openPrompt(this.getDemo(), AWAY_TEXT, AWAY_QUESTION, true, (prompt, id) -> {
                String text = prompt.requireControl(DemoPopup.INPUT_ID).getText().strip();

                prompt.close();

                if (id != DemoPopup.OK_ID) {
                    choice.setSelection(ONLINE_CHOICE);
                    this.shownPresence = ONLINE_CHOICE;
                } else if (text.isEmpty()) {
                    current.setAway(true);
                } else {
                    current.submit(AWAY_COMMAND + text);
                }
            });
        } catch (IOException exception) {
            LOGGER.warning("BEACON.LINK: {0}", exception.getMessage());
        }
    }

    /**
     * Searches the rooms again once the running search is complete.
     */
    private void refresh() {
        BeaconLinkSession current = this.session;
        RoomSearch running = this.search;

        if (current != null && current.getObservedAddress() != null && (running == null || running.isComplete())) {
            this.search = current.searchRooms();
        }
    }

    private void send() {
        ChannelChat current = this.chat;
        Control input = this.requireControl(CHAT_INPUT_ID);

        if (current == null) {
            return;
        }

        current.submit(input.getText());
        input.setText("");
    }

    /**
     * Shows a side panel: its table, its header and its actions.
     */
    private void showSidePanel(@NotNull SidePanel panel) {
        Dialog dialog = this.requireDialog();
        Control side = this.requireControl(SIDE_ID);
        int rowHeight = this.getDemo().getFont().getLineHeight();
        TableBox table = switch (panel) {
            case USERS -> new TableBox(side.getDefinition(), rowHeight, USER_COLUMNS, USER_HEADERS, true);
            case CHANNELS -> new TableBox(side.getDefinition(), rowHeight, CHANNEL_COLUMNS, CHANNEL_HEADERS, false);
            case FRIENDS -> new TableBox(side.getDefinition(), rowHeight, USER_COLUMNS, FRIEND_HEADERS, false);
        };
        Control first = this.requireControl(FIRST_ACTION_ID);
        Control second = this.requireControl(SECOND_ACTION_ID);

        dialog.replaceControl(side, table);

        this.sidePanel    = panel;
        this.sidePlayers  = List.of();
        this.sideChannels = List.of();

        first.setText(panel == SidePanel.CHANNELS ? "Join" : "Whisper");
        second.setText(panel == SidePanel.FRIENDS ? "Remove" : "Add friend");
        second.setVisible(panel != SidePanel.CHANNELS);

        for (int tab : new int[]{USERS_TAB_ID, CHANNELS_TAB_ID, FRIENDS_TAB_ID}) {
            ((Button) this.requireControl(tab)).setSelected(tab == USERS_TAB_ID + panel.ordinal());
        }

        if (this.chat != null) {
            this.showSide();
        }
    }

    private void showSide() {
        ChannelChat current = this.chat;
        TableBox table = (TableBox) this.requireControl(SIDE_ID);
        List<TableRow> rows = new ArrayList<>();

        if (current == null) {
            return;
        }

        ChatChannel channel = current.getChannel();
        String heading = channel == null ? CommunityText.NO_CHANNEL.get(TEXT_LOCALE)
                                         : String.format(Locale.ROOT, COUNT_FORMAT, channel.getName(), channel.getMembers().size());

        if (!heading.equals(this.requireControl(CHANNEL_ID).getText())) {
            this.requireControl(CHANNEL_ID).setText(heading);
        }

        switch (this.sidePanel) {
            case USERS -> {
                List<LinkPlayer> players = new ArrayList<>();

                for (PlayerPresence member : current.getMembers()) {
                    int color = current.isSelf(member) ? SELF_COLOR
                                                       : current.isFriend(member) ? FRIEND_COLOR
                                                                                  : member.canPlayTogether() ? PLAIN_COLOR
                                                                                                             : GREY_COLOR;

                    players.add(member.getPlayer());
                    rows.add(new TableRow(List.of(member.getTaggedName(), current.describeMod(member), current.getStatusText(member)),
                                          color, member.getLatencyEstimate().getLatencyClass()));
                }

                this.sidePlayers = players;
            }
            case CHANNELS -> {
                List<String> names = new ArrayList<>();

                for (ChannelSummary summary : current.listChannels()) {
                    boolean joined = channel != null && channel.getName().equalsIgnoreCase(summary.getName());

                    names.add(summary.getName());
                    rows.add(new TableRow(List.of(summary.getName(), Integer.toString(summary.getMemberCount())),
                                          joined ? SELF_COLOR : PLAIN_COLOR, null));
                }

                this.sideChannels = names;
            }
            case FRIENDS -> {
                List<LinkPlayer> players = new ArrayList<>();

                for (LinkPlayer friend : current.getFriends()) {
                    PlayerPresence presence = current.findPresence(friend);
                    String mod = presence == null ? CommunityText.UNKNOWN.get(TEXT_LOCALE)
                                                  : presence.getModId() + MOD_SEPARATOR + presence.getModVersion();

                    players.add(friend);
                    rows.add(new TableRow(List.of(current.getTaggedName(friend), current.getStatusText(presence), mod),
                                          presence == null ? GREY_COLOR : FRIEND_COLOR, null));
                }

                this.sidePlayers = players;
            }
        }

        if (!rows.equals(table.getRows())) {
            table.setRows(rows);
        }
    }

    /**
     * Whispers the selected user or friend (the input gets {@code /w Name#tag }), or joins the selected channel.
     */
    private void runFirstAction() {
        ChannelChat current = this.chat;
        int selection = ((TableBox) this.requireControl(SIDE_ID)).getSelection();

        if (current == null || selection < 0) {
            return;
        }

        if (this.sidePanel == SidePanel.CHANNELS) {
            if (selection < this.sideChannels.size()) {
                current.join(this.sideChannels.get(selection));
            }

            return;
        }

        if (selection < this.sidePlayers.size()) {
            this.requireControl(CHAT_INPUT_ID).setText(current.createWhisperStart(this.sidePlayers.get(selection)));
            this.requireDialog().setFocused(this.requireControl(CHAT_INPUT_ID));
        }
    }

    /**
     * Adds the selected user as a friend, or removes the selected friend.
     */
    private void runSecondAction() {
        ChannelChat current = this.chat;
        int selection = ((TableBox) this.requireControl(SIDE_ID)).getSelection();

        if (current == null || selection < 0 || selection >= this.sidePlayers.size()) {
            return;
        }

        LinkPlayer player = this.sidePlayers.get(selection);

        if (this.sidePanel == SidePanel.FRIENDS) {
            current.removeFriend(player);
        } else {
            current.addFriend(current.getTaggedName(player));
        }
    }

    private void showNodes(@NotNull BeaconLinkSession current) {
        List<LinkNode> nodes = current.getNodes();
        LinkFailure failure = current.getFailure();
        InetSocketAddress observed = current.getObservedAddress();
        long registered = nodes.stream().filter(node -> node.getState() == LinkNodeState.REGISTERED).count();
        String text;

        if (failure != null) {
            text = failure.getDescription();
        } else if (observed == null) {
            text = "Connecting";
        } else {
            text = registered + "/" + nodes.size();
        }

        if (!text.equals(this.requireControl(NODES_ID).getText())) {
            this.requireControl(NODES_ID).setText(text);
        }
    }

    private void showRooms(@NotNull RoomSearch running, @NotNull BeaconLinkSession current) {
        List<FoundRoom> found = new ArrayList<>(running.getRooms());
        TableBox table = (TableBox) this.requireControl(ROOMS_ID);
        PlayerPresence self = current.getCommunity().getSelf();
        String ownMod = self.getModId() + MOD_SEPARATOR + self.getModVersion();
        List<TableRow> rows = new ArrayList<>();

        found.addAll(running.getOtherRooms());
        this.applyFilter();

        List<FoundRoom> rooms = this.filter.select(found);

        for (FoundRoom room : rooms) {
            RoomSettings roomSettings = room.getSettings();
            boolean ownVersion = room.getJoinRefusal() == null;
            String mod = room.getModId().isEmpty() ? ownVersion ? ownMod : CommunityText.OTHER_VERSION.get(TEXT_LOCALE)
                                                   : room.getModId() + MOD_SEPARATOR + room.getModVersion();

            rows.add(new TableRow(List.of(roomSettings.getRoomName(), roomSettings.getMapName(),
                                          roomSettings.getPlayers() + "/" + roomSettings.getCapacity(),
                                          Integer.toString(DemoLobby.readTurnRate(roomSettings)), mod, describeState(room)),
                                  room.isJoinable() ? PLAIN_COLOR : GREY_COLOR, room.getLatencyEstimate().getLatencyClass()));
        }

        this.shownRooms = rooms;

        if (!rows.equals(table.getRows())) {
            table.setRows(rows);
        }
    }

    /**
     * Describes a room's state: closed for another version of the mod, started, full, private or open.
     */
    private static @NotNull String describeState(@NotNull FoundRoom room) {
        RoomSettings roomSettings = room.getSettings();

        if (room.getJoinRefusal() != null) {
            return CLOSED_STATE;
        }

        if (roomSettings.isStarted()) {
            return STARTED_STATE;
        }

        if (roomSettings.getPlayers() >= roomSettings.getCapacity()) {
            return FULL_STATE;
        }

        return room.isPrivate() ? PRIVATE_STATE : OPEN_STATE;
    }

    /**
     * Takes the filter's search text and choices from the dialog.
     */
    private void applyFilter() {
        int latency = ((ComboBox) this.requireControl(LATENCY_ID)).getSelection();
        int players = ((ComboBox) this.requireControl(PLAYERS_ID)).getSelection();

        this.filter.setQuery(this.requireControl(SEARCH_ID).getText());
        this.filter.setWorstLatency(latency == GOOD_LATENCY_CHOICE ? LatencyClass.GOOD : latency == FAIR_LATENCY_CHOICE ? LatencyClass.FAIR : null);
        this.filter.setMaximumCapacity(players <= 0 ? RoomFilter.ANY_CAPACITY : PLAYER_COUNTS[players - 1]);
        this.filter.setClosedRoomsShown(((ComboBox) this.requireControl(VERSIONS_ID)).getSelection() <= 0);
    }

    private void showUnavailable(@NotNull String text) {
        this.requireControl(NODES_ID).setText(text);

        for (int id : new int[]{HOST_ID, JOIN_ID, REFRESH_ID, SEND_ID, PRESENCE_ID}) {
            this.requireControl(id).setEnabled(false);
        }
    }

    private void showStatus(@NotNull String status) {
        this.requireControl(STATUS_ID).setText(status);
    }

    /**
     * Closes the scene: releases what it holds, its popup, the chat's listener and the Beacon.Link session unless a room took it.
     */
    @Override
    public void close() {
        BeaconLinkSession current = this.session;
        DemoPopup shownPopup = this.popup;

        this.popup = null;

        if (shownPopup != null) {
            shownPopup.close();
        }

        this.leaveChat();
        this.session = null;
        this.search  = null;

        if (current != null) {
            current.close();
        }

        super.close();
    }
}
