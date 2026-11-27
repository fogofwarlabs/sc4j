package org.fogofwarlabs.sc4j.translate;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

/**
 * Tests translation requests and the unavailable translator.
 */
final class TranslationRequestTest {
    @Test
    void build_should_sort_the_protected_spans_and_keep_the_defaults() {
        TranslationRequest request = new TranslationRequest.Builder("abcdefgh", Locale.KOREAN).addProtectedSpan(5, 6)
                                                                                              .addProtectedSpan(0, 2)
                                                                                              .build();

        Assertions.assertEquals("abcdefgh", request.getText());
        Assertions.assertEquals(Locale.KOREAN, request.getTarget());
        Assertions.assertEquals(TranslationPriority.CHAT, request.getPriority());
        Assertions.assertEquals(List.of(0, 5), request.getProtectedSpans().stream().map(ProtectedSpan::getStart).toList());
    }

    @Test
    void build_should_refuse_invalid_protected_spans() {
        TranslationRequest.Builder builder = new TranslationRequest.Builder("abcdef", Locale.KOREAN);

        Assertions.assertThrows(IllegalArgumentException.class, () -> builder.addProtectedSpan(2, 2));
        Assertions.assertThrows(IllegalArgumentException.class, () -> builder.addProtectedSpan(-1, 2));
        Assertions.assertThrows(IllegalArgumentException.class, () -> builder.addProtectedSpan(4, 7));

        builder.addProtectedSpan(1, 4).addProtectedSpan(3, 5);

        Assertions.assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void build_should_carry_the_context_into_the_request() {
        TranslationContext context = new TranslationContext.Builder().setTextKind("chat message").build();
        TranslationRequest request = new TranslationRequest.Builder("hello", Locale.KOREAN).setContext(context).build();

        Assertions.assertSame(context, request.getContext());
        Assertions.assertNull(new TranslationRequest.Builder("hello", Locale.KOREAN).build().getContext());
    }

    @Test
    void the_unavailable_translator_should_answer_unavailable_at_once() {
        TranslationRequest request = new TranslationRequest.Builder("hello", Locale.KOREAN).build();
        Translation translation = Translator.unavailable().translate(request).join();

        Assertions.assertEquals(TranslationStatus.UNAVAILABLE, translation.getStatus());
        Assertions.assertEquals("hello", translation.getTranslatedText());
        Assertions.assertNull(translation.getSource());
        Assertions.assertFalse(translation.isTranslated());
    }
}
