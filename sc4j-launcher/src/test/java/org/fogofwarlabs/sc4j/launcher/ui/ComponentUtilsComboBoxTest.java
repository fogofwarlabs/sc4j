package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the drop-down list width of the combo boxes of {@link ComponentUtils}.
 */
final class ComponentUtilsComboBoxTest {
    private static final double TOLERANCE = 0.5;

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
        // Closing the last window would otherwise end the toolkit of this test JVM.
        Platform.setImplicitExit(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "an item much longer than the combo box is wide, whose cell would widen the list"})
    void comboBox_should_open_a_list_as_wide_as_the_combo_box(@NotNull String item) throws Exception {
        double[] widths = LauncherFxScenario.run(() -> {
            ComboBox<String> comboBox = ComponentUtils.comboBox(ControlWidth.M);
            Stage stage = new Stage();

            comboBox.getItems().setAll("b", item);
            stage.setScene(new Scene(new StackPane(comboBox), 600, 400));
            stage.show();
            comboBox.show();

            Region list = (Region) ((ComboBoxListViewSkin<?>) comboBox.getSkin()).getPopupContent();
            double[] result = {comboBox.getWidth(), list.getWidth()};

            comboBox.hide();
            stage.close();

            return result;
        });

        Assertions.assertEquals(ControlWidth.M.getWidth(), widths[0], TOLERANCE);
        Assertions.assertEquals(widths[0], widths[1], TOLERANCE);
    }
}
