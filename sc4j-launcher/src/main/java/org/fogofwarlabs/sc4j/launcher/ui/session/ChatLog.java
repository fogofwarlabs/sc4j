package org.fogofwarlabs.sc4j.launcher.ui.session;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.*;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.fogofwarlabs.sc4j.base.text.LogSelectionModel;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.session.FriendCode;
import org.fogofwarlabs.sc4j.launcher.ui.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Shows the log of a chat conversation, oldest line first: each line the sender's name, which opens the player's menu, and the text, with a
 * state line under a whisper that is under way or was not delivered. The web addresses of the text ({@link TextLink}) are links.
 * <p>
 * The log is read-only but selectable ({@link LogSelectionModel}): a press and drag selects across lines, Shift with a press extends the
 * selection, a double press selects a word and a press without a drag ends it; above or below the shown lines the end goes to the start of
 * the top line or the end of the bottom line. Copy, by the platform shortcut or the log's menu, takes the selected text, one line (name and
 * text) per line. A press and release on a link without a move asks before the link opens ({@link LinkDialog}); a drag that starts on a link
 * selects. The selection stays on its lines when lines come in and when the log drops its oldest lines.
 */
final class ChatLog {
    private static final @NotNull String LINE_STYLE_CLASS = "chat-line";
    private static final @NotNull String LINK_STYLE_CLASS = "chat-link";
    private static final @NotNull String SELECTION_STYLE_CLASS = "chat-selection";
    private static final @NotNull String NAME_SEPARATOR = " ";
    private static final @NotNull KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final @NotNull KeyCombination SELECT_ALL = new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);
    private static final int DOUBLE_PRESS = 2;
    private static final double HALF = 2;

    /**
     * Opens the menu of a player named in the log.
     */
    @FunctionalInterface
    interface PlayerMenu {
        /**
         * Opens the player's menu at the pointer.
         *
         * @param player     the player's friend code
         * @param playerName the player's name and tag
         * @param event      the menu request
         */
        void open(@NotNull FriendCode player, @NotNull String playerName, @NotNull ContextMenuEvent event);
    }

    /**
     * Holds one line of the log: the sender's name, the player it names, the text with its links, the text's style and a state line under it.
     */
    static final class Line {
        private final @NotNull String prefix;
        private final @Nullable FriendCode player;
        private final @NotNull String playerName;
        private final @NotNull String text;
        private final @NotNull String textStyle;
        private final @Nullable String caption;

        private final @NotNull List<@NotNull TextLink> links;
        private final @NotNull String copiedText;

        /**
         * Creates a {@link Line} instance.
         *
         * @param prefix     the sender's name with its separator, empty for a line without a sender
         * @param player     the player the line names, or {@code null} for none
         * @param playerName the name and tag of the player the line names, empty for none
         * @param text       the text
         * @param textStyle  the style class of the text
         * @param caption    the state line under the text, or {@code null} for none
         */
        Line(@NotNull String prefix, @Nullable FriendCode player, @NotNull String playerName, @NotNull String text, @NotNull String textStyle,
             @Nullable String caption) {
            this.prefix     = prefix;
            this.player     = player;
            this.playerName = playerName;
            this.text       = text;
            this.textStyle  = textStyle;
            this.caption    = caption;

            this.links      = TextLink.findLinks(text);
            this.copiedText = prefix.isEmpty() ? text : prefix + NAME_SEPARATOR + text;
        }

        /**
         * @return the line as a copy takes it: the sender's name, a space and the text, or the text alone for a line without a sender
         */
        @NotNull String getCopiedText() {
            return this.copiedText;
        }

        /**
         * @return the UTF-16 index of the text in the copied text ({@link #getCopiedText})
         */
        private int getTextOffset() {
            return this.prefix.isEmpty() ? 0 : this.prefix.length() + NAME_SEPARATOR.length();
        }
    }

    /**
     * Shows a part of a line, the sender's name or the text, as text runs over the highlight of its selected part.
     */
    private final class LineFlow extends TextFlow {
        private final @NotNull LineCell cell;
        private final int offset;
        private final int length;
        private final @NotNull Path highlight;
        private final @NotNull List<@NotNull TextLink> links;
        private final @NotNull List<@NotNull Text> linkRuns;

        /**
         * Creates a {@link LineFlow} instance.
         *
         * @param cell         the cell that shows the line
         * @param offset       the UTF-16 index of the part in the line's copied text
         * @param text         the part's text
         * @param links        the links of the text
         * @param styleClasses the style classes of the text runs
         */
        private LineFlow(@NotNull LineCell cell, int offset, @NotNull String text, @NotNull List<@NotNull TextLink> links,
                         @NotNull String @NotNull ... styleClasses) {
            int position = 0;

            this.cell      = cell;
            this.offset    = offset;
            this.length    = text.length();
            this.highlight = new Path();
            this.links     = links;
            this.linkRuns  = new ArrayList<>(links.size());

            this.highlight.getStyleClass().add(SELECTION_STYLE_CLASS);
            this.highlight.setManaged(false);
            this.getStyleClass().add(LINE_STYLE_CLASS);
            this.getChildren().add(this.highlight);

            for (TextLink link : links) {
                if (link.getStart() > position) {
                    this.addRun(text.substring(position, link.getStart()), styleClasses);
                }

                this.linkRuns.add(this.addRun(link.getAddress(), styleClasses));
                this.linkRuns.getLast().getStyleClass().add(LINK_STYLE_CLASS);
                position = link.getEnd();
            }

            if (position < text.length() || text.isEmpty()) {
                this.addRun(text.substring(position), styleClasses);
            }
        }

        private @NotNull Text addRun(@NotNull String text, @NotNull String @NotNull ... styleClasses) {
            Text run = new Text(text);

            run.getStyleClass().addAll(styleClasses);
            this.getChildren().add(run);

            return run;
        }

        /**
         * Lays out the text runs, then the highlight for the new line breaks.
         */
        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            this.showSelection();
        }

        private void showSelection() {
            int line = this.cell.getIndex();
            Line shown = this.cell.getItem();

            if (shown == null || !ChatLog.this.selection.isSelected(line)) {
                this.highlight.getElements().clear();

                return;
            }

            int start = Math.max(ChatLog.this.selection.getSelectionStart(line) - this.offset, 0);
            int end = Math.min(ChatLog.this.selection.getSelectionEnd(line, shown.getCopiedText().length()) - this.offset, this.length);

            if (start >= end) {
                this.highlight.getElements().clear();
            } else {
                this.highlight.getElements().setAll(this.getRangeShape(start, end, true));
            }
        }

        /**
         * @return the UTF-16 index of the line's copied text nearest a point of the scene, on a line break of this part
         */
        private int findIndex(double sceneX, double sceneY) {
            return this.offset + Math.clamp(this.getHitInfo(this.sceneToLocal(sceneX, sceneY)).getInsertionIndex(), 0, this.length);
        }

        /**
         * @return the link under a point of the scene, or {@code null} for none
         */
        private @Nullable TextLink findLink(double sceneX, double sceneY) {
            Point2D point = this.sceneToLocal(sceneX, sceneY);

            if (this.links.isEmpty() || !this.getLayoutBounds().contains(point)) {
                return null;
            }

            int index = this.getHitInfo(point).getCharIndex();

            for (int link = 0; link < this.links.size(); link++) {
                if (this.links.get(link).contains(index) && this.linkRuns.get(link).getBoundsInParent().contains(point)) {
                    return this.links.get(link);
                }
            }

            return null;
        }

        private boolean isAt(double sceneX, double sceneY) {
            return this.getLayoutBounds().contains(this.sceneToLocal(sceneX, sceneY));
        }
    }

    /**
     * Shows one line of the log. The cell takes the pointer itself, its content never does, so that a drag goes on when lines come in.
     */
    private final class LineCell extends ListCell<Line> {
        private @Nullable LineFlow nameFlow;
        private @Nullable LineFlow textFlow;

        private LineCell() {
            this.nameFlow = null;
            this.textFlow = null;

            ComponentUtils.markFirstRow(this);
            this.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> ChatLog.this.press(this, event));
            this.addEventFilter(MouseEvent.MOUSE_DRAGGED, ChatLog.this::drag);
            this.addEventFilter(MouseEvent.MOUSE_RELEASED, ChatLog.this::release);
            this.addEventHandler(MouseEvent.MOUSE_MOVED, event -> this.setCursor(this.findCursor(event.getSceneX(), event.getSceneY())));
            this.setOnContextMenuRequested(event -> ChatLog.this.openMenu(this, event));
        }

        /**
         * Updates the item associated with this cell.
         *
         * @param line  the new item for the cell
         * @param empty whether or not this cell represents data from the list
         */
        @Override
        protected void updateItem(@Nullable Line line, boolean empty) {
            super.updateItem(line, empty);

            this.nameFlow = null;
            this.textFlow = null;

            if (empty || line == null) {
                this.setText(null);
                this.setGraphic(null);

                return;
            }

            LineFlow text = new LineFlow(this, line.getTextOffset(), line.text, line.links, line.textStyle);
            Region row = text;

            this.textFlow = text;

            if (!line.prefix.isEmpty()) {
                this.nameFlow = new LineFlow(this, 0, line.prefix, List.of(), "text-heading", "text-accent-strong");
                row           = ComponentUtils.createTextRow(Spacing.S, this.nameFlow, text);
            }

            VBox graphic = ComponentUtils.createColumn(Spacing.XS, row);

            if (line.caption != null) {
                graphic.getChildren().add(ComponentUtils.createLabel(line.caption, "text-caption"));
            }

            graphic.setMouseTransparent(true);
            ComponentUtils.setCellGraphic(this, graphic);
        }

        /**
         * @return {@code true} if the cell shows a line of the log, {@code false} for an empty or a spare cell
         */
        private boolean isShowingLine() {
            Parent parent = this.getParent();

            return !this.isEmpty() && this.getItem() != null && this.getIndex() >= 0 && this.isVisible() && parent != null && parent.isVisible();
        }

        private void showSelection() {
            if (this.nameFlow != null) {
                this.nameFlow.showSelection();
            }

            if (this.textFlow != null) {
                this.textFlow.showSelection();
            }
        }

        /**
         * Finds the UTF-16 index of the line's copied text nearest a point of the scene: in the name left of the middle of the gap before the
         * text, else in the text.
         */
        private int findIndex(double sceneX, double sceneY) {
            LineFlow name = this.nameFlow;
            LineFlow text = this.textFlow;

            if (text == null) {
                return 0;
            }

            if (name == null) {
                return text.findIndex(sceneX, sceneY);
            }

            double nameEnd = name.localToScene(name.getLayoutBounds()).getMaxX();
            double textStart = text.localToScene(text.getLayoutBounds()).getMinX();

            return sceneX < (nameEnd + textStart) / HALF ? name.findIndex(sceneX, sceneY) : text.findIndex(sceneX, sceneY);
        }

        private @Nullable TextLink findLink(double sceneX, double sceneY) {
            return this.textFlow == null ? null : this.textFlow.findLink(sceneX, sceneY);
        }

        private boolean isOnName(double sceneX, double sceneY) {
            return this.nameFlow != null && this.nameFlow.isAt(sceneX, sceneY);
        }

        private @Nullable Cursor findCursor(double sceneX, double sceneY) {
            if (this.isEmpty() || this.getItem() == null) {
                return null;
            }

            return this.findLink(sceneX, sceneY) == null ? Cursor.TEXT : Cursor.HAND;
        }
    }

    /**
     * Counts the lines a log dropped at its start: the fewest previous lines after which the previous lines start the current ones.
     */
    private static int countDroppedLines(@NotNull List<@NotNull Line> previous, @NotNull List<@NotNull Line> current) {
        for (int dropped = 0; dropped < previous.size(); dropped++) {
            if (startsWith(current, previous, dropped)) {
                return dropped;
            }
        }

        return previous.size();
    }

    private static boolean startsWith(@NotNull List<@NotNull Line> current, @NotNull List<@NotNull Line> previous, int dropped) {
        int kept = previous.size() - dropped;

        if (kept > current.size()) {
            return false;
        }

        for (int index = 0; index < kept; index++) {
            if (!previous.get(dropped + index).getCopiedText().equals(current.get(index).getCopiedText())) {
                return false;
            }
        }

        return true;
    }


    private final @NotNull LauncherShell shell;
    private final @NotNull LauncherTexts texts;
    private final @NotNull ChatScreen.Actions actions;
    private final @NotNull PlayerMenu playerMenu;

    private final @NotNull ListView<Line> view;
    private final @NotNull LogSelectionModel selection;
    /**
     * The cells the list made, which it may drop.
     */
    private final @NotNull Set<@NotNull LineCell> cells;
    /**
     * The link under the press that a release without a move opens, or {@code null} for none.
     */
    private @Nullable TextLink pressedLink;

    /**
     * Creates a {@link ChatLog} instance without lines.
     *
     * @param shell      the shell that shows the log's menu and the link dialog
     * @param texts      the launcher texts of the display language
     * @param actions    receives the copied text and the links to open
     * @param playerMenu opens the menu of a player named in the log
     */
    ChatLog(@NotNull LauncherShell shell, @NotNull LauncherTexts texts, @NotNull ChatScreen.Actions actions, @NotNull PlayerMenu playerMenu) {
        this.shell      = shell;
        this.texts      = texts;
        this.actions    = actions;
        this.playerMenu = playerMenu;

        this.view        = ComponentUtils.createLogListView();
        this.selection   = new LogSelectionModel();
        this.cells       = Collections.newSetFromMap(new WeakHashMap<>());
        this.pressedLink = null;

        this.view.setCellFactory(list -> {
            LineCell cell = new LineCell();

            this.cells.add(cell);

            return cell;
        });
        this.view.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (COPY.match(event)) {
                event.consume();
                this.copy();
            } else if (SELECT_ALL.match(event)) {
                event.consume();
                this.selectAll();
            }
        });
    }

    /**
     * @return the list view of the log
     */
    @NotNull ListView<Line> getView() {
        return this.view;
    }

    /**
     * Shows lines and scrolls to the last one, unless a drag selects. For the conversation shown before, the selection stays on its lines,
     * also when the log dropped its oldest lines; for another conversation it ends.
     *
     * @param lines            the lines, oldest first
     * @param sameConversation whether the lines belong to the conversation shown before
     */
    void show(@NotNull List<@NotNull Line> lines, boolean sameConversation) {
        if (sameConversation) {
            this.selection.removeFirstLines(countDroppedLines(this.view.getItems(), lines));
        } else {
            this.selection.clear();
            this.pressedLink = null;
        }

        this.view.getItems().setAll(lines);

        if (!this.selection.isDragging()) {
            ComponentUtils.scrollToLast(this.view);
        }
    }

    /**
     * Copies the selected text, one line per line ({@link LogSelectionModel#getSelectedText}); without a selection nothing is copied.
     */
    void copy() {
        if (!this.selection.hasSelection()) {
            return;
        }

        this.actions.onCopy(this.selection.getSelectedText(this.view.getItems().stream().map(Line::getCopiedText).toList()));
    }

    /**
     * Selects every line.
     */
    void selectAll() {
        List<Line> lines = this.view.getItems();

        if (lines.isEmpty()) {
            return;
        }

        this.selection.press(0, 0, false);
        this.selection.drag(lines.size() - 1, lines.getLast().getCopiedText().length());
        this.selection.release();
        this.showSelection();
    }

    private void showSelection() {
        for (LineCell cell : this.cells) {
            cell.showSelection();
        }
    }

    /**
     * Presses a cell: on a line the press starts a selection or selects a word on a double press, below the lines it ends the selection.
     */
    private void press(@NotNull LineCell cell, @NotNull MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        Line line = cell.getItem();

        event.consume();
        this.view.requestFocus();
        this.pressedLink = null;

        if (!cell.isShowingLine() || line == null) {
            this.selection.clear();
        } else if (event.getClickCount() >= DOUBLE_PRESS) {
            this.selection.selectWord(cell.getIndex(), line.getCopiedText(), cell.findIndex(event.getSceneX(), event.getSceneY()));
        } else {
            this.selection.press(cell.getIndex(), cell.findIndex(event.getSceneX(), event.getSceneY()), event.isShiftDown());
            this.pressedLink = event.isShiftDown() ? null : cell.findLink(event.getSceneX(), event.getSceneY());
        }

        this.showSelection();
    }

    /**
     * Drags the end of the selection the press started, once the pointer left the press position; above or below the shown lines the end
     * goes to the start of the top line or the end of the bottom line.
     */
    private void drag(@NotNull MouseEvent event) {
        if (!event.isPrimaryButtonDown()) {
            return;
        }

        event.consume();

        if (!this.selection.isDragging() || event.isStillSincePress()) {
            return;
        }

        LineCell top = null;
        LineCell bottom = null;

        this.pressedLink = null;

        for (LineCell cell : this.cells) {
            if (!cell.isShowingLine()) {
                continue;
            }

            Bounds bounds = cell.localToScene(cell.getLayoutBounds());

            if (event.getSceneY() >= bounds.getMinY() && event.getSceneY() < bounds.getMaxY()) {
                this.selection.drag(cell.getIndex(), cell.findIndex(event.getSceneX(), event.getSceneY()));
                this.showSelection();

                return;
            }

            if (top == null || cell.getIndex() < top.getIndex()) {
                top = cell;
            }

            if (bottom == null || cell.getIndex() > bottom.getIndex()) {
                bottom = cell;
            }
        }

        if (top == null || bottom == null) {
            return;
        }

        if (event.getSceneY() < top.localToScene(top.getLayoutBounds()).getMinY()) {
            this.selection.drag(top.getIndex(), 0);
        } else {
            this.selection.drag(bottom.getIndex(), this.view.getItems().get(bottom.getIndex()).getCopiedText().length());
        }

        this.showSelection();
    }

    /**
     * Releases the press; the selection stays. A release without a move after a press on a link asks before the link opens.
     */
    private void release(@NotNull MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        TextLink link = this.pressedLink;

        event.consume();
        this.pressedLink = null;
        this.selection.release();

        if (link != null && event.isStillSincePress()) {
            new LinkDialog(this.shell, this.texts, link.getAddress(), this.actions::onOpenWebsite).open();
        }
    }

    /**
     * Opens a menu at the pointer: the player's menu on a sender's name, else the log's menu with Copy and Select all.
     */
    private void openMenu(@NotNull LineCell cell, @NotNull ContextMenuEvent event) {
        Line line = cell.getItem();
        FriendCode player = line == null ? null : line.player;

        event.consume();

        if (line != null && player != null && cell.isOnName(event.getSceneX(), event.getSceneY())) {
            this.playerMenu.open(player, line.playerName, event);

            return;
        }

        Button copy = ComponentUtils.createMenuButton(this.texts.get("chat.log.copy"));
        Button selectAll = ComponentUtils.createMenuButton(this.texts.get("chat.log.select.all"));

        copy.setDisable(!this.selection.hasSelection());
        copy.setOnAction(action -> {
            this.shell.closeMenu();
            this.copy();
        });
        selectAll.setDisable(this.view.getItems().isEmpty());
        selectAll.setOnAction(action -> {
            this.shell.closeMenu();
            this.selectAll();
        });

        this.shell.openMenu(ComponentUtils.createMenuPanel(null, copy, selectAll), event.getSceneX(), event.getSceneY());
    }
}
