package org.fogofwarlabs.sc4j.engine.client.dialog;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.function.ToDoubleFunction;

/**
 * Tests the pointer and word boundaries of text selections.
 */
final class TextBoundaryUtilsTest {
    /**
     * The fixed advance of every code point in pixels.
     */
    private static final float ADVANCE = 10;
    private static final @NotNull ToDoubleFunction<String> MEASURE = text -> text.codePointCount(0, text.length()) * ADVANCE;
    /**
     * A character outside the Basic Multilingual Plane: two UTF-16 units, one code point.
     */
    private static final @NotNull String SUPPLEMENTARY = "😀";

    @Test
    void find_index_at_should_snap_to_the_nearest_character_edge() {
        Assertions.assertEquals(0, TextBoundaryUtils.findIndexAt("abc", -5, MEASURE));
        Assertions.assertEquals(0, TextBoundaryUtils.findIndexAt("abc", 4, MEASURE));
        Assertions.assertEquals(1, TextBoundaryUtils.findIndexAt("abc", 5, MEASURE));
        Assertions.assertEquals(1, TextBoundaryUtils.findIndexAt("abc", 14, MEASURE));
        Assertions.assertEquals(3, TextBoundaryUtils.findIndexAt("abc", 26, MEASURE));
        Assertions.assertEquals(3, TextBoundaryUtils.findIndexAt("abc", 500, MEASURE));
        Assertions.assertEquals(0, TextBoundaryUtils.findIndexAt("", 20, MEASURE));
    }

    @Test
    void find_index_at_should_step_over_whole_code_points() {
        String text = "a" + SUPPLEMENTARY + "b";

        Assertions.assertEquals(1, TextBoundaryUtils.findIndexAt(text, 12, MEASURE));
        Assertions.assertEquals(3, TextBoundaryUtils.findIndexAt(text, 16, MEASURE));
    }

    @Test
    void word_bounds_group_letters_spaces_and_other_characters() {
        String text = "gg  wp!! 안녕";

        Assertions.assertEquals(0, TextBoundaryUtils.findWordStart(text, 1));
        Assertions.assertEquals(2, TextBoundaryUtils.findWordEnd(text, 1));
        Assertions.assertEquals(2, TextBoundaryUtils.findWordStart(text, 3));
        Assertions.assertEquals(4, TextBoundaryUtils.findWordEnd(text, 3));
        Assertions.assertEquals(6, TextBoundaryUtils.findWordStart(text, 7));
        Assertions.assertEquals(8, TextBoundaryUtils.findWordEnd(text, 7));
        Assertions.assertEquals(9, TextBoundaryUtils.findWordStart(text, text.length()));
        Assertions.assertEquals(text.length(), TextBoundaryUtils.findWordEnd(text, text.length()));
        Assertions.assertEquals(0, TextBoundaryUtils.findWordStart("", 0));
        Assertions.assertEquals(0, TextBoundaryUtils.findWordEnd("", 0));
    }
}
