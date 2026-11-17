package org.fogofwarlabs.sc4j.launcher.ui.session;

import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.PresenceContent;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * Shows the logged-in account at the right end of the app bar and its menu: the account with its state over Friends, My status, Sign out
 * everywhere else and Log out; each reads and commands the account's {@link LauncherSessionView}.
 * <p>
 * Friends opens the friends with their merged state, as many as the launcher window leaves room for, the own friend code with Copy, adding a
 * friend by code, and Whisper, Ignore and Remove for the selected friend; Whisper closes the panel and hands the friend to the window, which
 * shows the friend's whisper conversation on the Chat screen. My status chooses between Online and Away with an away text, and Sign out
 * everywhere else asks once. Without a connection the panels and the menu say why, and Sign out everywhere else is disabled. Log out closes
 * the menu and hands the log out to the window.
 */
public final class AccountMenu {

    /**
     * Receives what the menu hands to the window.
     */
    public interface Actions {
        /**
         * Called when the user logs out.
         */
        void onLogOut();

        /**
         * Called when the user copies text, the own friend code.
         *
         * @param text the text for the clipboard
         */
        void onCopy(@NotNull String text);

        /**
         * Called when the user whispers to a friend.
         *
         * @param player     the friend's friend code
         * @param taggedName the friend's name and tag
         */
        void onWhisper(@NotNull FriendCode player, @NotNull String taggedName);
    }

    /**
     * Shows the Friends panel: the own friend code with Copy, the friends, Whisper, Ignore and Remove for the selected friend, and adding a
     * friend by code.
     */
    private final class FriendsPanel {
        private final @NotNull ModalDialog dialog;
        private final @NotNull ListView<FriendView> list;
        private final @NotNull Button whisper;
        private final @NotNull Button ignore;
        private final @NotNull Button remove;
        private final @NotNull TextField added;

        /**
         * Creates a {@link FriendsPanel} instance showing the session's friends.
         */
        private FriendsPanel() {
            LauncherTexts texts = AccountMenu.this.texts;
            Label code = ComponentUtils.paragraph(AccountMenu.this.session.getFriendCode().getGroupedText(), "text-mono");
            Button copy = ComponentUtils.button(texts.get("session.friends.copy"), ButtonRole.SECONDARY);
            Button add = ComponentUtils.button(texts.get("session.friends.add"), ButtonRole.SECONDARY);
            Button close = ComponentUtils.button(texts.get("session.close"), ButtonRole.SECONDARY);

            this.dialog  = new ModalDialog(texts.get("session.friends.title"), ModalDialog.Height.TALL);
            this.list    = ComponentUtils.sunkenListView(ListItemSize.COMPACT);
            this.whisper = ComponentUtils.button(texts.get("session.friends.whisper"), ButtonRole.SECONDARY);
            this.ignore  = ComponentUtils.button(texts.get("session.friends.ignore"), ButtonRole.SECONDARY);
            this.remove  = ComponentUtils.button(texts.get("session.friends.remove"), ButtonRole.SECONDARY);
            this.added   = new TextField();

            // The code wraps between its groups; Copy takes it whole.
            code.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(code, Priority.ALWAYS);
            copy.setOnAction(event -> AccountMenu.this.actions.onCopy(AccountMenu.this.session.getFriendCode().getText()));

            this.list.setCellFactory(view -> new ListItemCell<>(AccountMenu.this.playerTexts::createItem));
            this.list.setPlaceholder(ComponentUtils.paragraph(texts.get("session.friends.empty"), "text-caption"));
            this.list.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> this.updateButtons());

            this.whisper.setOnAction(event -> {
                FriendView friend = this.list.getSelectionModel().getSelectedItem();

                if (friend != null) {
                    AccountMenu.this.closeDialog();
                    AccountMenu.this.actions.onWhisper(friend.getCode(), friend.getTaggedName());
                }
            });
            this.ignore.setOnAction(event -> {
                FriendView friend = this.list.getSelectionModel().getSelectedItem();

                if (friend != null) {
                    AccountMenu.this.session.setIgnored(friend.getCode(), !friend.isIgnored());
                }
            });
            this.remove.setOnAction(event -> {
                FriendView friend = this.list.getSelectionModel().getSelectedItem();

                if (friend != null) {
                    AccountMenu.this.session.removeFriend(friend.getCode());
                }
            });

            this.added.textProperty().addListener((observable, previous, current) -> this.dialog.showMessage(null));
            this.added.setOnAction(event -> this.add());
            HBox.setHgrow(this.added, Priority.ALWAYS);
            add.setOnAction(event -> this.add());

