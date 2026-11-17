package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the slot of {@link ComponentUtils}: it takes room in its column, spacing included, only while it holds content.
 */
final class ComponentUtilsSlotTest {
    private static final double CHILD_HEIGHT = 40;
    private static final double SPACING = 16;
    private static final double TOLERANCE = 0.5;

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static double layOut(@NotNull VBox column) {
        column.applyCss();
        column.layout();

        return column.prefHeight(-1);
    }

    private static @NotNull Region createChild() {
        Region child = new Region();

        child.setPrefHeight(CHILD_HEIGHT);

        return child;
    }

    @Test
    void slot_should_take_no_room_while_empty() throws Exception {
        double[] heights = LauncherFxScenario.run(() -> {
            VBox slot = ComponentUtils.createSlot();
            VBox column = new VBox(SPACING, createChild(), slot, createChild());
            double empty = layOut(column);

            slot.getChildren().add(createChild());

            double filled = layOut(column);

            slot.getChildren().clear();

            return new double[]{empty, filled, layOut(column)};
        });

        Assertions.assertEquals(2 * CHILD_HEIGHT + SPACING, heights[0], TOLERANCE, "an empty slot adds no spacing");
        Assertions.assertEquals(3 * CHILD_HEIGHT + 2 * SPACING, heights[1], TOLERANCE, "a filled slot takes its room and spacing");
        Assertions.assertEquals(heights[0], heights[2], TOLERANCE, "a slot emptied again takes no room");
    }
}
