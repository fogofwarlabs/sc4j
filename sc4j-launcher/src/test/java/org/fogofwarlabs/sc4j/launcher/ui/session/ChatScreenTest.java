package org.fogofwarlabs.sc4j.launcher.ui.session;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.client.firewall.FirewallState;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkConnectivity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherFxScenario;
import org.fogofwarlabs.sc4j.launcher.ui.LauncherShell;
import org.fogofwarlabs.sc4j.launcher.ui.ThemeUtils;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests the Chat screen: the channels with their unread lines, the whisper conversations with their unread whispers, the channels,
 * conversations and members in compact rows, the shown channel's log and members, lines and whispers sent from the input, leaving and joining
 * a channel, a channel the chat joins shown at once, a whisper conversation opened from elsewhere shown with the input focused and kept while
 * the chat joins the shared channel, the player menu on a member and on a name in the log with its commands, the input footer with the same
 * room around its row and between the input and Send, the header controls with the same room on their right as above and below, and the
 * notice of a session without a connection.
 */
final class ChatScreenTest {
    private static final double SCENE_WIDTH = 1280;
    private static final double SCENE_HEIGHT = 800;
    private static final @NotNull SelfView CONNECTED = new SelfView(SessionState.CONNECTED, SelfStatus.ONLINE, "", 1, null, LinkConnectivity.UNKNOWN,
                                                                    FirewallState.NOT_NEEDED);
    private static final @NotNull String SHARED = "SC4J";
    private static final @NotNull String LADDER = "Ladder";
    private static final @NotNull String KOREA = "Korea";
    private static final @NotNull String GAME_MOD_ID = "test-game";
    private static final @NotNull String TOO_LONG_WHISPER = "x".repeat(300);
    private static final @NotNull String DIALOG = ".modal-scrim";
    private static final @NotNull String MENU_SELECTOR = ".menu-panel";
    private static final @NotNull String NAME_SELECTOR = ".text-heading";
    private static final @NotNull String INPUT_FOOTER_SELECTOR = ".input-footer";
    private static final @NotNull String INPUT_ROW_SELECTOR = ".input-row";
    /**
     * The lists of the screen by their place from left to right.
     */
    private static final int CHANNEL_LIST = 0;
    private static final int CONVERSATION_LIST = 1;
    private static final int LOG = 2;
    private static final int MEMBER_LIST = 3;
    private static final @NotNull String COMPACT_LIST_STYLE_CLASS = "compact";
    private static final double COMPACT_CELL_PADDING = 6;
    private static final double TOLERANCE = 0.5;
    private static final @NotNull FriendView RAYNOR = createPlayer(1, "Raynor#60bf07", FriendState.IN_LAUNCHER, null, "");
    private static final @NotNull FriendView KERRIGAN = createPlayer(2, "Kerrigan#a1b2c3", FriendState.IN_GAME, GAME_MOD_ID, "");
    private static final @NotNull FriendView ZERATUL = createPlayer(3, "Zeratul#d4e5f6", FriendState.AWAY, null, "brb");
    private static final @NotNull FriendView ARTANIS = createPlayer(4, "Artanis#0a0b0c", FriendState.IN_LAUNCHER, null, "");
    private static final @NotNull List<@NotNull ChannelView> CHANNELS = List.of(new ChannelView(SHARED, 3, List.of(RAYNOR, KERRIGAN, ZERATUL)),
                                                                                new ChannelView(LADDER, 2, List.of(RAYNOR, ARTANIS)));
    private static final @NotNull List<@NotNull ChannelView> KNOWN_CHANNELS = List.of(new ChannelView(KOREA, 12, List.of()),
                                                                                      new ChannelView(SHARED, 3, List.of()),
                                                                                      new ChannelView(LADDER, 2, List.of()));
    private static final @NotNull List<@NotNull ChatEntryView> ENTRIES = List.of(
            new ChatEntryView(ChatEntryKind.INFO, "", "Channel: SC4J", SHARED, null, null),
            new ChatEntryView(ChatEntryKind.SAY, "Kerrigan#a1b2c3: ", "gg all", SHARED, KERRIGAN.getCode(), KERRIGAN.getTaggedName()),
            new ChatEntryView(ChatEntryKind.SAY, "Raynor#60bf07: ", "hi", SHARED, RAYNOR.getCode(), RAYNOR.getTaggedName()),
            new ChatEntryView(ChatEntryKind.INFO, "", "Channel: Ladder", LADDER, null, null),
            new ChatEntryView(ChatEntryKind.SAY, "Artanis#0a0b0c: ", "anyone 1v1?", LADDER, ARTANIS.getCode(), ARTANIS.getTaggedName()),
            new ChatEntryView(ChatEntryKind.EMOTE, "", "Artanis#0a0b0c waves", LADDER, ARTANIS.getCode(), ARTANIS.getTaggedName()),
            new ChatEntryView(ChatEntryKind.INFO, "", "Zeratul#d4e5f6 joined", SHARED, ZERATUL.getCode(), ZERATUL.getTaggedName())
    );
    private static final @NotNull List<@NotNull WhisperView> WHISPERS = List.of(
            new WhisperView(KERRIGAN.getCode(), KERRIGAN.getTaggedName(), "Ready?", 1_000L, WhisperState.RECEIVED)
    );

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @NotNull FriendView createPlayer(int fill, @NotNull String taggedName, @NotNull FriendState state, @Nullable String modId,
                                                    @NotNull String awayText) {
        byte[] key = new byte[FriendCode.KEY_LENGTH];

        Arrays.fill(key, (byte) fill);

        return new FriendView(FriendCode.of(key), taggedName, state, modId, awayText, 1, false);
    }

