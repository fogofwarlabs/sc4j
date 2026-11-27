package org.fogofwarlabs.sc4j.translate.service.fasttext;

import org.fogofwarlabs.sc4j.translate.service.TranslationTestResources;
import org.fogofwarlabs.sc4j.translate.service.model.IdentifiedLanguage;
import org.fogofwarlabs.sc4j.translate.service.nllb.NllbPreset;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests that the fastText language identifier gives the reference labels and probabilities of the lid218e model.
 */
final class FastTextLanguageIdentifierTest {
    private static final @NotNull String LABEL_PREFIX = "__label__";
    /**
     * The reference probabilities are the label probabilities plus the {@code 1e-5} that fastText adds before it takes their logarithm.
     */
    private static final double REFERENCE_OFFSET = 1e-5;
    private static final double TOLERANCE = 1e-4;

    @Test
    void predict_should_match_the_reference_labels_and_probabilities() throws IOException {
        List<Map<?, ?>> cases = TranslationTestResources.readFixture(TranslationTestResources.FASTTEXT_FIXTURE);

        try (FastTextLanguageIdentifier identifier = FastTextLanguageIdentifier.open(TranslationTestResources.requirePresetFolder(NllbPreset.ID)
                                                                                                             .resolve(NllbPreset.IDENTIFIER_FILE))) {
            for (Map<?, ?> fixture : cases) {
                String text = (String) fixture.get("text");
                List<?> labels = (List<?>) fixture.get("labels");
                List<?> probabilities = (List<?>) fixture.get("probabilities");
                List<IdentifiedLanguage> predictions = identifier.predict(text, identifier.getLabels().size());
                Map<String, Float> byCode = new HashMap<>();

                for (IdentifiedLanguage prediction : predictions) {
                    byCode.put(prediction.getCode(), prediction.getProbability());
                }

                Assertions.assertEquals(((String) labels.getFirst()).substring(LABEL_PREFIX.length()), predictions.getFirst().getCode(), text);

                for (int index = 0; index < labels.size(); index++) {
                    String code = ((String) labels.get(index)).substring(LABEL_PREFIX.length());

                    Assertions.assertEquals((Double) probabilities.get(index) - REFERENCE_OFFSET, byCode.get(code), TOLERANCE, text + " " + code);
                }
            }
        }
    }
}
