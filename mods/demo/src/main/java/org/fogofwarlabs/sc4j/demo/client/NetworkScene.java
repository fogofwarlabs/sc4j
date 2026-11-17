package org.fogofwarlabs.sc4j.demo.client;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.demo.net.DemoLobby;
import org.fogofwarlabs.sc4j.demo.net.DemoNetworkSettings;
import org.fogofwarlabs.sc4j.engine.client.dialog.ListBox;
import org.fogofwarlabs.sc4j.engine.game.MapReference;
import org.fogofwarlabs.sc4j.engine.net.DatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.NetworkClock;
import org.fogofwarlabs.sc4j.engine.net.UdpDatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.CommunityText;
import org.fogofwarlabs.sc4j.engine.net.discovery.DiscoveredRoom;
import org.fogofwarlabs.sc4j.engine.net.discovery.LanRoomDiscovery;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows the demo's network screen on its tab of games on this computer: network games of two people on this computer, every socket on the
 * loopback address. Host opens a room with the first map, Join joins the room selected in the list of rooms or at the typed address, Refresh
 * asks the room discovery for the rooms again (the list fills from the answers), the Beacon.Link tab shows the games of the Beacon.Link
 * nodes ({@link BeaconLinkScene}), and Back returns to the main menu. A room shows on the room screen ({@link NetworkRoomScene}).
 */
public final class NetworkScene extends DemoMenuScene {
    /**
     * The pack id of the network screen's dialog.
     */
    public static final @NotNull String DIALOG = "network";
    /**
     * The id of the label that names the player: the account's name with its tag.
     */
    public static final int NAME_ID = 10;
    /**
     * The id of the address input.
     */
    public static final int ADDRESS_ID = 11;
    /**
     * The id of the room list.
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
     * The id of the tab of the games on this computer, on this screen and on the Beacon.Link screen.
     */
    public static final int LOCAL_TAB_ID = 30;
    /**
     * The id of the Beacon.Link tab, on this screen and on the Beacon.Link screen.
     */
    public static final int BEACON_LINK_TAB_ID = 31;

    private static final @NotNull Logger LOGGER = Logger.of(NetworkScene.class);
    /**
     * The demo's texts are English.
     */
    private static final @NotNull Locale TEXT_LOCALE = Locale.ENGLISH;

    private final @NotNull MainMenuScene menu;
    private final @NotNull DemoNetworkSettings settings;

    private @Nullable DatagramEndpoint discoveryEndpoint;
    private @Nullable LanRoomDiscovery discovery;
    private @NotNull List<@NotNull DiscoveredRoom> shownRooms;

    /**
     * Creates a {@link NetworkScene} instance.
     *
     * @param demo     the demo's shared services
     * @param menu     the main menu the back button returns to
     * @param settings where the network games run
     */
    public NetworkScene(@NotNull DemoClient demo, @NotNull MainMenuScene menu, @NotNull DemoNetworkSettings settings) {
        super(demo, DIALOG);

        this.menu     = menu;
        this.settings = settings;

        this.discoveryEndpoint = null;
        this.discovery         = null;
        this.shownRooms        = List.of();
    }

    /**
     * @return the rooms the list shows, in its order
     */
    public @NotNull List<@NotNull DiscoveredRoom> getShownRooms() {
        return this.shownRooms;
    }

    /**
     * Called when the scene opened and its dialog is built: fills the dialog's controls. The default fills nothing.
     * <p>
     * Fills the player's name and the address of the default lobby, opens the room discovery and asks for the rooms. A run without an
     * account has no Beacon.Link tab.
     */
    @Override
    protected void onOpened() {
        boolean guest = this.getDemo().getAccount() == null;

        this.requireControl(NAME_ID).setText(this.getDemo().getTaggedPlayerName());
        this.requireControl(ADDRESS_ID).setText(DemoNetworkSettings.LOOPBACK + ':' + this.settings.getLobbyAddress().getPort());
        this.requireControl(STATUS_ID).setText(guest ? CommunityText.NEEDS_ACCOUNT.get(TEXT_LOCALE) : "Host a game, or join one of this computer.");
        this.requireControl(BEACON_LINK_TAB_ID).setEnabled(!guest);

        try {
            DatagramEndpoint endpoint = UdpDatagramEndpoint.open(new InetSocketAddress(DemoNetworkSettings.LOOPBACK, DemoNetworkSettings.ANY_PORT));

            this.discoveryEndpoint = endpoint;
            this.discovery         = new LanRoomDiscovery(endpoint, NetworkClock.SYSTEM, this.getDemo().getClient().getLaunch().getIdentity());
            this.refresh();
        } catch (IOException exception) {
            this.showStatus("The room list is not available: " + exception.getMessage());
        }
    }