            AccountMenu.this.addConnectionNotice(this.dialog);
            this.dialog.addContent(ComponentUtils.field(texts.get("session.friends.code"), ComponentUtils.row(Spacing.S, code, copy)));
            this.dialog.addGrowingContent(this.list);
            this.dialog.addContent(ComponentUtils.field(texts.get("session.friends.add.code"), ComponentUtils.row(Spacing.S, this.added, add)));
            this.dialog.setButtons(List.of(this.whisper, this.ignore, this.remove), close, null);
            close.setOnAction(event -> AccountMenu.this.closeDialog());

            this.update();
        }

        /**
         * Shows the session's latest friends, keeping the selected friend selected.
         */
        private void update() {
            FriendView selected = this.list.getSelectionModel().getSelectedItem();

            this.list.getItems().setAll(AccountMenu.this.session.getFriends());

            if (selected != null) {
                for (FriendView friend : this.list.getItems()) {
                    if (friend.getCode().equals(selected.getCode())) {
                        this.list.getSelectionModel().select(friend);
                    }
                }
            }

            this.updateButtons();
        }

        private void updateButtons() {
            FriendView selected = this.list.getSelectionModel().getSelectedItem();
            LauncherTexts texts = AccountMenu.this.texts;

            this.whisper.setDisable(selected == null || selected.getState() == FriendState.OFFLINE);
            this.ignore.setDisable(selected == null);
            this.ignore.setText(texts.get(selected != null && selected.isIgnored() ? "session.friends.unignore" : "session.friends.ignore"));
            this.remove.setDisable(selected == null);
        }

