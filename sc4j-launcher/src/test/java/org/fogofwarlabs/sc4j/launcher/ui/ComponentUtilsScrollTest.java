package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ComponentUtils#scrollToLast}: the last item of a log list of wrapping lines is fully shown also when an added line first
 * makes the list scroll.
 */
final class ComponentUtilsScrollTest {
    private static final double LIST_WIDTH = 240;
    private static final double LIST_HEIGHT = 160;
    private static final int LINE_COUNT = 24;
    private static final String LINE = "a chat line long enough to wrap onto a second line in a narrow list";
    private static final double TOLERANCE = 1;

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
        // Closing the last window would otherwise end the toolkit of this test JVM.
        Platform.setImplicitExit(false);
    }

    private static boolean showsLastItem(@NotNull ListView<String> list) {
        VirtualFlow<?> flow = (VirtualFlow<?>) list.lookup(".virtual-flow");
        IndexedCell<?> cell = flow == null ? null : flow.getLastVisibleCell();

        if (cell == null || cell.getIndex() != list.getItems().size() - 1) {
            return false;
        }

        Bounds cellBounds = cell.localToScene(cell.getBoundsInLocal());
        Bounds flowBounds = flow.localToScene(flow.getBoundsInLocal());

        return cellBounds.getMaxY() <= flowBounds.getMaxY() + TOLERANCE;
    }

    @Test
    void scrollToLast_should_show_the_last_line_when_the_list_first_scrolls() throws Exception {
        ListView<String> list = LauncherFxScenario.run(() -> {
            ListView<String> created = ComponentUtils.createLogListView();
            Stage stage = new Stage();

            created.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(@Nullable String item, boolean empty) {
                    super.updateItem(item, empty);

                    if (empty || item == null) {
                        this.setGraphic(null);

                        return;
                    }

                    Label text = new Label(item);

                    text.setWrapText(true);
                    ComponentUtils.setCellGraphic(this, text);
                }
            });
            stage.setScene(new Scene(new StackPane(created), LIST_WIDTH, LIST_HEIGHT));
            stage.show();

            return created;
        });

        for (int index = 0; index < LINE_COUNT; index++) {
            int number = index;

            LauncherFxScenario.run(() -> {
                list.getItems().add(number + " " + LINE);
                ComponentUtils.scrollToLast(list);

                return null;
            });
            LauncherFxScenario.await(() -> showsLastItem(list), "line " + number + " shown at the end of the list");
        }

        LauncherFxScenario.run(() -> {
            list.getScene().getWindow().hide();

            return null;
        });
    }
}