    private static <T extends Node> @NotNull List<T> find(@NotNull Parent root, @NotNull Class<T> type) {
        List<T> found = new ArrayList<>();

        for (Node node : root.lookupAll("*")) {
            if (type.isInstance(node)) {
                found.add(type.cast(node));
            }
        }

        return found;
    }

    private static boolean showsText(@NotNull Parent root, @NotNull String text) {
        return find(root, Label.class).stream().anyMatch(label -> LauncherFxScenario.isShown(label) && label.getText().contains(text)) ||
               find(root, TextFlow.class).stream().anyMatch(flow -> LauncherFxScenario.isShown(flow) && readFlow(flow).contains(text));
    }

    private static @NotNull String readFlow(@NotNull TextFlow flow) {
        StringBuilder text = new StringBuilder();

        for (Node child : flow.getChildren()) {
            if (child instanceof Text run) {
                text.append(run.getText());
            }
        }

        return text.toString();
    }

    private static void lay(@NotNull LauncherShell shell) {
        shell.getRoot().applyCss();
        shell.getRoot().layout();
    }

    private static @NotNull <T extends Node> T lookup(@NotNull Parent root, @NotNull String selector, @NotNull Class<T> type) {
        Node found = root.lookup(selector);

        Assertions.assertNotNull(found, selector);

        return type.cast(found);
    }

    /**
     * Checks that the input footer has the same room above, below, left and right of its input row and between the input and Send.
     */
    private static void assertInputFooterEven(@NotNull Parent root, @NotNull LauncherTexts texts) {
        Node footer = lookup(root, INPUT_FOOTER_SELECTOR, Node.class);
        Node row = lookup(root, INPUT_ROW_SELECTOR, Node.class);
        Bounds footerBounds = footer.localToScene(footer.getLayoutBounds());
        Bounds rowBounds = row.localToScene(row.getLayoutBounds());
        Bounds input = getInput(root).localToScene(getInput(root).getLayoutBounds());
        Button send = LauncherFxScenario.findButton(root, texts.get("session.whispers.send"));
        Bounds sendBounds = send.localToScene(send.getLayoutBounds());
        double room = rowBounds.getMinY() - footerBounds.getMinY();

        Assertions.assertTrue(room > 0, "the footer has room around its row");
        Assertions.assertEquals(room, footerBounds.getMaxY() - rowBounds.getMaxY(), TOLERANCE, "room below the row");
        Assertions.assertEquals(room, rowBounds.getMinX() - footerBounds.getMinX(), TOLERANCE, "room left of the row");
        Assertions.assertEquals(room, footerBounds.getMaxX() - rowBounds.getMaxX(), TOLERANCE, "room right of the row");
        Assertions.assertEquals(room, sendBounds.getMinX() - input.getMaxX(), TOLERANCE, "gap between the input and Send");
    }

