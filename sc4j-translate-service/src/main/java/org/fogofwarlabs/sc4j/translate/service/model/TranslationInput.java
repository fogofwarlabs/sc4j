package org.fogofwarlabs.sc4j.translate.service.model;

import org.fogofwarlabs.sc4j.translate.TranslationContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Holds one text of a {@link TranslationModel} batch with the language it is in, the language to translate it into and the context of the
 * request it comes from.
 */
public final class TranslationInput {
    private final @NotNull String text;
    private final @NotNull Locale source;
    private final @NotNull Locale target;
    private final @Nullable TranslationContext context;

    /**
     * Creates a {@link TranslationInput} instance without context.
     *
     * @param text   the text
     * @param source the language of the text
     * @param target the language to translate into
     */
    public TranslationInput(@NotNull String text, @NotNull Locale source, @NotNull Locale target) {
        this(text, source, target, null);
    }

    /**
     * Creates a {@link TranslationInput} instance.
     *
     * @param text    the text
     * @param source  the language of the text
     * @param target  the language to translate into
     * @param context the context of the request, or {@code null} for none
     */
    public TranslationInput(@NotNull String text, @NotNull Locale source, @NotNull Locale target, @Nullable TranslationContext context) {
        this.text    = text;
        this.source  = source;
        this.target  = target;
        this.context = context;
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

    /**
     * @return the context of the request, or {@code null} for none
     */
    public @Nullable TranslationContext getContext() {
        return this.context;
    }
}
