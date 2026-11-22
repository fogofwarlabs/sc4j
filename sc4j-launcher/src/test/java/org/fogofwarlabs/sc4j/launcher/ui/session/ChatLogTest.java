package org.fogofwarlabs.sc4j.launcher.ui.session;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.input.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.fogofwarlabs.sc4j.launcher.session.FriendCode;
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

import java.util.*;

/**
 * Tests the chat log: a press and drag selects across lines and copies one line (name and text) per line, Shift extends, a double press
 * selects a word, a press below the lines ends the selection; web addresses are links with the hand pointer, a click on one asks before it
 * opens and a drag that starts on one selects; the selection stays on its lines when lines come in or the oldest drop and ends for another
 * conversation.
 */
final class ChatLogTest {
    private static final double SCENE_WIDTH = 900;
    private static final double SCENE_HEIGHT = 500;
    private static final @NotNull String KERRIGAN = "Kerrigan#a1b2c3";
    private static final @NotNull String RAYNOR = "Raynor#60bf07";
    private static final @NotNull String ARTANIS = "Artanis#0a0b0c";
    private static final @NotNull String ZERATUL = "Zeratul#d4e5f6";
    private static final @NotNull String ADDRESS = "https://example.com/maps?id=7";
    private static final @NotNull String GREETING = "gg all";
    private static final @NotNull String LINK_TEXT = "see " + ADDRESS + " for the map";
    private static final @NotNull String JOINED = ZERATUL + " joined";
    private static final @NotNull String WRAPPED_ADDRESS = "https://example.com/replays/2026/11/22/final-match-of-the-evening.rep";
    /**
     * A line wider than the log, with a link at its wrap.
     */
    private static final @NotNull String WRAPPED_TEXT = "the replay of the last game is up, it has the whole match from the first scout " +
                                                        "to the final push, get it at " + WRAPPED_ADDRESS;
    private static final @NotNull String OTHER_SCHEMES = "file:///C:/Windows javascript:alert(1) ftp://example.com";
    private static final @NotNull String DIALOG_SELECTOR = ".modal-scrim";
    private static final @NotNull String MENU_SELECTOR = ".menu-panel";

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @NotNull FriendCode createCode(int fill) {
        byte[] key = new byte[FriendCode.KEY_LENGTH];

        Arrays.fill(key, (byte) fill);

        return FriendCode.of(key);
    }

    private static @NotNull List<ChatLog.@NotNull Line> createLines() {
        return List.of(new ChatLog.Line(KERRIGAN + ":", createCode(1), KERRIGAN, GREETING, "text-body", null),
                       new ChatLog.Line(RAYNOR + ":", createCode(2), RAYNOR, LINK_TEXT, "text-body", null),
                       new ChatLog.Line("", null, "", JOINED, "text-caption", null),
                       new ChatLog.Line(ARTANIS + ":", createCode(3), ARTANIS, OTHER_SCHEMES, "text-body", null),
                       new ChatLog.Line(ZERATUL + ":", createCode(4), ZERATUL, WRAPPED_TEXT, "text-body", null));
    }

    /**
     * Records what the log hands out: the copied texts and the opened addresses.
     */
    private static final class RecordingActions implements ChatScreen.Actions {
        private final @NotNull List<@NotNull String> copied;
        private final @NotNull List<@NotNull String> opened;

        private RecordingActions() {
            this.copied = new ArrayList<>();
            this.opened = new ArrayList<>();
        }

        /**
         * Called when the user copies text: a player's name or the selected text of the log.
         *
         * @param text the text for the clipboard
         */
        @Override
        public void onCopy(@NotNull String text) {
            this.copied.add(text);
        }

        /**
         * Called when the user opens a link of the log after confirming it.
         *
         * @param address the {@code http} or {@code https} address
         */
        @Override
        public void onOpenWebsite(@NotNull String address) {
            this.opened.add(address);
        }
    }

    /**
     * Holds a shown log in a shell.
     */
    private static final class Shown {
        private final @NotNull LauncherShell shell;
        private final @NotNull ChatLog log;
        private final @NotNull RecordingActions actions;
        private final @NotNull List<@NotNull String> playerMenus;

        private Shown(@NotNull LauncherShell shell, @NotNull ChatLog log, @NotNull RecordingActions actions,
                      @NotNull List<@NotNull String> playerMenus) {
            this.shell       = shell;
            this.log         = log;
            this.actions     = actions;
            this.playerMenus = playerMenus;
        }
    }

