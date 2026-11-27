package org.fogofwarlabs.sc4j.translate.service.link;

import org.fogofwarlabs.sc4j.translate.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Tests the payloads of the translation service on the launcher link: requests and translations come back as they were encoded, and a
 * payload of another form is refused.
 */
final class TranslationCodecUtilsTest {
    @Test
    void decode_request_should_return_the_encoded_request() throws IOException {
        TranslationRequest request = new TranslationRequest.Builder("^1안녕 ^2하세요", Locale.forLanguageTag("zh-Hans-CN")).addProtectedSpan(0, 2)
                                                                                                                      .addProtectedSpan(5, 7)
                                                                                                                      .setPriority(TranslationPriority.BATCH)
                                                                                                                      .build();

        TranslationRequest decoded = TranslationCodecUtils.decodeRequest(TranslationCodecUtils.encodeRequest(request));

        Assertions.assertEquals(request.getText(), decoded.getText());
        Assertions.assertEquals(request.getTarget(), decoded.getTarget());
        Assertions.assertEquals(TranslationPriority.BATCH, decoded.getPriority());
        Assertions.assertEquals(2, decoded.getProtectedSpans().size());

        for (int index = 0; index < request.getProtectedSpans().size(); index++) {
            ProtectedSpan expected = request.getProtectedSpans().get(index);
            ProtectedSpan actual = decoded.getProtectedSpans().get(index);

            Assertions.assertEquals(expected.getStart(), actual.getStart());
            Assertions.assertEquals(expected.getEnd(), actual.getEnd());
        }
    }

    @Test
    void decode_translation_should_return_the_encoded_translation() throws IOException {
        Translation translated = new Translation("안녕하세요", "Hello", Locale.KOREAN, TranslationStatus.TRANSLATED);
        Translation skipped = Translation.untranslated(":)", null, TranslationStatus.SKIPPED);

        for (Translation translation : new Translation[]{translated, skipped}) {
            Translation decoded = TranslationCodecUtils.decodeTranslation(TranslationCodecUtils.encodeTranslation(translation));

            Assertions.assertEquals(translation.getOriginalText(), decoded.getOriginalText());
            Assertions.assertEquals(translation.getTranslatedText(), decoded.getTranslatedText());
            Assertions.assertEquals(translation.getSource(), decoded.getSource());
            Assertions.assertEquals(translation.getStatus(), decoded.getStatus());
        }
    }

    @Test
    void decode_should_refuse_payloads_of_another_form() {
        byte[] request = TranslationCodecUtils.encodeRequest(new TranslationRequest.Builder("text", Locale.ENGLISH).build());
        byte[] translation = TranslationCodecUtils.encodeTranslation(Translation.untranslated("text", null, TranslationStatus.UNAVAILABLE));

        Assertions.assertThrows(IOException.class, () -> TranslationCodecUtils.decodeRequest("garbage".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertThrows(IOException.class, () -> TranslationCodecUtils.decodeRequest(Arrays.copyOf(request, request.length - 1)));
        Assertions.assertThrows(IOException.class, () -> TranslationCodecUtils.decodeRequest(Arrays.copyOf(request, request.length + 1)));
        Assertions.assertThrows(IOException.class, () -> TranslationCodecUtils.decodeTranslation(request));
        Assertions.assertThrows(IOException.class, () -> TranslationCodecUtils.decodeRequest(translation));
    }
}
