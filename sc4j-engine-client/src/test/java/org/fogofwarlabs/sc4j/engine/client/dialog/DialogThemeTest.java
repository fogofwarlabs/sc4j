package org.fogofwarlabs.sc4j.engine.client.dialog;

import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.skin.BoxStyle;
import org.fogofwarlabs.sc4j.engine.client.skin.ButtonStyle;
import org.fogofwarlabs.sc4j.engine.client.skin.ColorBoxStyle;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.engine.resource.dialog.ControlKind;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.TextAlignment;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests the skin boxes of the dialog theme: the default theme has none, each {@code with} copy takes the given box and keeps the font, and a
 * selected button takes no presses.
 */
final class DialogThemeTest {
    private static final int SIZE = 20;
    private static final int LINE_HEIGHT = 10;
    private static final int PANEL_COLOR = 0x00AA00FF;
    private static final int NORMAL_COLOR = 0xAA0000FF;
    private static final int HOVERED_COLOR = 0xBB0000FF;
    private static final int PRESSED_COLOR = 0xCC0000FF;
    private static final int DISABLED_COLOR = 0x555555FF;
    private static final int FIELD_COLOR = 0x0000AAFF;
    private static final int SELECTION_BOX_COLOR = 0x00DDDDFF;
    private static final float FIELD_PADDING = 4;

    /**
     * Measures nothing and draws nothing.
     */
    private static final class EmptyFont implements Font {
        @Override
        public int getLineHeight() {
            return LINE_HEIGHT;
        }

        @Override
        public @NotNull TextStyle getDefaultStyle() {
            return TextStyle.DEFAULT;
        }

        @Override
        public float measure(@NotNull CharSequence text, @NotNull TextStyle style) {
            return 0;
        }

        @Override
        public float draw(@NotNull Canvas canvas, @NotNull CharSequence text, float x, float y, int color, @NotNull TextStyle style) {
            return x;
        }
    }

    @Test
    void with_styles_should_take_the_boxes_and_keep_the_font() {
        DialogTheme colors = DialogTheme.createDefault(new EmptyFont());
        BoxStyle panelStyle = new ColorBoxStyle(PANEL_COLOR);
        ButtonStyle buttonStyle = new ButtonStyle(new ColorBoxStyle(NORMAL_COLOR), new ColorBoxStyle(HOVERED_COLOR), new ColorBoxStyle(PRESSED_COLOR),
                                                  new ColorBoxStyle(DISABLED_COLOR));
        BoxStyle fieldStyle = new ColorBoxStyle(FIELD_COLOR);
        BoxStyle selectionStyle = new ColorBoxStyle(SELECTION_BOX_COLOR);
        DialogTheme skinned = colors.withPanelStyle(panelStyle)
                                    .withButtonStyle(buttonStyle)
                                    .withFieldStyle(fieldStyle, FIELD_PADDING)
                                    .withSelectionStyle(selectionStyle);

        Assertions.assertNull(colors.getPanelStyle());
        Assertions.assertNull(colors.getButtonStyle());
        Assertions.assertNull(colors.getFieldStyle());
        Assertions.assertEquals(0, colors.getFieldPadding());
        Assertions.assertNull(colors.getSelectionStyle());
        Assertions.assertSame(panelStyle, skinned.getPanelStyle());
        Assertions.assertSame(buttonStyle, skinned.getButtonStyle());
        Assertions.assertSame(fieldStyle, skinned.getFieldStyle());
        Assertions.assertEquals(FIELD_PADDING, skinned.getFieldPadding());
        Assertions.assertSame(selectionStyle, skinned.getSelectionStyle());
        Assertions.assertSame(colors.getFont(), skinned.getFont());
    }

    @Test
    void set_selected_should_make_a_button_take_no_presses() {
        Button tab = new Button(new DialogControl(1, ControlKind.BUTTON, 0, 0, SIZE, SIZE, "text", TextAlignment.LEFT, null, Map.of(), List.of()));

        tab.setSelected(true);

        Assertions.assertFalse(tab.isPressable());
    }
}
