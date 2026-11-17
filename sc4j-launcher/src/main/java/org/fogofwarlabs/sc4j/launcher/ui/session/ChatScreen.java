package org.fogofwarlabs.sc4j.launcher.ui.session;

import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.fogofwarlabs.sc4j.launcher.ui.session.Conversations.Conversation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Shows the Chat screen of the logged-in account in three columns: on the left the channels the chat is in, at most
 * {@value CommunitySession#MAXIMUM_CHANNELS}, with Join, and the whisper conversations ({@link Conversations}), each with its unread count; in
 * the middle the shown conversation's log and the input; on the right the shown channel's members. The screen only shows the chat, which the
 * session runs while the account is logged in; a channel the chat joins is shown unless a whisper conversation is shown.
 * <p>
 * A channel's input says lines and runs slash commands ({@code /help} lists them); a whisper conversation's input whispers to its player. A
 * right click on a member, or on a name in the log, opens the player's menu with Whisper, Add or Remove friend, Ignore or Unignore, Copy name
 * and Show status, the commands of the account menu. Without a connection the screen says why.
 */
public final class ChatScreen {
    /**
     * Receives what the screen hands to the window.
     */
    public interface Actions {
        /**
         * Called when the user copies text, a player's name.
         *
         * @param text the text for the clipboard
         */
        void onCopy(@NotNull String text);
    }

    /**
     * Holds one line of the shown log: the sender's name, the player it names, the text, the text's style and a state line under it.
     */
    private static final class LogLine {
        private final @NotNull String prefix;
        private final @Nullable FriendCode player;
        private final @NotNull String playerName;
        private final @NotNull String text;
        private final @NotNull String textStyle;
        private final @Nullable String caption;

        /**
         * Creates a {@link LogLine} instance.
         *
         * @param prefix     the sender's name with its separator, empty for a line without a sender
         * @param player     the player the line names, or {@code null} for none
         * @param playerName the name and tag of the player the line names, empty for none
         * @param text       the text
         * @param textStyle  the style class of the text
         * @param caption    the state line under the text, or {@code null} for none
         */
        private LogLine(@NotNull String prefix, @Nullable FriendCode player, @NotNull String playerName, @NotNull String text,
                        @NotNull String textStyle, @Nullable String caption) {
            this.prefix     = prefix;
            this.player     = player;
            this.playerName = playerName;
            this.text       = text;
            this.textStyle  = textStyle;
            this.caption    = caption;
        }
    }

    /**
     * Shows one line of the log: the sender's name, which opens the player's menu, and the text, with a state line under a whisper that is
     * under way or was not delivered.
     */
    private final class LogCell extends ListCell<LogLine> {
        private LogCell() {
            ComponentUtils.markFirstRow(this);
        }

        /**
         * Updates the item associated with this cell.
         *
         * @param line  the new item for the cell
         * @param empty whether or not this cell represents data from the list
         */
        @Override
        protected void updateItem(@Nullable LogLine line, boolean empty) {
            super.updateItem(line, empty);

            if (empty || line == null) {
                this.setText(null);
                this.setGraphic(null);

                return;
            }

            Label text = ComponentUtils.createParagraph(line.text, line.textStyle);
            Label name = ComponentUtils.createLabel(line.prefix, "text-heading", "text-accent-strong");
            HBox row = ComponentUtils.createTextRow(Spacing.S, name, text);
            VBox graphic = ComponentUtils.createColumn(Spacing.XS, row);
            FriendCode player = line.player;

            if (line.prefix.isEmpty()) {
                row.getChildren().remove(name);
            } else if (player != null) {
                name.setOnContextMenuRequested(event -> ChatScreen.this.openPlayerMenu(player, line.playerName, event));
            }

            if (line.caption != null) {
                graphic.getChildren().add(ComponentUtils.createLabel(line.caption, "text-caption"));
            }

            ComponentUtils.setCellGraphic(this, graphic);
        }
    }

    private final @NotNull LauncherShell shell;
    private final @NotNull LauncherTexts texts;
    private final @NotNull String taggedName;
    private final @NotNull LauncherSessionView session;
    private final @NotNull Conversations conversations;
    private final @NotNull PlayerTexts playerTexts;
    private final @NotNull Actions actions;

    private final @NotNull VBox root;
    private final @NotNull ListView<ChannelView> channelList;
    private final @NotNull ListView<Conversation> conversationList;
    private final @NotNull Label logTitle;
    private final @NotNull Button leave;
    private final @NotNull ListView<LogLine> log;
    private final @NotNull TextField input;
    private final @NotNull Button send;
    private final @NotNull Label inputMessage;
    private final @NotNull Label memberCount;
    private final @NotNull ListView<FriendView> memberList;
    private final @NotNull HBox columns;

    private final @NotNull Set<@NotNull String> seenChannels;
    private @Nullable String shownChannel;
    private @Nullable Conversation shownConversation;
    private @NotNull List<?> shownSource;
    private boolean listed;
    private boolean updating;

    /**
     * Creates a {@link ChatScreen} instance; {@link #update()} shows the session's latest views.
     *
     * @param shell         the shell that shows the screen's menus and dialogs
     * @param texts         the launcher texts of the display language
     * @param taggedName    the account's name and tag ({@code Name#abcd12})
     * @param session       the account's session
     * @param conversations the account's whisper conversations and what the user read of them
     * @param modNames      finds the display name of a mod by its id, the id itself for a mod the install does not have
     * @param actions       receives what the screen hands to the window
     */
    public ChatScreen(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull String taggedName, @NotNull LauncherSessionView session,
                      @NotNull Conversations conversations, @NotNull Function<@NotNull String, @NotNull String> modNames, @NotNull Actions actions) {
        Button join = ComponentUtils.createSmallButton(texts.get("chat.join"), ButtonRole.SECONDARY);

        this.shell         = shell;
        this.texts         = texts;
        this.taggedName    = taggedName;
        this.session       = session;
        this.conversations = conversations;
        this.playerTexts   = new PlayerTexts(texts, modNames);
        this.actions       = actions;

        this.root             = ComponentUtils.createColumn(Spacing.L);
        this.channelList      = ComponentUtils.createListView(ListItemSize.COMPACT);
        this.conversationList = ComponentUtils.createListView(ListItemSize.COMPACT);
        this.logTitle         = ComponentUtils.createLabel("");
        this.leave            = ComponentUtils.createSmallButton(texts.get("chat.leave"), ButtonRole.SECONDARY);
        this.log              = ComponentUtils.createLogListView();
        this.input            = new TextField();
        this.send             = ComponentUtils.createButton(texts.get("session.whispers.send"), ButtonRole.PRIMARY);
        this.inputMessage     = ComponentUtils.createLabel("", "field-message");
        this.memberCount      = ComponentUtils.createLabel("", "text-caption");
        this.memberList       = ComponentUtils.createListView(ListItemSize.COMPACT);
        this.columns          = ComponentUtils.createRow(Spacing.L, this.createSide(join), this.createLog(), this.createMembers());

        this.seenChannels      = new HashSet<>();
        this.shownChannel      = null;
        this.shownConversation = null;
        this.shownSource       = List.of();
        this.listed            = false;
        this.updating          = false;

        join.setOnAction(event -> this.openJoin());
        this.columns.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(this.columns, Priority.ALWAYS);
    }

    /**
     * @return the screen's root node
     */
    public @NotNull VBox getRoot() {
        return this.root;
    }

    /**
     * @return the name of the channel the screen shows, or {@code null} while it shows a whisper conversation or nothing
     */
    public @Nullable String getShownChannel() {
        return this.shownChannel;
    }

    /**
     * @return the friend code of the player whose whisper conversation the screen shows, or {@code null} while it shows a channel or nothing
     */
    public @Nullable FriendCode getShownPlayer() {
        Conversation conversation = this.shownConversation;

        return conversation == null ? null : conversation.getCode();
    }

    /**
     * Shows the session's latest views: the channels, the conversations, the shown conversation's log and the shown channel's members, or
     * why the session has no connection.
     */
    public void update() {
        SelfView self = this.session.getSelf();

        if (self.getState() != SessionState.CONNECTED) {
            String text = self.getState() == SessionState.CONNECTING || self.getFailure() == null
                          ? this.playerTexts.describe(self)
                          : this.texts.get("session.not.connected") + " " + this.playerTexts.describe(self);

            this.root.getChildren().setAll(ComponentUtils.createPanel(ComponentUtils.createPanelBody(ComponentUtils.createNotice(Tone.NEUTRAL, text))));

            return;
        }

        if (this.root.getChildren().size() != 1 || this.root.getChildren().getFirst() != this.columns) {
            this.root.getChildren().setAll(this.columns);
        }

        this.updateLists();
        this.showConversation();
        this.channelList.refresh();
        this.conversationList.refresh();
    }

    /**
     * Shows a player's whisper conversation, listed while it has no whispers too, and focuses the input.
     *
     * @param code       the player's friend code
     * @param playerName the player's name and tag
     */
    public void showWhispers(@NotNull FriendCode code, @NotNull String playerName) {
        this.shownChannel      = null;
        this.shownConversation = this.conversations.create(code, playerName);
        this.update();
        this.input.requestFocus();
    }

    private @NotNull VBox createSide(@NotNull Button join) {
        VBox channels = ComponentUtils.createPanel(this.texts.get("chat.channels"), ComponentUtils.createFlushPanelBody(this.channelList), join);
        VBox whispers = ComponentUtils.createPanel(this.texts.get("session.whispers.title"), ComponentUtils.createFlushPanelBody(this.conversationList));
        VBox side = ComponentUtils.createColumn(Spacing.L, channels, whispers);

        this.channelList.setCellFactory(view -> new ListItemCell<>(channel -> this.createChannelItem(channel, true)));
        this.channelList.setPlaceholder(ComponentUtils.createParagraph(this.texts.get("chat.channels.empty"), "text-caption"));
        this.channelList.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> {
            if (!this.updating && current != null) {
                this.shownChannel      = current.getName();
                this.shownConversation = null;
                this.update();
            }
        });

        this.conversationList.setCellFactory(view -> new ListItemCell<>(this::createConversationItem));
        this.conversationList.setPlaceholder(ComponentUtils.createParagraph(this.texts.get("session.whispers.empty"), "text-caption"));
        this.conversationList.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> {
            if (!this.updating && current != null) {
                this.shownChannel      = null;
                this.shownConversation = current;
                this.update();
            }
        });

        VBox.setVgrow(channels, Priority.ALWAYS);
        VBox.setVgrow(whispers, Priority.ALWAYS);
        ComponentUtils.setColumnWidth(side, ColumnWidth.NARROW);

        return side;
    }

    private @NotNull VBox createLog() {
        VBox panel = ComponentUtils.createPanelWithFooter(this.logTitle, ComponentUtils.createFlushPanelBody(this.log),
                                                          ComponentUtils.createInputFooter(this.input, this.send, this.inputMessage), this.leave);

        HBox.setHgrow(panel, Priority.ALWAYS);
        ComponentUtils.fillHeight(panel);

        this.log.setCellFactory(view -> new LogCell());
        VBox.setVgrow(this.log, Priority.ALWAYS);
        this.leave.setOnAction(event -> {
            String channel = this.shownChannel;

            if (channel != null) {
                this.session.leaveChannel(channel);
            }
        });

        this.input.setPromptText(this.texts.get("session.whispers.message"));
        this.input.textProperty().addListener((observable, previous, current) -> this.showInputMessage(null));
        this.input.setOnAction(event -> this.submit());
        this.send.setOnAction(event -> this.submit());
        this.inputMessage.setWrapText(true);
        this.showInputMessage(null);

        return panel;
    }

    private @NotNull VBox createMembers() {
        VBox panel = ComponentUtils.createPanel(this.texts.get("chat.members"), ComponentUtils.createFlushPanelBody(this.memberList), this.memberCount);

        this.memberList.setCellFactory(view -> {
            ListItemCell<FriendView> cell = new ListItemCell<>(this.playerTexts::createItem);

            cell.setOnContextMenuRequested(event -> {
                FriendView member = cell.getItem();

                if (member != null) {
                    this.openPlayerMenu(member.getCode(), member.getTaggedName(), event);
                }
            });

            return cell;
        });
        this.memberList.setPlaceholder(ComponentUtils.createParagraph(this.texts.get("chat.members.empty"), "text-caption"));
        ComponentUtils.setColumnWidth(panel, ColumnWidth.NARROW);

        return panel;
    }

    /**
     * Shows the session's channels and conversations in the lists, shows a channel the chat joined since, and keeps the shown conversation
     * selected.
     */
    private void updateLists() {
        List<ChannelView> channels = this.session.getChannels();
        Conversation opened = this.shownConversation;
        List<Conversation> listed = this.conversations.list(opened);
        String shown = this.shownChannel;

        // A channel the chat joined since the last update replaces a shown channel; the channels of a screen's first update are not new.
        for (ChannelView channel : channels) {
            if (this.seenChannels.add(channel.getName()) && this.listed && this.shownConversation == null) {
                shown = channel.getName();
            }
        }

        this.listed = true;

        this.seenChannels.retainAll(channels.stream().map(ChannelView::getName).toList());

        if (this.shownConversation == null && (shown == null || !this.seenChannels.contains(shown))) {
            shown = channels.isEmpty() ? null : channels.getFirst().getName();
        }

        this.shownChannel = this.shownConversation == null ? shown : null;
        this.updating     = true;

        try {
            if (!channels.equals(this.channelList.getItems())) {
                this.channelList.getItems().setAll(channels);
            }

            if (!listed.equals(this.conversationList.getItems())) {
                this.conversationList.getItems().setAll(listed);
            }

            this.selectShown(channels, listed);
        } finally {
            this.updating = false;
        }
    }

    private void selectShown(@NotNull List<@NotNull ChannelView> channels, @NotNull List<@NotNull Conversation> listed) {
        String channel = this.shownChannel;
        Conversation conversation = this.shownConversation;

        this.channelList.getSelectionModel().clearSelection();
        this.conversationList.getSelectionModel().clearSelection();

        for (ChannelView candidate : channels) {
            if (candidate.getName().equals(channel)) {
                this.channelList.getSelectionModel().select(candidate);
            }
        }

        for (Conversation candidate : listed) {
            if (conversation != null && candidate.getCode().equals(conversation.getCode())) {
                this.shownConversation = candidate;
                this.conversationList.getSelectionModel().select(candidate);
            }
        }
    }

    /**
     * Shows the shown conversation's title, log and members, reads it, and sets up the input for it.
     */
    private void showConversation() {
        String channel = this.shownChannel;
        Conversation conversation = this.shownConversation;
        List<?> source;
        List<LogLine> lines;
        List<FriendView> members = List.of();

        if (channel != null) {
            List<ChatEntryView> entries = this.session.getChatEntries().stream().filter(entry -> channel.equals(entry.getChannel())).toList();

            source = entries;
            lines  = new ArrayList<>(entries.size());

            for (ChatEntryView entry : entries) {
                lines.add(this.createLine(entry));
            }

            for (ChannelView candidate : this.session.getChannels()) {
                if (candidate.getName().equals(channel)) {
                    members = candidate.getMembers();
                }
            }

            this.logTitle.setText(channel);
            this.conversations.readLines(channel);
        } else if (conversation != null) {
            List<WhisperView> whispers = this.conversations.listWhispers(conversation.getCode());

            source = whispers;
            lines  = new ArrayList<>(whispers.size());

            for (WhisperView whisper : whispers) {
                lines.add(this.createLine(whisper));
            }

            this.logTitle.setText(this.texts.format("session.whisper.to", conversation.getTaggedName()));
            this.conversations.read(conversation.getCode());
        } else {
            source = List.of();
            lines  = List.of();

            this.logTitle.setText(this.texts.get("chat.title"));
        }

        if (!source.equals(this.shownSource)) {
            this.shownSource = source;
            this.log.getItems().setAll(lines);
            ComponentUtils.scrollToLast(this.log);
        }

        if (!members.equals(this.memberList.getItems())) {
            this.memberList.getItems().setAll(members);
        }

        String placeholder = channel == null && conversation == null ? "chat.channels.empty" : "chat.log.empty";

        this.log.setPlaceholder(ComponentUtils.createParagraph(this.texts.get(placeholder), "text-caption"));
        this.memberCount.setText(channel == null ? "" : Integer.toString(members.size()));
        this.leave.setVisible(channel != null);
        this.leave.setManaged(channel != null);
        this.input.setDisable(channel == null && conversation == null);
        this.send.setDisable(channel == null && conversation == null);
    }

    /**
     * Creates the log line of a channel entry; the session gives no whisper entries, its whispers show in their conversations.
     */
    private @NotNull LogLine createLine(@NotNull ChatEntryView entry) {
        FriendCode code = entry.getPlayer();
        String playerName = entry.getPlayerName() == null ? "" : entry.getPlayerName();

        return switch (entry.getKind()) {
            case SAY, WHISPER -> new LogLine(entry.getPrefix().strip(), code, playerName, entry.getText(), "text-body", null);
            case EMOTE -> new LogLine("", code, playerName, entry.getText(), "text-accent", null);
            case INFO -> new LogLine("", code, playerName, entry.getText(), "text-caption", null);
            case ERROR -> new LogLine("", code, playerName, entry.getText(), "text-danger", null);
        };
    }

    private @NotNull LogLine createLine(@NotNull WhisperView whisper) {
        String sender = whisper.isIncoming() ? whisper.getOtherTaggedName() : this.taggedName;
        FriendCode code = whisper.isIncoming() ? whisper.getOtherCode() : this.session.getFriendCode();
        String caption = switch (whisper.getState()) {
            case SENDING -> this.texts.get("session.whisper.sending");
            case NOT_DELIVERED -> this.texts.get("session.whisper.not.delivered");
            case RECEIVED, DELIVERED -> null;
        };

        return new LogLine(sender + ":", code, sender, whisper.getText(), "text-body", caption);
    }

    /**
     * Runs the input's text: says it or runs its command in the shown channel, or whispers it to the shown conversation's player.
     */
    private void submit() {
        String channel = this.shownChannel;
        Conversation conversation = this.shownConversation;
        String text = this.input.getText().strip();

        if (text.isEmpty()) {
            return;
        }

        if (channel != null) {
            this.session.submitChat(channel, text);
        } else if (conversation != null) {
            if (ChannelChat.getTextLength(text) > ChatChannel.MAXIMUM_TEXT_LENGTH) {
                this.showInputMessage(this.texts.get("session.whispers.too.long"));

                return;
            }

            this.session.whisper(conversation.getCode(), text);
        } else {
            return;
        }

        this.input.clear();
    }

    private void showInputMessage(@Nullable String text) {
        this.inputMessage.setText(text == null ? "" : text);
        this.inputMessage.setVisible(text != null);
        this.inputMessage.setManaged(text != null);
    }

    /**
     * Opens the Join dialog: the channels the session knows of with their member counts, and the name of the channel to join, which a click
     * on a listed channel fills in.
     */
    private void openJoin() {
        ModalDialog dialog = new ModalDialog(this.texts.get("chat.join.title"));
        ListView<ChannelView> known = ComponentUtils.createSunkenListView(ListItemSize.REGULAR);
        TextField name = new TextField();
        Button cancel = ComponentUtils.createButton(this.texts.get("session.cancel"), ButtonRole.SECONDARY);
        Button joinButton = ComponentUtils.createButton(this.texts.get("chat.join"), ButtonRole.PRIMARY);
        boolean full = this.session.getChannels().size() >= CommunitySession.MAXIMUM_CHANNELS;

        known.getItems().setAll(this.session.getKnownChannels());
        known.setCellFactory(view -> new ListItemCell<>(channel -> this.createChannelItem(channel, false)));
        known.setPlaceholder(ComponentUtils.createParagraph(this.texts.get("chat.channels.none"), "text-caption"));
        known.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                name.setText(current.getName());
            }
        });

        if (full) {
            joinButton.setDisable(true);
            dialog.addContent(ComponentUtils.createNotice(Tone.WARNING,
                                                          this.texts.format("chat.join.full", CommunitySession.MAXIMUM_CHANNELS)));
        } else {
            joinButton.disableProperty().bind(name.textProperty().isEmpty());
        }

        dialog.addGrowingContent(known);
        dialog.addContent(ComponentUtils.createField(this.texts.get("chat.join.name"), name));
        dialog.setButtons(cancel, joinButton);
        cancel.setOnAction(event -> this.shell.closeDialog(dialog));
        joinButton.setOnAction(event -> {
            String typed = name.getText().strip();

            if (!typed.isEmpty()) {
                // The joined channel is shown once the chat is in it.
                this.shownConversation = null;
                this.shell.closeDialog(dialog);
                this.session.joinChannel(typed);
            }
        });
        name.setOnAction(event -> joinButton.fire());

        this.shell.showDialog(dialog);
        name.requestFocus();
    }

    /**
     * Opens a player's menu at the pointer: the player with its state over Whisper, Add or Remove friend, Ignore or Unignore, Copy name and
     * Show status. The account's own entry only copies its name and shows its status.
     */
    private void openPlayerMenu(@NotNull FriendCode code, @NotNull String playerName, @NotNull ContextMenuEvent event) {
        FriendView shown = this.findPlayer(code);
        boolean self = code.equals(this.session.getFriendCode());
        boolean friend = this.session.getFriends().stream().anyMatch(candidate -> candidate.getCode().equals(code));
        boolean ignored = shown != null && shown.isIgnored();
        boolean full = this.session.getFriends().size() >= CommunitySession.MAXIMUM_FRIENDS;
        String status = shown == null ? this.texts.get("session.friend.offline") : this.playerTexts.describe(shown);
        Button whisper = ComponentUtils.createMenuButton(this.texts.get("session.friends.whisper"));
        Button friendButton = ComponentUtils.createMenuButton(this.texts.get(friend ? "chat.menu.remove.friend" : "chat.menu.add.friend"));
        Button ignore = ComponentUtils.createMenuButton(this.texts.get(ignored ? "session.friends.unignore" : "session.friends.ignore"));
        Button copy = ComponentUtils.createMenuButton(this.texts.get("chat.menu.copy.name"));
        Button statusButton = ComponentUtils.createMenuButton(this.texts.get("chat.menu.status"));
        VBox menu = ComponentUtils.createMenuPanel(this.createPlayerItem(code, playerName).createNode(), new Separator(), whisper, friendButton, ignore,
                                                   copy, statusButton);

        whisper.setDisable(self);
        whisper.setOnAction(action -> {
            this.shell.closeMenu();
            this.showWhispers(code, playerName);
        });
        friendButton.setDisable(self || !friend && full);
        friendButton.setOnAction(action -> {
            this.shell.closeMenu();

            if (friend) {
                this.session.removeFriend(code);
            } else {
                this.session.addFriend(code);
            }
        });
        ignore.setDisable(self);
        ignore.setOnAction(action -> {
            this.shell.closeMenu();
            this.session.setIgnored(code, !ignored);
        });
        copy.setOnAction(action -> {
            this.shell.closeMenu();
            this.actions.onCopy(playerName);
        });
        statusButton.setOnAction(action -> {
            this.shell.closeMenu();
            this.openStatus(code, playerName, status);
        });

        event.consume();
        this.shell.openMenu(menu, event.getSceneX(), event.getSceneY());
    }

    /**
     * Creates the list item of a channel: its name, its member count and, for a channel the chat is in, its unread lines.
     */
    private @NotNull ListItem createChannelItem(@NotNull ChannelView channel, boolean joined) {
        int unread = joined ? this.conversations.countUnreadLines(channel.getName()) : 0;
        ListItem item = new ListItem(channel.getName()).addCaption(this.texts.formatCount("chat.channel.members", channel.getMemberCount()));

        if (unread > 0) {
            item.setBadge(ComponentUtils.createBadge(Integer.toString(unread), Tone.ACCENT));
        }

        return item;
    }

    /**
     * Creates the list item of a whisper conversation: the player's item ({@link #createPlayerItem}) with the number of unread whispers.
     */
    private @NotNull ListItem createConversationItem(@NotNull Conversation conversation) {
        int unread = this.conversations.countUnread(conversation.getCode());
        ListItem item = this.createPlayerItem(conversation.getCode(), conversation.getTaggedName());

        if (unread > 0) {
            item.setBadge(ComponentUtils.createBadge(Integer.toString(unread), Tone.ACCENT));
        }

        return item;
    }

    /**
     * Creates the list item of a player by the player's latest view ({@link #findPlayer}), offline when the chat sees the player nowhere.
     */
    private @NotNull ListItem createPlayerItem(@NotNull FriendCode code, @NotNull String playerName) {
        FriendView shown = this.findPlayer(code);

        if (shown == null) {
            return new ListItem(playerName).setMarker(Tone.NEUTRAL).addCaption(this.texts.get("session.friend.offline"));
        }

        return this.playerTexts.createItem(shown);
    }

    /**
     * Finds the latest view of a player: a member of a channel the chat is in, else a friend.
     */
    private @Nullable FriendView findPlayer(@NotNull FriendCode code) {
        for (ChannelView channel : this.session.getChannels()) {
            for (FriendView member : channel.getMembers()) {
                if (member.getCode().equals(code)) {
                    return member;
                }
            }
        }

        return this.session.getFriends().stream().filter(candidate -> candidate.getCode().equals(code)).findFirst().orElse(null);
    }

    private void openStatus(@NotNull FriendCode code, @NotNull String playerName, @NotNull String status) {
        ModalDialog dialog = new ModalDialog(playerName);
        Button close = ComponentUtils.createButton(this.texts.get("session.close"), ButtonRole.SECONDARY);
        GridPane grid = ComponentUtils.createMetaGrid();

        ComponentUtils.addMetaRow(grid, this.texts.get("chat.status.state"), status);
        ComponentUtils.addMetaRow(grid, this.texts.get("chat.status.code"), code.getGroupedText());
        dialog.addContent(grid);
        dialog.setButtons(close, null);
        close.setOnAction(action -> this.shell.closeDialog(dialog));

        this.shell.showDialog(dialog);
    }
}
