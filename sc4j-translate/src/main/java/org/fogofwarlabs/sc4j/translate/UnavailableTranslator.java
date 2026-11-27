package org.fogofwarlabs.sc4j.translate;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Answers every request at once with {@link TranslationStatus#UNAVAILABLE} and the original text.
 */
final class UnavailableTranslator implements Translator {
    static final @NotNull UnavailableTranslator INSTANCE = new UnavailableTranslator();

    private UnavailableTranslator() {
    }

    /**
     * Translates a text.
     *
     * @param request the request
     * @return the future of the translation, completed on a worker thread or at once
     */
    @Override
    public @NotNull CompletableFuture<@NotNull Translation> translate(@NotNull TranslationRequest request) {
        return CompletableFuture.completedFuture(Translation.untranslated(request.getText(), null, TranslationStatus.UNAVAILABLE));
    }
}
