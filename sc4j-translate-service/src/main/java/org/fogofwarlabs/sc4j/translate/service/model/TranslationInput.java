package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Holds one text of a {@link TranslationModel} batch with the language it is in and the language to translate it into.
 */
public final class TranslationInput {
    private final @NotNull String text;
    private final @NotNull Locale source;
    private final @NotNull Locale target;

    /**
     * Creates a {@link TranslationInput} instance.
     *
     * @param text   the text
     * @param source the language of the text
     * @param target the language to translate into
     */
    public TranslationInput(@NotNull String text, @NotNull Locale source, @NotNull Locale target) {
        this.text   = text;
        this.source = source;
        this.target = target;
    }

    /**
     * @return the text
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the language of the text
     */
    public @NotNull Locale getSource() {
        return this.source;
    }

    /**
     * @return the language to translate into
     */
    public @NotNull Locale getTarget() {
        return this.target;
    }
}