    /**
     * Updates the scene at the supplied time.
     * <p>
     * Takes the room discovery's answers and shows the rooms.
     *
     * @param time the client time in milliseconds
     */
    @Override
    public void update(long time) {
        LanRoomDiscovery current = this.discovery;

        if (current == null) {
            return;
        }

        try {
            current.poll();
        } catch (IOException exception) {
            LOGGER.warning("ROOM LIST: {0}", exception.getMessage());

            return;
        }

        List<DiscoveredRoom> rooms = current.getRooms();

        if (!rooms.equals(this.shownRooms)) {
            List<String> entries = new ArrayList<>(rooms.size());
            ListBox list = (ListBox) this.requireControl(ROOMS_ID);
            int selection = list.getSelection();

            for (DiscoveredRoom room : rooms) {
                entries.add(room.getRoom().getName() + "  " + room.getRoom().getMemberCount() + '/' + room.getRoom().getCapacity() +
                            (room.isJoinable() ? "" : "  (closed)"));
            }

            this.shownRooms = rooms;
            list.setEntries(entries);
            list.setSelection(selection < entries.size() ? selection : entries.isEmpty() ? ListBox.NO_SELECTION : 0);
        }
    }

    /**
     * Called when a control of the dialog is activated.
     * <p>
     * Host opens a room, Join joins the selected or typed room, Refresh asks for the rooms, the Beacon.Link tab shows the Beacon.Link games,
     * Back returns to the main menu.
     *
     * @param id the id of the activated control
     */
    @Override
    protected void onActivated(int id) {
        switch (id) {
            case HOST_ID -> this.host();
            case JOIN_ID -> this.join();
            case REFRESH_ID -> this.refresh();
            case BEACON_LINK_TAB_ID -> this.getDemo().getClient().getScenes().replace(new BeaconLinkScene(this.getDemo(), this.menu, this.settings, ""));
            case BACK_ID -> this.menu.show();
            default -> {
                // Labels, inputs and the list do nothing when activated.
            }
        }
    }

    private void host() {
        DemoClient demo = this.getDemo();
        MapReference map = demo.getMaps().getReferences().getFirst();

        try {
            DemoLobby lobby = DemoLobby.host(this.settings, demo.getClient().getLaunch().getIdentity(), demo.getContent().createSetupCodec(),
                                             demo.getMaps(), demo.getTaggedPlayerName(), DemoLobby.createRoomName(demo.getPlayerName()), map,
                                             NetworkClock.SYSTEM);

            demo.getClient().getScenes().replace(new NetworkRoomScene(demo, this.menu, this.settings, lobby));
        } catch (IOException | IllegalArgumentException exception) {
            this.showStatus("Cannot host: " + exception.getMessage());
        }
    }

    private void join() {
        DemoClient demo = this.getDemo();
        int selection = ((ListBox) this.requireControl(ROOMS_ID)).getSelection();
        InetSocketAddress address;

        try {
            address = selection >= 0 && selection < this.shownRooms.size() ? this.shownRooms.get(selection).getLobbyAddress()
                                                                           : DemoLobby.parseAddress(this.requireControl(ADDRESS_ID).getText());
        } catch (IllegalArgumentException exception) {
            this.showStatus(exception.getMessage());

            return;
        }

        try {
            DemoLobby lobby = DemoLobby.join(this.settings, demo.getClient().getLaunch().getIdentity(), demo.getContent().createSetupCodec(),
                                             demo.getMaps(), demo.getTaggedPlayerName(), address);

            demo.getClient().getScenes().replace(new NetworkRoomScene(demo, this.menu, this.settings, lobby));
        } catch (IOException exception) {
            this.showStatus("Cannot join " + address + ": " + exception.getMessage());
        }
    }

    private void refresh() {
        LanRoomDiscovery current = this.discovery;

        if (current == null) {
            return;
        }

        try {
            current.query(this.settings.getDiscoveryAddress());
        } catch (IOException exception) {
            this.showStatus("The room list is not available: " + exception.getMessage());
        }
    }

    private void showStatus(@NotNull String status) {
        this.requireControl(STATUS_ID).setText(status);
    }

    /**
     * Closes the scene: releases what it holds, the room discovery's socket.
     */
    @Override
    public void close() {
        DatagramEndpoint endpoint = this.discoveryEndpoint;

        this.discoveryEndpoint = null;
        this.discovery         = null;

        if (endpoint != null) {
            IgnoreException.execute(
                    endpoint::close
            );
        }

        super.close();
    }
}