    private static @NotNull Shown show(@NotNull LauncherTexts texts, @NotNull java.nio.file.Path directory) {
        LauncherShell shell = new LauncherShell(texts, InstallLayout.of(directory), 1, section -> {
        }, () -> {
        }, () -> {
        });
        Scene scene = new Scene(shell.getRoot(), SCENE_WIDTH, SCENE_HEIGHT);
        RecordingActions actions = new RecordingActions();
        List<String> playerMenus = new ArrayList<>();
        ChatLog log = new ChatLog(shell, texts, actions, (player, playerName, event) -> playerMenus.add(playerName));
        VBox content = new VBox(log.getView());

        VBox.setVgrow(log.getView(), Priority.ALWAYS);
        ThemeUtils.apply(scene, texts.getLanguage());
        shell.show(LauncherShell.Section.CHAT, content);
        log.show(createLines(), false);
        layOut(shell);

        return new Shown(shell, log, actions, playerMenus);
    }

    private static void layOut(@NotNull LauncherShell shell) {
        shell.getRoot().applyCss();
        shell.getRoot().layout();
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

    /**
     * Finds the shown text flow of a line part.
     */
    private static @NotNull TextFlow findFlow(@NotNull Shown shown, @NotNull String text) {
        for (Node node : shown.log.getView().lookupAll(".chat-line")) {
            if (node instanceof TextFlow flow && readFlow(flow).equals(text) && LauncherFxScenario.isShown(flow)) {
                return flow;
            }
        }

        throw Exceptions.newAssertionError("No line part {0}", text);
    }

    private static @NotNull ListCell<?> findCell(@NotNull Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof ListCell<?> cell) {
                return cell;
            }
        }

