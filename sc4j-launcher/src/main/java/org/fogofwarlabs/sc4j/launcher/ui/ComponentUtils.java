package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the shared components of the launcher theme. Every screen builds its controls here, so each component has one
 * definition: the style classes of {@code sc4j-launcher.css} plus the node structure below. Spacing and padding come from
 * the stylesheet's spacing classes, never from code.
 */
public final class ComponentUtils {
    private static final @NotNull String BADGE_PREFIX = "badge-";
    private static final @NotNull String STATUS_MARKER_PREFIX = "status-marker-";
    private static final @NotNull String NOTICE_PREFIX = "notice-";
    private static final @NotNull String COMPACT_LIST_STYLE_CLASS = "compact";
    private static final @NotNull String SUNKEN_LIST_STYLE_CLASS = "sunken";
    private static final @NotNull String LOG_LIST_STYLE_CLASS = "log-list";
    private static final @NotNull String VIRTUAL_FLOW_SELECTOR = ".virtual-flow";
    private static final @NotNull Duration FULL_TEXT_DELAY = Duration.millis(400);
    private static final double CUT_TOLERANCE = 0.5;
    private static final double FULL_PERCENT = 100;
    private static final @NotNull PseudoClass FIRST_ROW = PseudoClass.getPseudoClass("first-row");

    /**
     * Creates a label; while its text is cut short, hovering it shows the whole text ({@link #showFullTextWhenCut}).
     *
     * @param text         the text
     * @param styleClasses the style classes to add
     * @return the created label
     */
    public static @NotNull Label createLabel(@NotNull String text, @NotNull String @NotNull ... styleClasses) {
        Label label = new Label(text);

        label.getStyleClass().addAll(styleClasses);
        showFullTextWhenCut(label);

        return label;
    }

    /**
     * Gives a labeled control a tooltip with its whole text while the text does not fit on one line and is cut short.
     *
     * @param labeled the labeled control
     */
    public static void showFullTextWhenCut(@NotNull Labeled labeled) {
        Tooltip tooltip = new Tooltip();
        InvalidationListener update = observable -> labeled.setTooltip(isCut(labeled) ? tooltip : null);

        tooltip.textProperty().bind(labeled.textProperty());
        tooltip.setShowDelay(FULL_TEXT_DELAY);
        labeled.widthProperty().addListener(update);
        labeled.textProperty().addListener(update);
        labeled.fontProperty().addListener(update);
    }

    private static boolean isCut(@NotNull Labeled labeled) {
        return !labeled.isWrapText() && labeled.getWidth() > 0 && labeled.getWidth() + CUT_TOLERANCE < labeled.prefWidth(-1);
    }

    /**
     * Creates a label that wraps its text, for paragraphs.
     *
     * @param text         the text
     * @param styleClasses the style classes to add
     * @return the created label
     */
    public static @NotNull Label createParagraph(@NotNull String text, @NotNull String @NotNull ... styleClasses) {
        Label label = createLabel(text, styleClasses);

        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);

