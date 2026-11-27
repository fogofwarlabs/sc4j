package org.fogofwarlabs.sc4j.translate;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

/**
 * Tests translation contexts: the canonical glossary order, the id of equal and different contents, and building from another context.
 */
final class TranslationContextTest {
    @Test
    void get_id_should_be_equal_for_equal_contents_in_any_order() {
        TranslationContext first = new TranslationContext.Builder().setTextKind("chat message")
                                                                   .setDescription("Short messages between players.")
                                                                   .addGlossaryEntry("tower", Locale.KOREAN, "타워")
                                                                   .addGlossaryEntry("castle", Locale.GERMAN, "Burg")
                                                                   .addGlossaryEntry("tower", Locale.GERMAN, "Turm")
                                                                   .build();
        TranslationContext second = new TranslationContext.Builder().addGlossaryEntry("tower", Locale.GERMAN, "Turm")
                                                                    .addGlossaryEntry("tower", Locale.KOREAN, "타워")
                                                                    .addGlossaryEntry("castle", Locale.GERMAN, "Burg")
                                                                    .addGlossaryEntry("tower", Locale.GERMAN, "Turm")
                                                                    .setDescription("Short messages between players.")
                                                                    .setTextKind("chat message")
                                                                    .build();

        Assertions.assertEquals(first.getId(), second.getId());
        Assertions.assertEquals(first, second);
        Assertions.assertEquals(64, first.getId().length());
        Assertions.assertTrue(first.getId().matches("[0-9a-f]+"));
        Assertions.assertEquals(List.of(new GlossaryEntry("castle", Locale.GERMAN, "Burg"), new GlossaryEntry("tower", Locale.GERMAN, "Turm"),
                                        new GlossaryEntry("tower", Locale.KOREAN, "타워")), first.getGlossary());
    }

    @Test
    void get_id_should_differ_for_different_contents() {
        TranslationContext base = new TranslationContext.Builder().setTextKind("chat message").addGlossaryEntry("tower", Locale.GERMAN, "Turm").build();
        List<TranslationContext> changed = List.of(new TranslationContext.Builder(base).setTextKind("map text").build(),
                                                   new TranslationContext.Builder(base).setDescription("Casual.").build(),
                                                   new TranslationContext.Builder(base).addGlossaryEntry("wall", Locale.GERMAN, "Mauer").build(),
                                                   new TranslationContext.Builder(base).clearGlossary()
                                                                                       .addGlossaryEntry("tower", Locale.FRENCH, "Turm")
                                                                                       .build(),
                                                   new TranslationContext.Builder().setTextKind("chat").setDescription(" message").build(),
                                                   new TranslationContext.Builder().setTextKind("chat message").build());

        for (TranslationContext context : changed) {
            Assertions.assertNotEquals(base.getId(), context.getId(), context.getTextKind() + context.getDescription() + context.getGlossary());
        }

        Assertions.assertNotEquals(changed.get(4).getId(), new TranslationContext.Builder().setTextKind("chat ").setDescription("message").build().getId());
    }

    @Test
    void builder_should_start_from_another_context_and_leave_it_unchanged() {
        TranslationContext base = new TranslationContext.Builder().setTextKind("chat message")
                                                                  .setDescription("Casual.")
                                                                  .addGlossaryEntry("tower", Locale.GERMAN, "Turm")
                                                                  .build();
        TranslationContext extended = new TranslationContext.Builder(base).addGlossaryEntry("wall", Locale.GERMAN, "Mauer").build();

        Assertions.assertEquals("chat message", extended.getTextKind());
        Assertions.assertEquals("Casual.", extended.getDescription());
        Assertions.assertEquals(2, extended.getGlossary().size());
        Assertions.assertEquals(1, base.getGlossary().size());
        Assertions.assertTrue(new TranslationContext.Builder().build().isEmpty());
        Assertions.assertFalse(base.isEmpty());
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TranslationContext.Builder().addGlossaryEntry("", Locale.GERMAN, "Turm"));
    }
}
