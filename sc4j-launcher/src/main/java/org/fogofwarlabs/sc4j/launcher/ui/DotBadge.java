package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.beans.InvalidationListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows the dot of a dot badge ({@link ComponentUtils#addDotBadge}) as the graphic of a labeled control. The graphic has no size and lies
 * over the control's text, so the text keeps its place whether the dot shows or not; the dot sits over the top right corner of the text.
 */
final class DotBadge extends Region {
    private static final @NotNull String DOT_STYLE_CLASS = "dot-badge";

    private final @NotNull Region dot;

    /**
     * Creates a {@link DotBadge} instance for the graphic of a labeled control shown over its text ({@link javafx.scene.control.ContentDisplay#CENTER}).
     *
     * @param labeled the labeled control, whose text the dot follows
     */
    DotBadge(@NotNull Labeled labeled) {
        InvalidationListener relayout = observable -> this.requestLayout();

        this.dot = new Region();

        this.dot.getStyleClass().add(DOT_STYLE_CLASS);
        this.dot.setVisible(false);
        this.getChildren().add(this.dot);
        this.setMouseTransparent(true);

        labeled.textProperty().addListener(relayout);
        labeled.fontProperty().addListener(relayout);
        labeled.widthProperty().addListener(relayout);
        labeled.heightProperty().addListener(relayout);
    }

    /**
     * Shows or hides the dot.
     *
     * @param shown whether the dot is shown
     */
    void setDotShown(boolean shown) {
        this.dot.setVisible(shown);
    }

    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computeMinHeight(double width) {
        return 0;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 0;
    }

    @Override
    protected double computePrefHeight(double width) {
        return 0;
    }

    @Override
    protected double computeMaxWidth(double height) {
        return 0;
    }

    @Override
    protected double computeMaxHeight(double width) {
        return 0;
    }

    /**
     * Puts the dot's bottom left corner on the top right corner of the control's text; the stylesheet's translation of the dot moves it
     * from there.
     */
    @Override
    protected void layoutChildren() {
        double width = Math.clamp(this.dot.prefWidth(-1), this.dot.minWidth(-1), this.dot.maxWidth(-1));
        double height = Math.clamp(this.dot.prefHeight(-1), this.dot.minHeight(-1), this.dot.maxHeight(-1));
        Text text = this.findText();
        double x = 0;
        double y = -height;

        if (text != null) {
            Bounds bounds = text.getBoundsInParent();

            x = bounds.getMaxX() - this.getLayoutX();
            y = bounds.getMinY() - this.getLayoutY() - height;
        }

        this.dot.resizeRelocate(this.snapPositionX(x), this.snapPositionY(y), width, height);
    }

    private @Nullable Text findText() {
        Parent parent = this.getParent();

        if (parent == null) {
            return null;
        }

        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Text text) {
                return text;
            }
        }

        return null;
    }
}
