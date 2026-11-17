package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes one item of a list, the shared look of every list entry: a status marker or a thumbnail, the title over its captions, and a badge
 * at the right end. A list shows its items with {@link ListItemCell}; the item's size follows the list ({@link ComponentUtils#createListView}).
 */
public final class ListItem {
    /**
     * The size of a thumbnail ({@link #setThumbnail}).
     */
    public static final @NotNull IconSize THUMBNAIL_SIZE = IconSize.M;

    private static final @NotNull String ITEM_STYLE_CLASS = "list-item";
    private static final @NotNull String TEXT_STYLE_CLASS = "list-item-text";
    private static final @NotNull String TITLE_STYLE_CLASS = "list-title";
    private static final @NotNull String CAPTION_STYLE_CLASS = "text-caption";
    private static final @NotNull String MONO_STYLE_CLASS = "text-mono";

    private final @NotNull Label title;
    private final @NotNull VBox text;

    private @Nullable Node leading;
    private @Nullable Label badge;

    /**
     * Creates a {@link ListItem} instance with a title and nothing else.
     *
     * @param title the title
     */
    public ListItem(@NotNull String title) {
        this.title = ComponentUtils.createLabel(title, TITLE_STYLE_CLASS);
        this.text  = new VBox(this.title);

        this.text.getStyleClass().add(TEXT_STYLE_CLASS);

        this.leading = null;
        this.badge   = null;
    }

    /**
     * Sets the status marker before the title.
     *
     * @param tone the colour role of the marker
     * @return this instance for chaining
     */
    public @NotNull ListItem setMarker(@NotNull Tone tone) {
        this.leading = ComponentUtils.createStatusMarker(tone);

        return this;
    }

    /**
     * Sets the thumbnail before the title ({@link ComponentUtils#createThumbnail}), {@link #THUMBNAIL_SIZE} wide and high.
     *
     * @param image the image, or {@code null} for the placeholder
     * @return this instance for chaining
     */
    public @NotNull ListItem setThumbnail(@Nullable Image image) {
        this.leading = ComponentUtils.createThumbnail(image, THUMBNAIL_SIZE.getSize(), THUMBNAIL_SIZE.getSize());

        return this;
    }

    /**
     * Sets the title in the mono font, for addresses and paths.
     *
     * @return this instance for chaining
     */
    public @NotNull ListItem setMonospaced() {
        this.title.getStyleClass().add(MONO_STYLE_CLASS);

        return this;
    }

    /**
     * Adds a caption under the title and the captions added before.
     *
     * @param caption      the caption
     * @param styleClasses the style classes to add to the caption style, such as a text colour
     * @return this instance for chaining
     */
    public @NotNull ListItem addCaption(@NotNull String caption, @NotNull String @NotNull ... styleClasses) {
        Label label = ComponentUtils.createLabel(caption, CAPTION_STYLE_CLASS);

        label.getStyleClass().addAll(styleClasses);
        this.text.getChildren().add(label);

        return this;
    }

    /**
     * Sets the badge at the right end ({@link ComponentUtils#createBadge}).
     *
     * @param badge the badge
     * @return this instance for chaining
     */
    public @NotNull ListItem setBadge(@NotNull Label badge) {
        this.badge = badge;

        return this;
    }

    /**
     * Creates the item's node.
     *
     * @return the created node: the marker or thumbnail, the title and captions, and the badge in a row
     */
    public @NotNull HBox createNode() {
        HBox row = new HBox();

        if (this.leading != null) {
            row.getChildren().add(this.leading);
        }

        row.getChildren().add(this.text);

        if (this.badge != null) {
            row.getChildren().addAll(ComponentUtils.createGrowingSpacer(), this.badge);
        }

        row.getStyleClass().add(ITEM_STYLE_CLASS);

        return row;
    }
}
