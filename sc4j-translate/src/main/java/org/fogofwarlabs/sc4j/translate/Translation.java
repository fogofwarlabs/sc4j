package org.fogofwarlabs.sc4j.translate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Holds the answer to a translation request: the original text, the translated text, the source language and the status. The translated
 * text equals the original unless the status is {@link TranslationStatus#TRANSLATED}.
 */
public final class Translation {
    /**
     * Creates a {@link Translation} instance that leaves the text as it is.
     *
     * @param originalText the text of the request
     * @param source       the source language, or {@code null} when it was not decided
     * @param status       the outcome, any but {@link TranslationStatus#TRANSLATED}
     * @return the created {@link Translation} instance
     */
    public static @NotNull Translation untranslated(@NotNull String originalText, @Nullable Locale source, @NotNull TranslationStatus status) {
        return new Translation(originalText, originalText, source, status);
    }


    private final @NotNull String originalText;
    private final @NotNull String translatedText;
    private final @Nullable Locale source;
    private final @NotNull TranslationStatus status;

    /**
     * Creates a {@link Translation} instance.
     *
     * @param originalText   the text of the request
     * @param translatedText the translated text, or the original text when nothing was translated
     * @param source         the source language, or {@code null} when it was not decided
     * @param status         the outcome
     */
    public Translation(@NotNull String originalText, @NotNull String translatedText, @Nullable Locale source, @NotNull TranslationStatus status) {
        this.originalText   = originalText;
        this.translatedText = translatedText;
        this.source         = source;
        this.status         = status;
    }

    /**
     * @return the text of the request
     */
    public @NotNull String getOriginalText() {
        return this.originalText;
    }

    /**
     * @return the translated text, or the original text when nothing was translated
     */
    public @NotNull String getTranslatedText() {
        return this.translatedText;
    }

    /**
     * @return the source language, or {@code null} when it was not decided
     */
    public @Nullable Locale getSource() {
        return this.source;
    }

    /**
     * @return the outcome
     */
    public @NotNull TranslationStatus getStatus() {
        return this.status;
    }

    /**
     * @return {@code true} if the text was translated, {@code false} otherwise
     */
    public boolean isTranslated() {
        return this.status == TranslationStatus.TRANSLATED;
    }
}
