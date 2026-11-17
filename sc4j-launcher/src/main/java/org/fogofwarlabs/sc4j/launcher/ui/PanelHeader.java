package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Lays out the header of a panel: the title on the left and the trailing controls at the right end, centred in the header's height. The
 * header keeps its preferred height from the stylesheet whatever its controls and their state. The room right of the last shown trailing
 * control equals the room above and below it, whatever its height.
 */
final class PanelHeader extends HBox {
    private static final @NotNull String HEADER_STYLE_CLASS = "panel-header";
    private static final @NotNull String TITLE_STYLE_CLASS = "panel-title";
    private static final int LEADING_CHILDREN = 2;

    private static double getRightMargin(@NotNull Node child) {
        Insets margin = HBox.getMargin(child);

        return margin == null ? 0 : margin.getRight();
    }

    /**
     * Creates a {@link PanelHeader} instance.
     *
     * @param title    the title label
     * @param trailing the controls at the right end
     */
    PanelHeader(@NotNull Label title, @NotNull Node @NotNull ... trailing) {
        super(title, ComponentUtils.createGrowingSpacer());

        title.getStyleClass().add(TITLE_STYLE_CLASS);
        this.getStyleClass().add(HEADER_STYLE_CLASS);
        this.setMinHeight(USE_PREF_SIZE);
        this.setMaxHeight(USE_PREF_SIZE);
        this.getChildren().addAll(trailing);
    }

    /**
     * Lays out the children, then sets the right margin of the last shown trailing control so that its room on the right equals its room
     * above and below, and lays them out again if the margin changed.
     */
    @Override
    protected void layoutChildren() {
        super.layoutChildren();

        Node last = this.findLastTrailing();
        boolean changed = false;

        for (Node child : this.getTrailing()) {
            double right = 0;

            if (child == last) {
                right = this.snapSpaceX(Math.max(0, (this.getHeight() - child.getLayoutBounds().getHeight()) / 2 - this.snappedRightInset()));
            }

            if (getRightMargin(child) != right) {
                HBox.setMargin(child, right == 0 ? null : new Insets(0, right, 0, 0));
                changed = true;
            }
        }

        if (changed) {
            super.layoutChildren();
        }
    }

    private @Nullable Node findLastTrailing() {
        List<Node> trailing = this.getTrailing();

        for (int index = trailing.size() - 1; index >= 0; index--) {
            Node child = trailing.get(index);

            if (child.isManaged()) {
                return child;
            }
        }

        return null;
    }

    private @NotNull List<Node> getTrailing() {
        List<Node> children = this.getChildren();

        return children.subList(Math.min(LEADING_CHILDREN, children.size()), children.size());
    }
}