    private static @NotNull ListView<?> getList(@NotNull LauncherShell shell, int list) {
        Assertions.assertTrue(find(shell.getRoot(), ListView.class).size() > list, "the screen shows its lists");

        return find(shell.getRoot(), ListView.class).get(list);
    }

    private static @NotNull TextField getInput(@NotNull Parent root) {
        List<TextField> fields = find(root, TextField.class);

        Assertions.assertFalse(fields.isEmpty(), "the screen has an input");

        return fields.getFirst();
    }

    /**
     * Lists the rows of a list from the top: each row's title, and after it the unread count if any.
     */
    private static @NotNull List<@NotNull String> listRows(@NotNull LauncherShell shell, int shown) {
        ListView<?> list = getList(shell, shown);
        String[] rows = new String[list.getItems().size()];

        for (Node node : list.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty() && cell.isVisible() && cell.getIndex() >= 0) {
                String title = ((Label) cell.lookup(".list-title")).getText();
                Node badge = cell.lookup(".badge");

                rows[cell.getIndex()] = badge == null ? title : title + " " + ((Label) badge).getText();
            }
        }

        return Arrays.asList(rows);
    }

    private static void select(@NotNull LauncherShell shell, int list, int index) {
        getList(shell, list).getSelectionModel().select(index);
        lay(shell);
    }

    private static @NotNull Node findCell(@NotNull LauncherShell shell, int list, @NotNull Object item) {
        for (Node node : getList(shell, list).lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && item.equals(cell.getItem())) {
                return cell;
            }
        }

        throw Exceptions.newAssertionError("No cell of {0}", item);
    }

    private static @NotNull Node findName(@NotNull LauncherShell shell, @NotNull String prefix) {
        for (Node node : getList(shell, LOG).lookupAll(NAME_SELECTOR)) {
            if (node instanceof Text name && name.getText().equals(prefix) && LauncherFxScenario.isShown(name)) {
                return name;
            }
        }

        throw Exceptions.newAssertionError("No name {0}", prefix);
    }

    /**
     * Right-clicks a node: fires the context menu request at its centre.
     */
    private static void requestMenu(@NotNull LauncherShell shell, @NotNull Node node) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());

        Event.fireEvent(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, bounds.getCenterX(), bounds.getCenterY(), 0, 0, false,
                                                   null));
        lay(shell);
    }

    private static @NotNull Parent getMenu(@NotNull LauncherShell shell) {
        return lookup(shell.getRoot(), MENU_SELECTOR, Parent.class);
    }

    private static @NotNull List<@NotNull String> listMenuEntries(@NotNull LauncherShell shell) {
        return find(getMenu(shell), Button.class).stream().map(Button::getText).toList();
    }

    private static void fireMenuEntry(@NotNull LauncherShell shell, @NotNull String entry) {
        LauncherFxScenario.findButton(getMenu(shell), entry).fire();
        lay(shell);
    }

    private static void type(@NotNull LauncherShell shell, @NotNull String text) {
        getInput(shell.getRoot()).setText(text);
        lay(shell);
    }

    private static @NotNull LauncherShell createShell(@NotNull LauncherTexts texts, @NotNull Path directory,
                                                      @NotNull RecordingLauncherSession session, @NotNull List<@NotNull String> copied) {
        return createShell(texts, directory, session, copied, new AtomicReference<>());
    }

    /**
     * Builds the shell of a logged-in account on its Chat screen, the way the window does, and hands out the screen.
     */
    private static @NotNull LauncherShell createShell(@NotNull LauncherTexts texts, @NotNull Path directory,
                                                      @NotNull RecordingLauncherSession session, @NotNull List<@NotNull String> copied,
                                                      @NotNull AtomicReference<ChatScreen> created) {
        LauncherShell shell = new LauncherShell(texts, InstallLayout.of(directory), 1, section -> {
        }, () -> {
        }, () -> {
        });
        Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);
        ChatScreen.Actions actions = new ChatScreen.Actions() {
            /**
             * Called when the user copies text: a player's name or the selected text of the log.
             *
             * @param text the text for the clipboard
             */
            @Override
            public void onCopy(@NotNull String text) {
                copied.add(text);
            }

            /**
             * Called when the user opens a link of the log after confirming it.
             *
             * @param address the {@code http} or {@code https} address
             */
            @Override
            public void onOpenWebsite(@NotNull String address) {
                Assertions.fail("no link is opened");
            }
        };
        ChatScreen screen = new ChatScreen(shell, texts, RAYNOR.getTaggedName(), session, new Conversations(session), modId -> modId, actions);

        created.set(screen);

        ThemeUtils.apply(scene, texts.getLanguage());
        session.setOnChanged(screen::update);
        shell.show(LauncherShell.Section.CHAT, screen.getRoot());
        screen.update();
        lay(shell);

        return shell;
    }

    @Test
    void chat_screen_shows_channels_whispers_members_and_unread_counts_and_sends_from_the_input(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        RecordingLauncherSession session = new RecordingLauncherSession(RAYNOR.getCode(), CONNECTED);

        session.setFriends(List.of(KERRIGAN));
        session.setWhispers(WHISPERS);
        session.setChat(CHANNELS, KNOWN_CHANNELS, ENTRIES);

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory, session, new ArrayList<>());
            Parent root = shell.getRoot();

            // The screen only shows the chat: it gives no command; the first channel is shown, read, and the other channel and the whisper
            // conversation count their unread lines.
            Assertions.assertTrue(session.getCommands().isEmpty(), "showing the chat starts nothing");
            Assertions.assertEquals(List.of(SHARED, LADDER + " 2"), listRows(shell, CHANNEL_LIST));
            Assertions.assertEquals(List.of(KERRIGAN.getTaggedName() + " 1"), listRows(shell, CONVERSATION_LIST));
            Assertions.assertEquals(List.of(RAYNOR.getTaggedName(), KERRIGAN.getTaggedName(), ZERATUL.getTaggedName()),
                                    listRows(shell, MEMBER_LIST));
            Assertions.assertTrue(showsText(root, "gg all"));
            Assertions.assertTrue(showsText(root, "Zeratul#d4e5f6 joined"));
            Assertions.assertFalse(showsText(root, "anyone 1v1?"), "a channel's log shows its own lines only");
            Assertions.assertTrue(showsText(root, "brb"), "a member shows its away text");

            // The channels, the conversations and the members are compact lists; the log is not.
            for (int list : List.of(CHANNEL_LIST, CONVERSATION_LIST, MEMBER_LIST)) {
                Assertions.assertTrue(getList(shell, list).getStyleClass().contains(COMPACT_LIST_STYLE_CLASS), "list " + list);
            }

            Assertions.assertFalse(getList(shell, LOG).getStyleClass().contains(COMPACT_LIST_STYLE_CLASS));
            Assertions.assertEquals(COMPACT_CELL_PADDING, ((ListCell<?>) findCell(shell, MEMBER_LIST, ZERATUL)).getPadding().getTop(),
                                    TOLERANCE);
            Assertions.assertEquals(3, LauncherFxScenario.assertHeaderInsets(root), "Join, Leave and the member count are checked");
            Assertions.assertEquals(1, LauncherFxScenario.assertFooterInsets(root), "the input footer is checked");

            // The input row's footer: the same room on every side of the row and between the input and Send.
            assertInputFooterEven(root, texts);

            // A line typed in the channel goes to the session for that channel.
            type(shell, "hello");
            LauncherFxScenario.findButton(root, texts.get("session.whispers.send")).fire();

            Assertions.assertEquals("say " + SHARED + ":hello", session.getCommands().getLast());
            Assertions.assertTrue(getInput(root).getText().isEmpty(), "a sent line leaves the input");

            // The other channel: its lines, read once shown, its members, and Leave.
            select(shell, CHANNEL_LIST, 1);

            Assertions.assertEquals(List.of(SHARED, LADDER), listRows(shell, CHANNEL_LIST));
            Assertions.assertTrue(showsText(root, "anyone 1v1?"));
            Assertions.assertTrue(showsText(root, "Artanis#0a0b0c waves"));
            Assertions.assertFalse(showsText(root, "gg all"));
            Assertions.assertEquals(List.of(RAYNOR.getTaggedName(), ARTANIS.getTaggedName()), listRows(shell, MEMBER_LIST));

            LauncherFxScenario.findButton(root, texts.get("chat.leave")).fire();

            Assertions.assertEquals("leave " + LADDER, session.getCommands().getLast());

            // A line in a channel that is not shown counts as unread there; the account's own line does not.
            List<ChatEntryView> entries = new ArrayList<>(ENTRIES);

            entries.add(new ChatEntryView(ChatEntryKind.SAY, "Zeratul#d4e5f6: ", "en taro", SHARED, ZERATUL.getCode(), ZERATUL.getTaggedName()));
            entries.add(new ChatEntryView(ChatEntryKind.SAY, "Raynor#60bf07: ", "hello", SHARED, RAYNOR.getCode(), RAYNOR.getTaggedName()));
            session.setChat(CHANNELS, KNOWN_CHANNELS, entries);
            lay(shell);

            Assertions.assertEquals(List.of(SHARED + " 1", LADDER), listRows(shell, CHANNEL_LIST));

            // The whisper conversation, the one of the account menu: its whispers, read once shown; the input whispers to its player.
            select(shell, CONVERSATION_LIST, 0);

            Assertions.assertEquals(List.of(KERRIGAN.getTaggedName()), listRows(shell, CONVERSATION_LIST));
            Assertions.assertTrue(showsText(root, "Ready?"));
            Assertions.assertTrue(showsText(root, texts.format("session.whisper.to", KERRIGAN.getTaggedName())));
            Assertions.assertTrue(getList(shell, CHANNEL_LIST).getSelectionModel().isEmpty(), "one conversation is shown at a time");

            type(shell, TOO_LONG_WHISPER);
            LauncherFxScenario.findButton(root, texts.get("session.whispers.send")).fire();
            lay(shell);

            Assertions.assertTrue(showsText(root, texts.get("session.whispers.too.long")));

            type(shell, "on my way");
            LauncherFxScenario.findButton(root, texts.get("session.whispers.send")).fire();

            Assertions.assertEquals("whisper " + KERRIGAN.getCode().getText() + ":on my way", session.getCommands().getLast());

            // Join: the known channels, a click fills in the name, Join joins it; the joined channel is shown once the session has it.
            LauncherFxScenario.findButton(root, texts.get("chat.join")).fire();
            lay(shell);

            Parent dialog = lookup(root, DIALOG, Parent.class);

            find(dialog, ListView.class).getFirst().getSelectionModel().select(0);
            lay(shell);

            Assertions.assertEquals(KOREA, getInput(dialog).getText());
            LauncherFxScenario.findButton(dialog, texts.get("chat.join")).fire();
            lay(shell);

            Assertions.assertEquals("join " + KOREA, session.getCommands().getLast());
            Assertions.assertNull(root.lookup(DIALOG));

            List<ChannelView> joined = new ArrayList<>(CHANNELS);

            joined.add(new ChannelView(KOREA, 1, List.of(RAYNOR)));
            entries.add(new ChatEntryView(ChatEntryKind.INFO, "", "Channel: Korea", KOREA, null, null));
            session.setChat(joined, KNOWN_CHANNELS, entries);
            lay(shell);

            Assertions.assertTrue(getList(shell, CHANNEL_LIST).getSelectionModel().isSelected(2), "the joined channel is shown");
            Assertions.assertTrue(showsText(root, "Channel: Korea"));

            return null;
        });
    }

    @Test
    void whisper_opened_from_elsewhere_stays_shown_with_the_input_focused(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        RecordingLauncherSession session = new RecordingLauncherSession(RAYNOR.getCode(), CONNECTED);

        session.setFriends(List.of(KERRIGAN));

        LauncherFxScenario.run(() -> {
            AtomicReference<ChatScreen> screen = new AtomicReference<>();
            LauncherShell shell = createShell(texts, directory, session, new ArrayList<>(), screen);
            Parent root = shell.getRoot();

            // The chat is opened but not in a channel yet; the friend's conversation is shown, new and empty, with the input focused.
            screen.get().showWhispers(KERRIGAN.getCode(), KERRIGAN.getTaggedName());
            lay(shell);

            Assertions.assertEquals(List.of(KERRIGAN.getTaggedName()), listRows(shell, CONVERSATION_LIST));
            Assertions.assertTrue(getList(shell, CONVERSATION_LIST).getSelectionModel().isSelected(0));
            Assertions.assertTrue(showsText(root, texts.format("session.whisper.to", KERRIGAN.getTaggedName())));
            Assertions.assertSame(getInput(root), root.getScene().getFocusOwner(), "the input is focused");

            // The chat joins the shared channel: it is listed, and the conversation stays shown.
            session.setChat(CHANNELS, KNOWN_CHANNELS, ENTRIES);
            lay(shell);

            Assertions.assertEquals(List.of(SHARED + " 1", LADDER + " 2"), listRows(shell, CHANNEL_LIST));
            Assertions.assertTrue(getList(shell, CHANNEL_LIST).getSelectionModel().isEmpty());
            Assertions.assertTrue(getList(shell, CONVERSATION_LIST).getSelectionModel().isSelected(0));
            Assertions.assertTrue(showsText(root, texts.format("session.whisper.to", KERRIGAN.getTaggedName())));

            type(shell, "hi");
            LauncherFxScenario.findButton(root, texts.get("session.whispers.send")).fire();

            Assertions.assertEquals("whisper " + KERRIGAN.getCode().getText() + ":hi", session.getCommands().getLast());

            return null;
        });
    }

    @Test
    void player_menu_on_a_member_or_a_name_in_the_log_runs_the_account_menu_commands(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        RecordingLauncherSession session = new RecordingLauncherSession(RAYNOR.getCode(), CONNECTED);
        List<String> copied = new ArrayList<>();

        session.setFriends(List.of(KERRIGAN));
        session.setChat(CHANNELS, KNOWN_CHANNELS, ENTRIES);

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory, session, copied);
            Parent root = shell.getRoot();

            session.getCommands().clear();

            // A member who is no friend: Whisper, Add friend, Ignore, Copy name and Show status.
            requestMenu(shell, findCell(shell, MEMBER_LIST, ZERATUL));

            Assertions.assertEquals(List.of(texts.get("session.friends.whisper"), texts.get("chat.menu.add.friend"),
                                            texts.get("session.friends.ignore"), texts.get("chat.menu.copy.name"), texts.get("chat.menu.status")),
                                    listMenuEntries(shell));
            Assertions.assertTrue(showsText(getMenu(shell), ZERATUL.getTaggedName()));
            fireMenuEntry(shell, texts.get("chat.menu.add.friend"));

            Assertions.assertNull(root.lookup(MENU_SELECTOR), "an entry closes the menu");

            requestMenu(shell, findCell(shell, MEMBER_LIST, ZERATUL));
            fireMenuEntry(shell, texts.get("session.friends.ignore"));
            requestMenu(shell, findCell(shell, MEMBER_LIST, ZERATUL));
            fireMenuEntry(shell, texts.get("chat.menu.copy.name"));

            // A friend: Remove friend.
            requestMenu(shell, findCell(shell, MEMBER_LIST, KERRIGAN));
            fireMenuEntry(shell, texts.get("chat.menu.remove.friend"));

            Assertions.assertEquals(List.of("add " + ZERATUL.getCode().getText(), "ignore " + ZERATUL.getCode().getText(),
                                            "remove " + KERRIGAN.getCode().getText()), session.getCommands());
            Assertions.assertEquals(List.of(ZERATUL.getTaggedName()), copied);

            // Show status: the state line and the friend code.
            requestMenu(shell, findCell(shell, MEMBER_LIST, ZERATUL));
            fireMenuEntry(shell, texts.get("chat.menu.status"));

            Parent status = lookup(root, DIALOG, Parent.class);

            Assertions.assertTrue(showsText(status, texts.format("session.status.away.line", "brb")));
            Assertions.assertTrue(showsText(status, ZERATUL.getCode().getGroupedText()));
            LauncherFxScenario.findButton(status, texts.get("session.close")).fire();

            // The account itself: only Copy name and Show status.
            requestMenu(shell, findCell(shell, MEMBER_LIST, RAYNOR));

            Assertions.assertTrue(LauncherFxScenario.findButton(getMenu(shell), texts.get("session.friends.whisper")).isDisabled());
            Assertions.assertTrue(LauncherFxScenario.findButton(getMenu(shell), texts.get("chat.menu.add.friend")).isDisabled());
            Assertions.assertTrue(LauncherFxScenario.findButton(getMenu(shell), texts.get("session.friends.ignore")).isDisabled());
            Assertions.assertFalse(LauncherFxScenario.findButton(getMenu(shell), texts.get("chat.menu.copy.name")).isDisabled());

            shell.closeMenu();

            // A name in the log opens the same menu; Whisper opens the player's conversation, new and empty.
            requestMenu(shell, findName(shell, KERRIGAN.getTaggedName() + ":"));

            Assertions.assertTrue(showsText(getMenu(shell), KERRIGAN.getTaggedName()));
            Assertions.assertTrue(listMenuEntries(shell).contains(texts.get("chat.menu.remove.friend")));

            fireMenuEntry(shell, texts.get("session.friends.whisper"));

            Assertions.assertEquals(List.of(KERRIGAN.getTaggedName()), listRows(shell, CONVERSATION_LIST));
            Assertions.assertTrue(getList(shell, CONVERSATION_LIST).getSelectionModel().isSelected(0));
            Assertions.assertTrue(showsText(root, texts.format("session.whisper.to", KERRIGAN.getTaggedName())));
            Assertions.assertTrue(showsText(root, texts.get("chat.log.empty")));

            return null;
        });
    }

    @Test
    void chat_screen_says_why_the_session_is_offline(@TempDir @NotNull Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);
        RecordingLauncherSession session = new RecordingLauncherSession(RAYNOR.getCode(), new SelfView(SessionState.OFFLINE, SelfStatus.ONLINE, "", 0,
                                                                                                       SessionFailure.SIGNED_OUT,
                                                                                                       LinkConnectivity.UNKNOWN,
                                                                                                       FirewallState.NOT_NEEDED));

        LauncherFxScenario.run(() -> {
            LauncherShell shell = createShell(texts, directory, session, new ArrayList<>());

            Assertions.assertTrue(showsText(shell.getRoot(), texts.get("session.not.connected")));
            Assertions.assertTrue(showsText(shell.getRoot(), texts.get("session.failure.signed_out")));
            Assertions.assertTrue(find(shell.getRoot(), ListView.class).isEmpty());
            Assertions.assertTrue(session.getCommands().isEmpty());

            return null;
        });
    }
}