        throw Exceptions.newAssertionError("No cell above {0}", node);
    }

    /**
     * Finds the point of the scene at a character boundary of a line part, in the middle of its text line.
     */
    private static @NotNull Point2D findPoint(@NotNull TextFlow flow, int index) {
        Bounds caret = new Path(flow.getCaretShape(index, true)).getLayoutBounds();

        return flow.localToScene(caret.getMinX(), caret.getCenterY());
    }

    private static void fireMouse(@NotNull Node target, @NotNull EventType<MouseEvent> type, @NotNull Point2D point, int clickCount, boolean shift,
                                  boolean still) {
        boolean down = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
        MouseButton button = type == MouseEvent.MOUSE_MOVED ? MouseButton.NONE : MouseButton.PRIMARY;

        Event.fireEvent(target, new MouseEvent(type, point.getX(), point.getY(), point.getX(), point.getY(), button, clickCount, shift, false, false,
                                               false, down, false, false, false, false, still, null));
    }

    /**
     * Presses at a point, drags to another and releases there.
     */
    private static void drag(@NotNull Node pressed, @NotNull Point2D from, @NotNull Point2D to) {
        fireMouse(pressed, MouseEvent.MOUSE_PRESSED, from, 1, false, true);
        fireMouse(pressed, MouseEvent.MOUSE_DRAGGED, to, 1, false, false);
        fireMouse(pressed, MouseEvent.MOUSE_RELEASED, to, 1, false, false);
    }

    private static void click(@NotNull Node target, @NotNull Point2D point) {
        fireMouse(target, MouseEvent.MOUSE_PRESSED, point, 1, false, true);
        fireMouse(target, MouseEvent.MOUSE_RELEASED, point, 1, false, true);
    }

    /**
     * Presses a key with the platform's shortcut modifier in the log.
     */
    private static void pressShortcut(@NotNull Shown shown, @NotNull KeyCode key) {
        boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("mac");

        Event.fireEvent(shown.log.getView(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, !mac, false, mac));
    }

    private static @Nullable String copySelection(@NotNull Shown shown) {
        int before = shown.actions.copied.size();

        pressShortcut(shown, KeyCode.C);

        return shown.actions.copied.size() == before ? null : shown.actions.copied.getLast();
    }

    private static int countHighlights(@NotNull Shown shown) {
        int count = 0;

        for (Node node : shown.log.getView().lookupAll(".chat-selection")) {
            if (node instanceof Path path && !path.getElements().isEmpty() && LauncherFxScenario.isShown(path)) {
                count++;
            }
        }

        return count;
    }

    @Test
    void drag_selects_across_lines_and_copy_takes_one_line_per_line(@TempDir @NotNull java.nio.file.Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);

        LauncherFxScenario.run(() -> {
            Shown shown = show(texts, directory);
            TextFlow greeting = findFlow(shown, GREETING);
            TextFlow joined = findFlow(shown, JOINED);
            ListCell<?> pressed = findCell(greeting);

            // From "all" of the first line into the third: the lines between come whole with their names.
            drag(pressed, findPoint(greeting, GREETING.indexOf("all")), findPoint(joined, "Zeratul".length()));
            layOut(shown.shell);

            Assertions.assertEquals("all\n" + RAYNOR + ": " + LINK_TEXT + "\nZeratul", copySelection(shown));
            Assertions.assertEquals(4, countHighlights(shown), "the selected parts of the three lines are highlighted");

            // The log's menu copies too; on a name it is the player's menu.
            Point2D inText = findPoint(greeting, 1);

            Event.fireEvent(pressed, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, inText.getX(), inText.getY(), 0, 0, false, null));
            layOut(shown.shell);
            LauncherFxScenario.findButton(shown.shell.getRoot().lookup(MENU_SELECTOR), texts.get("chat.log.copy")).fire();

            Assertions.assertEquals("all\n" + RAYNOR + ": " + LINK_TEXT + "\nZeratul", shown.actions.copied.getLast());
            Assertions.assertNull(shown.shell.getRoot().lookup(MENU_SELECTOR), "an entry closes the menu");

            Point2D onName = findPoint(findFlow(shown, KERRIGAN + ":"), 2);

            Event.fireEvent(pressed, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, onName.getX(), onName.getY(), 0, 0, false, null));

            Assertions.assertEquals(List.of(KERRIGAN), shown.playerMenus);
            Assertions.assertNull(shown.shell.getRoot().lookup(MENU_SELECTOR));

            return null;
        });
    }

    @Test
    void shift_extends_a_double_press_selects_a_word_and_a_press_below_the_lines_ends_the_selection(@TempDir @NotNull java.nio.file.Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);

        LauncherFxScenario.run(() -> {
            Shown shown = show(texts, directory);
            TextFlow greeting = findFlow(shown, GREETING);
            TextFlow link = findFlow(shown, LINK_TEXT);
            TextFlow name = findFlow(shown, RAYNOR + ":");

            // A press on the first line, then Shift with a press in the second line's name.
            click(findCell(greeting), findPoint(greeting, 0));
            fireMouse(findCell(name), MouseEvent.MOUSE_PRESSED, findPoint(name, "Raynor".length()), 1, true, true);
            fireMouse(findCell(name), MouseEvent.MOUSE_RELEASED, findPoint(name, "Raynor".length()), 1, true, true);

            Assertions.assertEquals(GREETING + "\nRaynor", copySelection(shown));

            // A double press selects the word under it.
            fireMouse(findCell(link), MouseEvent.MOUSE_PRESSED, findPoint(link, LINK_TEXT.indexOf("example") + 2), 2, false, true);
            fireMouse(findCell(link), MouseEvent.MOUSE_RELEASED, findPoint(link, LINK_TEXT.indexOf("example") + 2), 2, false, true);

            Assertions.assertEquals("example", copySelection(shown));
            Assertions.assertNull(shown.shell.getRoot().lookup(DIALOG_SELECTOR), "a double press opens no link");

            // Select all, then a press below the lines ends the selection.
            pressShortcut(shown, KeyCode.A);

            Assertions.assertEquals(String.join("\n", createLines().stream().map(ChatLog.Line::getCopiedText).toList()), copySelection(shown));

            ListCell<?> below = null;

            for (Node node : shown.log.getView().lookupAll(".list-cell")) {
                if (node instanceof ListCell<?> cell && cell.isEmpty() && LauncherFxScenario.isShown(cell)) {
                    below = cell;
                }
            }

            Assertions.assertNotNull(below, "the log has room below its lines");

            click(below, below.localToScene(below.getWidth() / 2, below.getHeight() / 2));

            Assertions.assertNull(copySelection(shown), "nothing is selected");
            Assertions.assertEquals(0, countHighlights(shown));

            return null;
        });
    }

    @Test
    void click_on_a_link_asks_before_it_opens_and_a_drag_from_a_link_selects(@TempDir @NotNull java.nio.file.Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);

        LauncherFxScenario.run(() -> {
            Shown shown = show(texts, directory);
            TextFlow link = findFlow(shown, LINK_TEXT);
            ListCell<?> cell = findCell(link);
            Point2D onLink = findPoint(link, LINK_TEXT.indexOf(ADDRESS) + 3);
            Point2D besideLink = findPoint(link, 1);
            List<String> linkRuns = new ArrayList<>();

            // Only the http and https addresses are links.
            for (Node node : shown.log.getView().lookupAll(".chat-link")) {
                if (node instanceof Text run && LauncherFxScenario.isShown(run)) {
                    linkRuns.add(run.getText());
                }
            }

            Assertions.assertEquals(Set.of(ADDRESS, WRAPPED_ADDRESS), Set.copyOf(linkRuns));

            // The pointer is a hand over a link and a text cursor over other text.
            fireMouse(cell, MouseEvent.MOUSE_MOVED, onLink, 0, false, true);

            Assertions.assertEquals(Cursor.HAND, cell.getCursor());

            fireMouse(cell, MouseEvent.MOUSE_MOVED, besideLink, 0, false, true);

            Assertions.assertEquals(Cursor.TEXT, cell.getCursor());

            // A click asks; Cancel opens nothing.
            click(cell, onLink);
            layOut(shown.shell);

            Parent dialog = (Parent) shown.shell.getRoot().lookup(DIALOG_SELECTOR);

            Assertions.assertNotNull(dialog, "a click on a link asks");
            Assertions.assertTrue(dialog.lookupAll(".text-mono").stream().anyMatch(node -> node instanceof Text text &&
                                                                                           text.getText().equals(ADDRESS)));
            LauncherFxScenario.findButton(dialog, texts.get("link.cancel")).fire();

            Assertions.assertNull(shown.shell.getRoot().lookup(DIALOG_SELECTOR));
            Assertions.assertTrue(shown.actions.opened.isEmpty(), "Cancel opens nothing");

            // Open opens the address.
            click(cell, onLink);
            layOut(shown.shell);
            LauncherFxScenario.findButton(shown.shell.getRoot(), texts.get("link.open")).fire();

            Assertions.assertEquals(List.of(ADDRESS), shown.actions.opened);

            // A drag that starts on a link selects and opens nothing.
            drag(cell, onLink, findPoint(link, LINK_TEXT.length()));
            layOut(shown.shell);

            Assertions.assertNull(shown.shell.getRoot().lookup(DIALOG_SELECTOR), "a drag opens no link");
            Assertions.assertEquals(List.of(ADDRESS), shown.actions.opened);
            Assertions.assertEquals(LINK_TEXT.substring(LINK_TEXT.indexOf(ADDRESS) + 3), copySelection(shown));

            // A click on other text opens nothing.
            click(cell, besideLink);

            Assertions.assertNull(shown.shell.getRoot().lookup(DIALOG_SELECTOR));

            return null;
        });
    }

    @Test
    void selection_stays_on_its_lines_when_lines_come_in_or_the_oldest_drop(@TempDir @NotNull java.nio.file.Path directory) throws Exception {
        LauncherTexts texts = LauncherTexts.of(UiLanguage.ENGLISH);

        LauncherFxScenario.run(() -> {
            Shown shown = show(texts, directory);
            TextFlow link = findFlow(shown, LINK_TEXT);
            TextFlow joined = findFlow(shown, JOINED);
            String selected = "for the map\nZeratul";
            List<ChatLog.Line> lines = new ArrayList<>(createLines());

            drag(findCell(link), findPoint(link, LINK_TEXT.indexOf("for")), findPoint(joined, "Zeratul".length()));

            Assertions.assertEquals(selected, copySelection(shown));

            // A line comes in.
            lines.add(new ChatLog.Line(KERRIGAN + ":", createCode(1), KERRIGAN, "rematch?", "text-body", null));
            shown.log.show(lines, true);
            layOut(shown.shell);

            Assertions.assertEquals(selected, copySelection(shown));

            // The oldest line drops.
            lines.removeFirst();
            shown.log.show(lines, true);
            layOut(shown.shell);

            Assertions.assertEquals(selected, copySelection(shown));
            Assertions.assertEquals(2, countHighlights(shown), "the highlight follows its lines");

            // Another conversation ends the selection.
            shown.log.show(List.of(new ChatLog.Line(RAYNOR + ":", createCode(2), RAYNOR, "hi", "text-body", null)), false);
            layOut(shown.shell);

            Assertions.assertNull(copySelection(shown));
            Assertions.assertEquals(0, countHighlights(shown));

            return null;
        });
    }
}