        /**
         * Adds the friend of the entered code, or says in one line why not: the text is no code, it is the account's own code, the player is
         * a friend already, or the list is full.
         */
        private void add() {
            LauncherSessionView session = AccountMenu.this.session;
            LauncherTexts texts = AccountMenu.this.texts;
            List<FriendView> friends = session.getFriends();
            FriendCode code;

            try {
                code = FriendCode.parse(this.added.getText());
            } catch (IllegalArgumentException exception) {
                this.dialog.showMessage(texts.get("session.friends.invalid"));

                return;
            }

            if (code.equals(session.getFriendCode())) {
                this.dialog.showMessage(texts.get("session.friends.own"));

                return;
            }

            if (friends.stream().anyMatch(friend -> friend.getCode().equals(code))) {
                this.dialog.showMessage(texts.get("session.friends.known"));

                return;
            }

            if (friends.size() >= CommunitySession.MAXIMUM_FRIENDS) {
                this.dialog.showMessage(texts.format("session.friends.full", CommunitySession.MAXIMUM_FRIENDS));

                return;
            }

            session.addFriend(code);
            this.added.clear();
        }
    }


    private final @NotNull LauncherShell shell;
    private final @NotNull LauncherTexts texts;
    private final @NotNull String taggedName;
    private final @NotNull LauncherSessionView session;
    private final @NotNull PlayerTexts playerTexts;
    private final @NotNull Actions actions;

    private @Nullable VBox menu;
    private @Nullable ModalDialog dialog;
    private @Nullable FriendsPanel friendsPanel;

    /**
     * Creates a {@link AccountMenu} instance; {@link #update()} shows the account in the app bar.
     *
     * @param shell      the shell whose app bar shows the account and which shows the menu and its dialogs
     * @param texts      the launcher texts of the display language
     * @param taggedName the account's name and tag ({@code Name#abcd12})
     * @param session    the account's session
     * @param modNames   finds the display name of a mod by its id, the id itself for a mod the install does not have
     * @param actions    receives what the menu hands to the window
     */
    public AccountMenu(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull String taggedName, @NotNull LauncherSessionView session,
                       @NotNull Function<@NotNull String, @NotNull String> modNames, @NotNull Actions actions) {
        this.shell       = shell;
        this.texts       = texts;
        this.taggedName  = taggedName;
        this.session     = session;
        this.playerTexts = new PlayerTexts(texts, modNames);
        this.actions     = actions;

        this.menu         = null;
        this.dialog       = null;
        this.friendsPanel = null;
    }

    /**
     * Shows the session's latest views: the account's state in the app bar, in the open menu and in the open panel.
     */
    public void update() {
        VBox opened = this.menu;
        FriendsPanel friends = this.friendsPanel;

        this.shell.setAccount(this.taggedName, PlayerTexts.getTone(this.session.getSelf()));

        if (friends != null) {
            friends.update();
        }

        // A click outside the menu closes it in the shell; a closed menu has left the scene.
        if (opened != null && opened.getScene() != null) {
            this.open();
        }
    }

    /**
     * Opens the menu under the account.
     */
    public void open() {
        SelfView self = this.session.getSelf();
        ListItem heading = new ListItem(this.taggedName).setMarker(PlayerTexts.getTone(self)).addCaption(this.playerTexts.describe(self));
        Button friends = ComponentUtils.menuButton(this.texts.get("session.menu.friends"));
        Button status = ComponentUtils.menuButton(this.texts.get("session.menu.status"));
        Button signOut = ComponentUtils.menuButton(this.texts.get("session.menu.sign.out"));
        Button logOutButton = ComponentUtils.menuButton(this.texts.get("session.menu.log.out"));
        VBox opened;

        if (self.getState() == SessionState.CONNECTED && self.getDeviceCount() > 1) {
            heading.addCaption(this.texts.format("session.devices", self.getDeviceCount()));
        }

        opened = ComponentUtils.menuPanel(heading.createNode(), new Separator(), friends, status, signOut, new Separator(), logOutButton);

        friends.setOnAction(event -> this.showDialog(new FriendsPanel()));
        status.setOnAction(event -> this.openStatus());
        signOut.setOnAction(event -> this.openSignOut());
        signOut.setDisable(self.getState() != SessionState.CONNECTED);
        logOutButton.setOnAction(event -> {
            this.closeMenu();
            this.actions.onLogOut();
        });

        this.menu = opened;
        this.shell.openMenu(opened);
    }

    private void openStatus() {
        SelfView self = this.session.getSelf();
        ModalDialog shown = new ModalDialog(this.texts.get("session.status.title"));
        ToggleGroup group = new ToggleGroup();
        ToggleButton online = new ToggleButton(this.texts.get("session.status.online"));
        ToggleButton away = new ToggleButton(this.texts.get("session.status.away"));
        TextField awayText = new TextField(self.getAwayText());
        Button cancel = ComponentUtils.button(this.texts.get("session.cancel"), ButtonRole.SECONDARY);
        Button save = ComponentUtils.button(this.texts.get("session.save"), ButtonRole.PRIMARY);

        online.setToggleGroup(group);
        away.setToggleGroup(group);
        group.selectToggle(self.getStatus() == SelfStatus.AWAY ? away : online);
        // A toggle group lets the selected button be clicked off; one of the two statuses always stays chosen.
        group.selectedToggleProperty().addListener((observable, previous, current) -> {
            if (current == null) {
                group.selectToggle(previous);
            }
        });
        awayText.disableProperty().bind(away.selectedProperty().not());
        awayText.textProperty().addListener((observable, previous, current) -> shown.showMessage(null));

        this.addConnectionNotice(shown);
        shown.addContent(ComponentUtils.row(Spacing.S, online, away),
                         ComponentUtils.field(this.texts.get("session.status.away.text"), awayText));
        shown.setButtons(cancel, save);
        cancel.setOnAction(event -> this.closeDialog());
        save.setOnAction(event -> {
            SelfStatus status = away.isSelected() ? SelfStatus.AWAY : SelfStatus.ONLINE;
            String text = status == SelfStatus.AWAY ? awayText.getText().strip() : "";

            if (ChannelChat.getTextLength(text) > PresenceContent.MAXIMUM_AWAY_TEXT_LENGTH) {
                shown.showMessage(this.texts.get("session.status.too.long"));

                return;
            }

            this.closeDialog();
            this.session.setStatus(status, text);
        });

        this.showDialog(shown);
    }

    private void openSignOut() {
        ModalDialog shown = new ModalDialog(this.texts.get("session.sign.out.title"));
        Button cancel = ComponentUtils.button(this.texts.get("session.cancel"), ButtonRole.SECONDARY);
        Button signOut = ComponentUtils.button(this.texts.get("session.sign.out"), ButtonRole.DANGER);

        shown.addContent(ComponentUtils.paragraph(this.texts.format("session.sign.out.text", this.taggedName), "text-body"));
        shown.setButtons(cancel, signOut);
        cancel.setOnAction(event -> this.closeDialog());
        signOut.setOnAction(event -> {
            this.closeDialog();
            this.session.signOutOtherDevices();
        });

        this.showDialog(shown);
        cancel.requestFocus();
    }

    private void addConnectionNotice(@NotNull ModalDialog shown) {
        if (this.session.getSelf().getState() != SessionState.CONNECTED) {
            shown.addContent(ComponentUtils.notice(Tone.NEUTRAL, this.texts.get("session.not.connected")));
        }
    }

    private void closeMenu() {
        this.menu = null;
        this.shell.closeMenu();
    }

    private void showDialog(@NotNull FriendsPanel shown) {
        this.showDialog(shown.dialog);
        this.friendsPanel = shown;
    }

    /**
     * Shows a dialog in place of the menu and of the dialog shown before.
     */
    private void showDialog(@NotNull ModalDialog shown) {
        this.closeMenu();
        this.closeDialog();

        this.dialog = shown;
        this.shell.showDialog(shown);
    }

    private void closeDialog() {
        ModalDialog shown = this.dialog;

        this.dialog       = null;
        this.friendsPanel = null;

        if (shown != null) {
            this.shell.closeDialog(shown);
        }
    }
}