        return label;
    }

    /**
     * Creates a button.
     *
     * @param text the text
     * @param role the role, which selects the look
     * @return the created button
     */
    public static @NotNull Button createButton(@NotNull String text, @NotNull ButtonRole role) {
        Button button = new Button(text);

        switch (role) {
            case PRIMARY -> button.getStyleClass().add("primary");
            case DANGER -> button.getStyleClass().add("danger");
            case TEXT_ONLY -> button.getStyleClass().add("text-only");
            case SECONDARY -> {
                // The default button look is the secondary look.
            }
        }

        return button;
    }

    /**
     * Creates a small button, for panel headers and footers.
     *
     * @param text the text
     * @param role the role, which selects the look
     * @return the created button
     */
    public static @NotNull Button createSmallButton(@NotNull String text, @NotNull ButtonRole role) {
        Button button = createButton(text, role);

        button.getStyleClass().add("small");

        return button;
    }

    /**
     * Creates a menu entry: a flat button as wide as its menu, its text on the left.
     *
     * @param text the text
     * @return the created button
     */
    public static @NotNull Button createMenuButton(@NotNull String text) {
        Button button = new Button(text);

        button.getStyleClass().add("menu-item");
        button.setMaxWidth(Double.MAX_VALUE);

        return button;
    }

    /**
     * Creates a chamfered badge.
     *
     * @param text the text, shown in capitals by the stylesheet
     * @param tone the colour role
     * @return the created badge
     */
    public static @NotNull Label createBadge(@NotNull String text, @NotNull Tone tone) {
        Label badge = createLabel(text, "badge", BADGE_PREFIX + tone.getSuffix());

        badge.setMinWidth(Region.USE_PREF_SIZE);

        return badge;
    }

    /**
     * Puts a dot badge on a navigation entry or a button: a small dot in the danger colour over the top right corner of its text, which marks
     * something new there. The dot stays hidden until {@link #setDotBadgeShown} shows it; shown or hidden, it takes no room, so the text keeps
     * its place.
     *
     * @param labeled the navigation entry or button, which has no graphic of its own
     */
    public static void addDotBadge(@NotNull Labeled labeled) {
        labeled.setGraphic(new DotBadge(labeled));
        labeled.setContentDisplay(ContentDisplay.CENTER);
    }

    /**
     * Shows or hides the dot badge of a navigation entry or a button ({@link #addDotBadge}).
     *
     * @param labeled the navigation entry or button
     * @param shown   whether the dot is shown
     */
    public static void setDotBadgeShown(@NotNull Labeled labeled, boolean shown) {
        if (labeled.getGraphic() instanceof DotBadge badge) {
            badge.setDotShown(shown);
        }
    }

    /**
     * Creates a diamond status marker, the cell of the logo grid.
     *
     * @param tone the colour role
     * @return the created marker
     */
    public static @NotNull Region createStatusMarker(@NotNull Tone tone) {
        Region marker = new Region();

        marker.getStyleClass().addAll("status-marker", STATUS_MARKER_PREFIX + tone.getSuffix());

        return marker;
    }

    /**
     * Creates an inline notice: a coloured bar, a wrapping text and the notice's actions after the text, such as a small button.
     *
     * @param tone     the colour role
     * @param text     the text
     * @param trailing the nodes after the text
     * @return the created notice
     */
    public static @NotNull HBox createNotice(@NotNull Tone tone, @NotNull String text, @NotNull Node @NotNull ... trailing) {
        Label label = createParagraph(text);
        HBox notice = new HBox(label);

        notice.getChildren().addAll(trailing);
        HBox.setHgrow(label, Priority.ALWAYS);
        notice.getStyleClass().add("notice");

        if (tone != Tone.ACCENT && tone != Tone.NEUTRAL) {
            notice.getStyleClass().add(NOTICE_PREFIX + tone.getSuffix());
        }

        return notice;
    }

    /**
     * Creates a form field: a label above its control.
     *
     * @param label   the label text
     * @param control the control
     * @return the created field
     */
    public static @NotNull VBox createField(@NotNull String label, @NotNull Node control) {
        VBox field = new VBox(createLabel(label, "field-label"), control);

        field.getStyleClass().add("field");

        return field;
    }

    /**
     * Creates a form: its fields ({@link #createField}) stacked in a column, one field per row.
     *
     * @param fields the fields
     * @return the created form
     */
    public static @NotNull VBox createForm(@NotNull Node @NotNull ... fields) {
        VBox form = new VBox(fields);

        form.getStyleClass().add("form");

        return form;
    }

    /**
     * Creates a row with the stylesheet's spacing, its children centred vertically.
     *
     * @param spacing  the spacing between the children
     * @param children the children
     * @return the created row
     */
    public static @NotNull HBox createRow(@NotNull Spacing spacing, @NotNull Node @NotNull ... children) {
        HBox row = new HBox(children);

        row.getStyleClass().add(spacing.getStyleClass());
        row.setAlignment(Pos.CENTER_LEFT);

        return row;
    }

    /**
     * Creates a slot whose content a screen replaces; the slot takes no room in its parent's layout, spacing included, while it is empty.
     *
     * @return the created slot
     */
    public static @NotNull VBox createSlot() {
        VBox slot = new VBox();
        BooleanBinding filled = Bindings.isNotEmpty(slot.getChildren());

        slot.visibleProperty().bind(filled);
        slot.managedProperty().bind(filled);

        return slot;
    }

    /**
     * Creates a column with the stylesheet's spacing.
     *
     * @param spacing  the spacing between the children
     * @param children the children
     * @return the created column
     */
    public static @NotNull VBox createColumn(@NotNull Spacing spacing, @NotNull Node @NotNull ... children) {
        VBox column = new VBox(children);

        column.getStyleClass().add(spacing.getStyleClass());

        return column;
    }

    /**
     * Creates a row of columns of equal width that share the row's width and take its full height, such as the two columns of a screen.
     *
     * @param spacing the spacing between the columns
     * @param columns the columns
     * @return the created row
     */
    public static @NotNull GridPane createEqualColumns(@NotNull Spacing spacing, @NotNull Region @NotNull ... columns) {
        GridPane grid = new GridPane();
        RowConstraints row = new RowConstraints();

        grid.getStyleClass().add(spacing.getStyleClass());
        row.setVgrow(Priority.ALWAYS);
        grid.getRowConstraints().add(row);

        for (int index = 0; index < columns.length; index++) {
            ColumnConstraints column = new ColumnConstraints();

            column.setPercentWidth(FULL_PERCENT / columns.length);
            grid.getColumnConstraints().add(column);
            grid.add(columns[index], index, 0);
            fillHeight(columns[index]);
        }

        return grid;
    }

    /**
     * Creates a row of a label and a text after it: the label keeps its width, the text takes the rest of the row and wraps, such as a
     * sender's name before a chat line.
     *
     * @param spacing the spacing between the label and the text
     * @param label   the label
     * @param text    the text, a paragraph ({@link #createParagraph})
     * @return the created row, its children aligned at the top
     */
    public static @NotNull HBox createTextRow(@NotNull Spacing spacing, @NotNull Label label, @NotNull Label text) {
        HBox row = createRow(spacing, label, text);

        label.setMinWidth(Region.USE_PREF_SIZE);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        row.setAlignment(Pos.TOP_LEFT);

        return row;
    }

    /**
     * Lets a region take the full height its parent gives it, such as a panel beside a taller one.
     *
     * @param region the region
     */
    public static void fillHeight(@NotNull Region region) {
        region.setMaxHeight(Double.MAX_VALUE);
    }

    /**
     * Gives a side column of a screen its fixed width and the full height ({@link #fillHeight}).
     *
     * @param column the column, such as a panel
     * @param width  the width
     */
    public static void setColumnWidth(@NotNull Region column, @NotNull ColumnWidth width) {
        column.setMinWidth(width.getWidth());
        column.setPrefWidth(width.getWidth());
        fillHeight(column);
    }

    /**
     * Gives an input control its width.
     *
     * @param control the control, such as a text field or a spinner
     * @param width   the width
     */
    public static void setControlWidth(@NotNull Region control, @NotNull ControlWidth width) {
        control.setPrefWidth(width.getWidth());
        control.setMaxWidth(width.getWidth());
    }

    /**
     * Creates an empty region that takes the free space of a row or a column.
     *
     * @return the created spacer
     */
    public static @NotNull Region createGrowingSpacer() {
        Region spacer = new Region();

        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);

        return spacer;
    }

    /**
     * Creates an image view of a square image.
     *
     * @param image the image
     * @param size  the width and height in pixels
     * @return the created image view
     */
    public static @NotNull ImageView createImageView(@NotNull Image image, double size) {
        ImageView view = new ImageView(image);

        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        return view;
    }

    /**
     * Creates an image in the thin thumbnail frame, or the frame with a question mark when there is no image.
     * <p>
     * The frame always has the given size, with or without an image: the image is scaled to fit inside the frame's border, keeping its
     * aspect ratio, and centred.
     *
     * @param image  the image, or {@code null} for the placeholder
     * @param width  the width of the frame in pixels
     * @param height the height of the frame in pixels
     * @return the created thumbnail
     */
    public static @NotNull StackPane createThumbnail(@Nullable Image image, double width, double height) {
        StackPane frame = new StackPane();

        if (image != null && !image.isError()) {
            ImageView view = new ImageView(image);

            view.fitWidthProperty().bind(Bindings.createDoubleBinding(() -> Math.max(0, width - frame.getInsets().getLeft() - frame.getInsets().getRight()),
                                                                      frame.insetsProperty()));
            view.fitHeightProperty().bind(Bindings.createDoubleBinding(() -> Math.max(0, height - frame.getInsets().getTop() - frame.getInsets().getBottom()),
                                                                       frame.insetsProperty()));
            view.setPreserveRatio(true);
            view.setSmooth(true);
            frame.getChildren().add(view);
        } else {
            frame.getChildren().add(createLabel("?", "text-title", "text-muted"));
        }

        frame.getStyleClass().add("thumbnail");
        frame.setMinSize(width, height);
        frame.setPrefSize(width, height);
        frame.setMaxSize(width, height);

        return frame;
    }

    /**
     * Creates a raised metal panel with a title header; the body grows to fill the panel.
     *
     * @param title          the title, shown in capitals by the stylesheet
     * @param body           the body
     * @param headerTrailing the nodes at the right end of the header
     * @return the created panel
     */
    public static @NotNull VBox createPanel(@NotNull String title, @NotNull Node body, @NotNull Node @NotNull ... headerTrailing) {
        return createPanel(createLabel(title), body, headerTrailing);
    }

    /**
     * Creates a raised metal panel with a title header whose title label the caller keeps to change its text; the body grows to fill the
     * panel.
     *
     * @param title          the title label, shown in capitals by the stylesheet
     * @param body           the body
     * @param headerTrailing the nodes at the right end of the header
     * @return the created panel
     */
    public static @NotNull VBox createPanel(@NotNull Label title, @NotNull Node body, @NotNull Node @NotNull ... headerTrailing) {
        HBox header = new HBox(title, createGrowingSpacer());
        VBox panel;

        title.getStyleClass().add("panel-title");

        header.getChildren().addAll(headerTrailing);
        header.getStyleClass().add("panel-header");
        VBox.setVgrow(body, Priority.ALWAYS);

        panel = new VBox(header, body);
        panel.getStyleClass().add("panel");

        return panel;
    }

    /**
     * Creates a raised metal panel with a title header, a body that grows to fill the panel and a footer strip ({@link #createPanelFooter}).
     *
     * @param title          the title, shown in capitals by the stylesheet
     * @param body           the body
     * @param footer         the footer
     * @param headerTrailing the nodes at the right end of the header
     * @return the created panel
     */
    public static @NotNull VBox createPanelWithFooter(@NotNull String title, @NotNull Node body, @NotNull HBox footer,
                                                      @NotNull Node @NotNull ... headerTrailing) {
        return createPanelWithFooter(createLabel(title), body, footer, headerTrailing);
    }

    /**
     * Creates a raised metal panel with a title header whose title label the caller keeps to change its text, a body that grows to fill the
     * panel and a footer strip ({@link #createPanelFooter}).
     *
     * @param title          the title label, shown in capitals by the stylesheet
     * @param body           the body
     * @param footer         the footer
     * @param headerTrailing the nodes at the right end of the header
     * @return the created panel
     */
    public static @NotNull VBox createPanelWithFooter(@NotNull Label title, @NotNull Node body, @NotNull HBox footer,
                                                      @NotNull Node @NotNull ... headerTrailing) {
        VBox panel = createPanel(title, body, headerTrailing);

        panel.getChildren().add(footer);

        return panel;
    }

    /**
     * Creates a raised metal panel without a header.
     *
     * @param body the body
     * @return the created panel
     */
    public static @NotNull VBox createPanel(@NotNull Node body) {
        VBox panel = new VBox(body);

        VBox.setVgrow(body, Priority.ALWAYS);
        panel.getStyleClass().add("panel");

        return panel;
    }

    /**
     * Creates a padded panel body holding its children in a column.
     *
     * @param children the children
     * @return the created body
     */
    public static @NotNull VBox createPanelBody(@NotNull Node @NotNull ... children) {
        VBox body = new VBox(children);

        body.getStyleClass().add("panel-body");

        return body;
    }

    /**
     * Creates a panel body for a list that runs edge to edge.
     *
     * @param children the children
     * @return the created body
     */
    public static @NotNull VBox createFlushPanelBody(@NotNull Node @NotNull ... children) {
        VBox body = createPanelBody(children);

        body.getStyleClass().add("flush");

        return body;
    }

    /**
     * Creates the footer strip of a panel, usually holding the panel's actions.
     *
     * @param children the children
     * @return the created footer
     */
    public static @NotNull HBox createPanelFooter(@NotNull Node @NotNull ... children) {
        HBox footer = new HBox(children);

        footer.getStyleClass().add("panel-footer");

        return footer;
    }

    /**
     * Creates the footer strip of a panel that holds a text input and its action, such as a chat line and Send: the room around the input row
     * is the same on every side and equals the gap between the input and the action. The input takes the free width.
     *
     * @param input  the input, such as a text field
     * @param action the action
     * @param below  the nodes under the input row, such as the input's message
     * @return the created footer
     */
    public static @NotNull HBox createInputFooter(@NotNull Region input, @NotNull Button action, @NotNull Node @NotNull ... below) {
        HBox row = new HBox(input, action);
        VBox column = createColumn(Spacing.XS, row);
        HBox footer = createPanelFooter(column);

        row.getStyleClass().add("input-row");
        row.setAlignment(Pos.CENTER_LEFT);
        column.getChildren().addAll(below);
        footer.getStyleClass().add("input-footer");
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox.setHgrow(column, Priority.ALWAYS);

        return footer;
    }

    /**
     * Creates the red panel of a failure report or another blocking error, holding its children in a column.
     *
     * @param children the children, such as a headline and the details
     * @return the created panel
     */
    public static @NotNull VBox createDangerPanel(@NotNull Node @NotNull ... children) {
        VBox panel = new VBox(children);

        panel.getStyleClass().add("panel-danger");

        return panel;
    }

    /**
     * Creates a menu: a panel of flat entries ({@link #createMenuButton}) under a header, such as a {@link ListItem} of the player the menu is about.
     *
     * @param header  the header
     * @param entries the entries and the separators between their groups
     * @return the created menu
     */
    public static @NotNull VBox createMenuPanel(@NotNull Region header, @NotNull Node @NotNull ... entries) {
        VBox menu = new VBox(header);

        header.getStyleClass().add("menu-header");
        menu.getChildren().addAll(entries);
        menu.getStyleClass().addAll("panel", "menu-panel");

        return menu;
    }

    /**
     * Creates a read-only monospaced text area, for logs and file contents.
     *
     * @param text       the text
     * @param styleClass {@code log-area} or {@code code-area}
     * @return the created text area
     */
    public static @NotNull TextArea createCodeArea(@NotNull String text, @NotNull String styleClass) {
        TextArea area = new TextArea(text);

        area.getStyleClass().add(styleClass);
        area.setEditable(false);

        return area;
    }

    /**
     * Creates a read-only text area of wrapped plain text in the body font that scrolls, for longer text such as release notes; a dialog
     * gives it its height ({@link ModalDialog#addGrowingContent}).
     *
     * @param text the text
     * @return the created text area
     */
    public static @NotNull TextArea createTextArea(@NotNull String text) {
        TextArea area = new TextArea(text);

        area.setEditable(false);
        area.setWrapText(true);

        return area;
    }

    /**
     * Creates an empty key/value metadata grid in a sunken frame; {@link #addMetaRow} adds its rows.
     *
     * @return the created grid
     */
    public static @NotNull GridPane createMetaGrid() {
        GridPane grid = new GridPane();

        grid.getStyleClass().addAll("panel-sunken", "spacing-s");

        return grid;
    }

    /**
     * Adds a row of text to a metadata grid ({@link #createMetaGrid}); a long value wraps.
     *
     * @param grid  the grid
     * @param key   the key, in the faint colour
     * @param value the value
     */
    public static void addMetaRow(@NotNull GridPane grid, @NotNull String key, @NotNull String value) {
        Label valueLabel = createLabel(value, "meta-value");

        valueLabel.setWrapText(true);
        addMetaRow(grid, key, valueLabel);
    }

    /**
     * Adds a row to a metadata grid ({@link #createMetaGrid}) under its last row.
     *
     * @param grid  the grid
     * @param key   the key, in the faint colour
     * @param value the value node, such as a link
     */
    public static void addMetaRow(@NotNull GridPane grid, @NotNull String key, @NotNull Node value) {
        Label keyLabel = createLabel(key, "meta-key");

        keyLabel.setMinWidth(Region.USE_PREF_SIZE);
        grid.addRow(grid.getRowCount(), keyLabel, value);
    }

    /**
     * Creates a list view whose items have the given size.
     *
     * @param size the size of the items
     * @param <T>  the item type
     * @return the created list view
     */
    public static <T> @NotNull ListView<T> createListView(@NotNull ListItemSize size) {
        ListView<T> list = new ListView<>();

        if (size == ListItemSize.COMPACT) {
            list.getStyleClass().add(COMPACT_LIST_STYLE_CLASS);
        }

        return list;
    }

    /**
     * Creates a list view in a sunken frame, for a list inside a panel body.
     *
     * @param size the size of the items
     * @param <T>  the item type
     * @return the created list view
     */
    public static <T> @NotNull ListView<T> createSunkenListView(@NotNull ListItemSize size) {
        ListView<T> list = createListView(size);

        list.getStyleClass().add(SUNKEN_LIST_STYLE_CLASS);

        return list;
    }

    /**
     * Creates a list view of log lines that run edge to edge in a flush panel body ({@link #createFlushPanelBody}): the lines sit close together and
     * are not selected, the first line ({@link #markFirstRow}) starts below the header by the room left of the lines, and the room below the
     * last line is the same.
     *
     * @param <T> the item type
     * @return the created list view
     */
    public static <T> @NotNull ListView<T> createLogListView() {
        ListView<T> list = new ListView<>();

        list.getStyleClass().add(LOG_LIST_STYLE_CLASS);

        return list;
    }

    /**
     * Scrolls a list view to its end, now and once more after the scene's next layout pass, in which the items' heights may change (for
     * example when the scroll bar appears and narrows them).
     *
     * @param list the list view
     */
    public static void scrollToLast(@NotNull ListView<?> list) {
        Scene scene = list.getScene();

        if (list.getItems().isEmpty()) {
            return;
        }

        scrollToEnd(list);

        if (scene == null) {
            return;
        }

        Runnable[] again = new Runnable[1];

        again[0] = () -> {
            scene.removePostLayoutPulseListener(again[0]);
            scrollToEnd(list);
        };
        scene.addPostLayoutPulseListener(again[0]);
    }

    private static void scrollToEnd(@NotNull ListView<?> list) {
        list.scrollTo(list.getItems().size() - 1);

        // The list's flow estimates the positions of items it has not measured; scrolling by the rest of its length ends at its last pixel.
        if (list.lookup(VIRTUAL_FLOW_SELECTOR) instanceof VirtualFlow<?> flow) {
            flow.scrollPixels(Double.MAX_VALUE);
        }
    }

    /**
     * Gives a list cell the pseudo-class {@code first-row} while it shows the list's first item.
     *
     * @param cell the list cell
     */
    public static void markFirstRow(@NotNull ListCell<?> cell) {
        cell.indexProperty().addListener((observable, previous, index) -> cell.pseudoClassStateChanged(FIRST_ROW, index.intValue() == 0));
    }

    /**
     * Sets a rich cell graphic that spans the cell width (inside its border and padding), so trailing badges align.
     *
     * @param cell    the list cell
     * @param graphic the graphic
     */
    public static void setCellGraphic(@NotNull ListCell<?> cell, @NotNull Region graphic) {
        graphic.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> cell.getWidth() - cell.getInsets().getLeft() - cell.getInsets().getRight(),
                                                                      cell.widthProperty(), cell.insetsProperty()));
        graphic.setMinWidth(0);
        cell.setText(null);
        cell.setGraphic(graphic);
    }

    /**
     * Creates a combo box whose drop-down list is exactly as wide as the combo box.
     *
     * @param width the preferred width
     * @param <T>   the item type
     * @return the combo box
     */
    public static <T> @NotNull ComboBox<T> createComboBox(@NotNull ControlWidth width) {
        ComboBox<T> comboBox = new ComboBox<>();

        comboBox.setPrefWidth(width.getWidth());
        comboBox.addEventHandler(ComboBoxBase.ON_SHOWING, event -> {
            if (comboBox.getSkin() instanceof ComboBoxListViewSkin<?> skin && skin.getPopupContent() instanceof Region list) {
                list.setMinWidth(comboBox.getWidth());
                list.setPrefWidth(comboBox.getWidth());
                list.setMaxWidth(comboBox.getWidth());
            }
        });

        return comboBox;
    }


    private ComponentUtils() {
    }
}
