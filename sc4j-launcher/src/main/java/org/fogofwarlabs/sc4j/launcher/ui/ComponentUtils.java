package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
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
    private static final @NotNull PseudoClass FIRST_ROW = PseudoClass.getPseudoClass("first-row");

    /**
     * Creates a label.
     *
     * @param text         the text
     * @param styleClasses the style classes to add
     * @return the created label
     */
    public static @NotNull Label label(@NotNull String text, @NotNull String @NotNull ... styleClasses) {
        Label label = new Label(text);

        label.getStyleClass().addAll(styleClasses);

        return label;
    }

    /**
     * Creates a label that wraps its text, for paragraphs.
     *
     * @param text         the text
     * @param styleClasses the style classes to add
     * @return the created label
     */
    public static @NotNull Label paragraph(@NotNull String text, @NotNull String @NotNull ... styleClasses) {
        Label label = label(text, styleClasses);

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
    public static @NotNull Button button(@NotNull String text, @NotNull ButtonRole role) {
        Button button = new Button(text);

        switch (role) {
            case PRIMARY -> button.getStyleClass().add("primary");
            case DANGER -> button.getStyleClass().add("danger");
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
    public static @NotNull Button smallButton(@NotNull String text, @NotNull ButtonRole role) {
        Button button = button(text, role);

        button.getStyleClass().add("small");

        return button;
    }

    /**
     * Creates a menu entry: a flat button as wide as its menu, its text on the left.
     *
     * @param text the text
     * @return the created button
     */
    public static @NotNull Button menuButton(@NotNull String text) {
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
    public static @NotNull Label badge(@NotNull String text, @NotNull Tone tone) {
        Label badge = label(text, "badge", BADGE_PREFIX + tone.getSuffix());

        badge.setMinWidth(Region.USE_PREF_SIZE);

        return badge;
    }

    /**
     * Creates a diamond status marker, the cell of the logo grid.
     *
     * @param tone the colour role
     * @return the created marker
     */
    public static @NotNull Region statusMarker(@NotNull Tone tone) {
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
    public static @NotNull HBox notice(@NotNull Tone tone, @NotNull String text, @NotNull Node @NotNull ... trailing) {
        Label label = paragraph(text);
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
    public static @NotNull VBox field(@NotNull String label, @NotNull Node control) {
        VBox field = new VBox(label(label, "field-label"), control);

        field.getStyleClass().add("field");

        return field;
    }

    /**
     * Creates a row with the stylesheet's spacing, its children centred vertically.
     *
     * @param spacing  the spacing between the children
     * @param children the children
     * @return the created row
     */
    public static @NotNull HBox row(@NotNull Spacing spacing, @NotNull Node @NotNull ... children) {
        HBox row = new HBox(children);

        row.getStyleClass().add(spacing.getStyleClass());
        row.setAlignment(Pos.CENTER_LEFT);

        return row;
    }

    /**
     * Creates a column with the stylesheet's spacing.
     *
     * @param spacing  the spacing between the children
     * @param children the children
     * @return the created column
     */
    public static @NotNull VBox column(@NotNull Spacing spacing, @NotNull Node @NotNull ... children) {
        VBox column = new VBox(children);

        column.getStyleClass().add(spacing.getStyleClass());

        return column;
    }

    /**
     * Creates a row of a label and a text after it: the label keeps its width, the text takes the rest of the row and wraps, such as a
     * sender's name before a chat line.
     *
     * @param spacing the spacing between the label and the text
     * @param label   the label
     * @param text    the text, a paragraph ({@link #paragraph})
     * @return the created row, its children aligned at the top
     */
    public static @NotNull HBox textRow(@NotNull Spacing spacing, @NotNull Label label, @NotNull Label text) {
        HBox row = row(spacing, label, text);

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
    public static @NotNull Region growingSpacer() {
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
    public static @NotNull ImageView imageView(@NotNull Image image, double size) {
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
    public static @NotNull StackPane thumbnail(@Nullable Image image, double width, double height) {
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
            frame.getChildren().add(label("?", "text-title", "text-muted"));
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
    public static @NotNull VBox panel(@NotNull String title, @NotNull Node body, @NotNull Node @NotNull ... headerTrailing) {
        return panel(label(title), body, headerTrailing);
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
    public static @NotNull VBox panel(@NotNull Label title, @NotNull Node body, @NotNull Node @NotNull ... headerTrailing) {
        HBox header = new HBox(title, growingSpacer());
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
     * Creates a raised metal panel with a title header, a body that grows to fill the panel and a footer strip ({@link #panelFooter}).
     *
     * @param title          the title, shown in capitals by the stylesheet
     * @param body           the body
     * @param footer         the footer
     * @param headerTrailing the nodes at the right end of the header
     * @return the created panel
     */
    public static @NotNull VBox panelWithFooter(@NotNull String title, @NotNull Node body, @NotNull HBox footer,
                                                @NotNull Node @NotNull ... headerTrailing) {
        return panelWithFooter(label(title), body, footer, headerTrailing);
    }

    /**
     * Creates a raised metal panel with a title header whose title label the caller keeps to change its text, a body that grows to fill the
     * panel and a footer strip ({@link #panelFooter}).
     *
     * @param title          the title label, shown in capitals by the stylesheet
     * @param body           the body
     * @param footer         the footer
     * @param headerTrailing the nodes at the right end of the header
     * @return the created panel
     */
    public static @NotNull VBox panelWithFooter(@NotNull Label title, @NotNull Node body, @NotNull HBox footer,
                                                @NotNull Node @NotNull ... headerTrailing) {
        VBox panel = panel(title, body, headerTrailing);

        panel.getChildren().add(footer);

        return panel;
    }

    /**
     * Creates a raised metal panel without a header.
     *
     * @param body the body
     * @return the created panel
     */
    public static @NotNull VBox panel(@NotNull Node body) {
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
    public static @NotNull VBox panelBody(@NotNull Node @NotNull ... children) {
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
    public static @NotNull VBox flushPanelBody(@NotNull Node @NotNull ... children) {
        VBox body = panelBody(children);

        body.getStyleClass().add("flush");

        return body;
    }

    /**
     * Creates the footer strip of a panel, usually holding the panel's actions.
     *
     * @param children the children
     * @return the created footer
     */
    public static @NotNull HBox panelFooter(@NotNull Node @NotNull ... children) {
        HBox footer = new HBox(children);

        footer.getStyleClass().add("panel-footer");

        return footer;
    }

    /**
     * Creates the red panel of a failure report or another blocking error, holding its children in a column.
     *
     * @param children the children, such as a headline and the details
     * @return the created panel
     */
    public static @NotNull VBox dangerPanel(@NotNull Node @NotNull ... children) {
        VBox panel = new VBox(children);

        panel.getStyleClass().add("panel-danger");

        return panel;
    }

    /**
     * Creates a menu: a panel of flat entries ({@link #menuButton}) under a header, such as a {@link ListItem} of the player the menu is about.
     *
     * @param header  the header
     * @param entries the entries and the separators between their groups
     * @return the created menu
     */
    public static @NotNull VBox menuPanel(@NotNull Region header, @NotNull Node @NotNull ... entries) {
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
    public static @NotNull TextArea codeArea(@NotNull String text, @NotNull String styleClass) {
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
    public static @NotNull TextArea textArea(@NotNull String text) {
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
    public static @NotNull GridPane metaGrid() {
        GridPane grid = new GridPane();

        grid.getStyleClass().addAll("panel-sunken", "spacing-s");

        return grid;
    }

    /**
     * Adds a row of text to a metadata grid ({@link #metaGrid}); a long value wraps.
     *
     * @param grid  the grid
     * @param key   the key, in the faint colour
     * @param value the value
     */
    public static void addMetaRow(@NotNull GridPane grid, @NotNull String key, @NotNull String value) {
        Label valueLabel = label(value, "meta-value");

        valueLabel.setWrapText(true);
        addMetaRow(grid, key, valueLabel);
    }

    /**
     * Adds a row to a metadata grid ({@link #metaGrid}) under its last row.
     *
     * @param grid  the grid
     * @param key   the key, in the faint colour
     * @param value the value node, such as a link
     */
    public static void addMetaRow(@NotNull GridPane grid, @NotNull String key, @NotNull Node value) {
        Label keyLabel = label(key, "meta-key");

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
    public static <T> @NotNull ListView<T> listView(@NotNull ListItemSize size) {
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
    public static <T> @NotNull ListView<T> sunkenListView(@NotNull ListItemSize size) {
        ListView<T> list = listView(size);

        list.getStyleClass().add(SUNKEN_LIST_STYLE_CLASS);

        return list;
    }

    /**
     * Creates a list view of log lines that run edge to edge in a flush panel body ({@link #flushPanelBody}): the lines sit close together and
     * are not selected, the first line ({@link #markFirstRow}) starts below the header by the room left of the lines, and the room below the
     * last line is the same.
     *
     * @param <T> the item type
     * @return the created list view
     */
    public static <T> @NotNull ListView<T> logListView() {
        ListView<T> list = new ListView<>();

        list.getStyleClass().add(LOG_LIST_STYLE_CLASS);

        return list;
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
    public static <T> @NotNull ComboBox<T> comboBox(@NotNull ControlWidth width) {
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
