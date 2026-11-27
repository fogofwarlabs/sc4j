package org.fogofwarlabs.sc4j.translate.service.sentencepiece;

import org.fogofwarlabs.sc4j.translate.service.TranslationTestResources;
import org.fogofwarlabs.sc4j.translate.service.nllb.NllbPreset;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Tests that the SentencePiece tokenizer gives the reference ids and decoded texts of the NLLB model for texts in many scripts and with
 * full-width forms, combining marks, emoji, unknown characters and runs of spaces, and that its pieces decode as its ids do.
 */
final class SentencePieceTokenizerTest {
    /**
     * The text the reference decodes an unknown piece to.
     */
    private static final @NotNull String UNKNOWN_MARKER = " ⁇ ";

    @Test
    void encode_ids_should_match_the_reference_ids_and_decode_should_match_the_reference_texts() throws IOException {
        SentencePieceTokenizer tokenizer = SentencePieceTokenizer.read(TranslationTestResources.requirePresetFolder(NllbPreset.DISTILLED_600M.getId())
                                                                                               .resolve(NllbPreset.TOKENIZER_FILE));
        List<Map<?, ?>> cases = TranslationTestResources.readFixture(TranslationTestResources.SENTENCEPIECE_FIXTURE);

        for (Map<?, ?> fixture : cases) {
            String text = (String) fixture.get("text");
            List<?> expectedIds = (List<?>) fixture.get("ids");
            int[] ids = tokenizer.encodeIds(text);
            String[] pieces = tokenizer.encode(text);

            Assertions.assertArrayEquals(expectedIds.stream().mapToInt(id -> ((Long) id).intValue()).toArray(), ids, text);
            // The reference decodes unknown pieces to a marker, which this tokenizer leaves out; after a leading unknown piece the reference
            // keeps the space of the next piece.
            Assertions.assertEquals(((String) fixture.get("decoded")).replace(UNKNOWN_MARKER, "").stripLeading(), tokenizer.decodeIds(ids), text);
            Assertions.assertEquals(ids.length, pieces.length, text);
            Assertions.assertEquals(tokenizer.decodeIds(ids), tokenizer.decode(pieces), text);
        }
    }
}
