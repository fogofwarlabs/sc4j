package org.fogofwarlabs.sc4j.translate.service.nllb;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the languages that the script of a text decides before the model is asked.
 */
final class NllbLanguageIdentifierTest {
    @Test
    void kana_with_han_letters_is_japanese() {
        Assertions.assertEquals(NllbLanguageIdentifier.JAPANESE_CODE, NllbLanguageIdentifier.findScriptCode("おはよう"));
        Assertions.assertEquals(NllbLanguageIdentifier.JAPANESE_CODE, NllbLanguageIdentifier.findScriptCode("今日の天気はとてもいいですね。"));
        Assertions.assertEquals(NllbLanguageIdentifier.JAPANESE_CODE, NllbLanguageIdentifier.findScriptCode("東京大学に行く"));
        Assertions.assertEquals(NllbLanguageIdentifier.JAPANESE_CODE, NllbLanguageIdentifier.findScriptCode("ゲームしようgg"));
    }

    @Test
    void hangul_is_korean() {
        Assertions.assertEquals(NllbLanguageIdentifier.KOREAN_CODE, NllbLanguageIdentifier.findScriptCode("안녕하세요"));
        Assertions.assertEquals(NllbLanguageIdentifier.KOREAN_CODE, NllbLanguageIdentifier.findScriptCode("오늘 gg 한 판 더"));
    }

    @Test
    void han_letters_alone_are_chinese() {
        Assertions.assertEquals(NllbLanguageIdentifier.CHINESE_CODE, NllbLanguageIdentifier.findScriptCode("你好，我们一起玩吧"));
    }

    @Test
    void other_single_language_scripts_are_their_language() {
        Assertions.assertEquals("tha_Thai", NllbLanguageIdentifier.findScriptCode("สวัสดีครับ"));
        Assertions.assertEquals("ell_Grek", NllbLanguageIdentifier.findScriptCode("Καλημέρα"));
    }

    @Test
    void shared_scripts_and_mixed_text_are_left_to_the_model() {
        Assertions.assertNull(NllbLanguageIdentifier.findScriptCode("Good game, well played"));
        Assertions.assertNull(NllbLanguageIdentifier.findScriptCode("Привет"));
        Assertions.assertNull(NllbLanguageIdentifier.findScriptCode("مرحبا"));
        Assertions.assertNull(NllbLanguageIdentifier.findScriptCode("gg well played 안녕"));
        Assertions.assertNull(NllbLanguageIdentifier.findScriptCode("123 !!"));
    }
}
