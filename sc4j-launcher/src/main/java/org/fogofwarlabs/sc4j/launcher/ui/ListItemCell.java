package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.scene.control.ListCell;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Shows the items of a list as {@link ListItem}s; the list gives the function that describes an item.
 *
 * @param <T> the item type
 */
public final class ListItemCell<T> extends ListCell<T> {
    private final @NotNull Function<? super @NotNull T, @NotNull ListItem> describe;

    /**
     * Creates a {@link ListItemCell} instance.
     *
     * @param describe describes an item of the list
     */
    public ListItemCell(@NotNull Function<? super @NotNull T, @NotNull ListItem> describe) {
        this.describe = describe;
    }

    /**
     * Updates the item associated with this cell.
     *
     * @param item  the new item for the cell
     * @param empty whether or not this cell represents data from the list
     */
    @Override
    protected void updateItem(@Nullable T item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            this.setText(null);
            this.setGraphic(null);

            return;
        }

        ComponentUtils.setCellGraphic(this, this.describe.apply(item).createNode());
    }
}
