package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the labels of {@link ComponentUtils}: a label whose text is cut short shows its whole text on hover, a label that fits does not.
 */
final class ComponentUtilsLabelTest {
    private static final double WIDTH = 80;
    private static final String SHORT_TEXT = "SC4J";
    private static final String LONG_TEXT = "A name far too long for the narrow column it sits in";

    @BeforeAll
    static void startToolkit() {
        LauncherFxScenario.startToolkit();
    }

    private static @Nullable String layOut(@NotNull Label label) {
        HBox box = new HBox(label);

        box.setMinWidth(WIDTH);
        box.setPrefWidth(WIDTH);
        box.setMaxWidth(WIDTH);
        label.setMinWidth(0);
        new Scene(box);
        box.applyCss();
        box.resize(WIDTH, box.prefHeight(WIDTH));
        box.layout();

        Tooltip tooltip = label.getTooltip();

        return tooltip == null ? null : tooltip.getText();
    }

    @Test
    void label_should_show_its_whole_text_only_while_it_is_cut() throws Exception {
        String[] tooltips = LauncherFxScenario.run(() -> {
            Label label = ComponentUtils.createLabel(LONG_TEXT);
            String cut = layOut(label);

            label.setText(SHORT_TEXT);

            return new String[]{cut, layOut(label)};
        });

        Assertions.assertEquals(LONG_TEXT, tooltips[0], "a cut label shows its whole text");
        Assertions.assertNull(tooltips[1], "a label that fits has no tooltip");
    }
}
