package org.fogofwarlabs.sc4j.engine.client.console;

import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.image.ImageCanvas;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the default console: typing, Backspace, Enter running commands or chat, Escape, the kept message lines and their fading.
 */
final class ConsoleTest {
    private static final int PAGE_LINES = 4;
    private static final int MAXIMUM_LINES = 3;
    private static final int LINE_HEIGHT = 6;
    private static final int CANVAS_SIZE = 32;
    private static final int TEXT_COLOR = 0xF0E6C8FF;
    private static final int ALPHA_MASK = 0xFF;
    private static final long SHOWN_MILLISECONDS = 8000;
    private static final long FADE_MILLISECONDS = 2000;

    /**
     * Keeps every drawn line with its colour.
     */
    private static final class RecordingFont implements Font {
        private final @NotNull List<@NotNull String> lines;
        private final @NotNull List<@NotNull Integer> colors;

        private RecordingFont() {
            this.lines  = new ArrayList<>();
            this.colors = new ArrayList<>();
        }

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
            this.lines.add(text.toString());
            this.colors.add(color);

            return x;
        }
    }

    private static @NotNull RecordingFont draw(@NotNull Console console) {
        ImageCanvas canvas = new ImageCanvas(CANVAS_SIZE, CANVAS_SIZE);
        RecordingFont font = new RecordingFont();

        canvas.begin(CANVAS_SIZE, CANVAS_SIZE);
        console.draw(canvas, font, 0, 0, TEXT_COLOR);
        canvas.end();

        return font;
    }

    private static void type(@NotNull Console console, @NotNull String text) {
        text.codePoints().forEach(codePoint -> console.handleInput(InputEvent.createCharacterTyped(codePoint)));
    }

    private static boolean press(@NotNull Console console, @NotNull Key key) {
        return console.handleInput(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, key, 0));
    }

    @Test
    void draw_should_fade_a_message_line_out_after_its_shown_time() {
        Console console = new Console(new ConsoleCommandRegistry(PAGE_LINES), line -> {
        }, MAXIMUM_LINES);

        console.setLineTimes(SHOWN_MILLISECONDS, FADE_MILLISECONDS);
        console.addLine("old");
        console.update(SHOWN_MILLISECONDS - 1);
        console.addLine("new");

        Assertions.assertEquals(List.of("old", "new"), draw(console).lines, "both lines show within their shown time");
        Assertions.assertEquals(List.of(TEXT_COLOR, TEXT_COLOR), draw(console).colors, "fully");

        console.update(FADE_MILLISECONDS / 2);

        RecordingFont fading = draw(console);

        Assertions.assertEquals(List.of("old", "new"), fading.lines);
        Assertions.assertTrue((fading.colors.getFirst() & ALPHA_MASK) < (TEXT_COLOR & ALPHA_MASK), "the old line fades");
        Assertions.assertEquals(TEXT_COLOR & ~ALPHA_MASK, fading.colors.getFirst() & ~ALPHA_MASK, "in its own colour");
        Assertions.assertEquals(TEXT_COLOR, fading.colors.get(1), "the new line still shows fully");

        console.update(FADE_MILLISECONDS);

        Assertions.assertEquals(List.of("new"), draw(console).lines, "the faded line is no longer drawn");
        Assertions.assertEquals(List.of("old", "new"), console.getLines(), "but kept");

        console.open();

        Assertions.assertEquals(List.of("old", "new", Console.PROMPT), draw(console).lines, "the open console shows every kept line");
    }

    @Test
    void handle_input_should_edit_and_submit_the_typed_line() {
        ConsoleCommandRegistry registry = new ConsoleCommandRegistry(PAGE_LINES);
        List<String> chat = new ArrayList<>();
        Console console = new Console(registry, chat::add, MAXIMUM_LINES);

        registry.register(ConsoleCommand.of("/echo", "<words>", "repeat the words")
                                        .otherwise("<words>", "repeat the words", arguments -> String.join(" ", arguments)));

        Assertions.assertFalse(console.handleInput(InputEvent.createCharacterTyped('x')), "a closed console takes nothing");

        console.open();
        type(console, "/echo hi!");
        press(console, Key.BACKSPACE);

        Assertions.assertEquals("/echo hi", console.getInput());
        Assertions.assertFalse(press(console, Key.F1));
        Assertions.assertTrue(press(console, Key.ENTER));
        Assertions.assertFalse(console.isOpen());
        Assertions.assertEquals(List.of("hi"), console.getLines());

        console.open();
        type(console, "gg");
        press(console, Key.KEYPAD_ENTER);
        console.open();
        type(console, "dropped");
        press(console, Key.ESCAPE);

        Assertions.assertEquals(List.of("gg"), chat);
        Assertions.assertEquals("", console.getInput());
    }

    @Test
    void add_line_should_keep_the_latest_lines() {
        Console console = new Console(new ConsoleCommandRegistry(PAGE_LINES), line -> {
        }, MAXIMUM_LINES);

        for (int index = 0; index < MAXIMUM_LINES + 2; index++) {
            console.addLine("line " + index);
        }

        Assertions.assertEquals(List.of("line 2", "line 3", "line 4"), console.getLines());
        Assertions.assertThrows(IllegalArgumentException.class, () -> new Console(new ConsoleCommandRegistry(PAGE_LINES), line -> {
        }, 0));
    }
}
