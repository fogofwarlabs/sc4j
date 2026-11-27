package org.fogofwarlabs.sc4j.translate;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Translates texts for display. The answer comes through a future that completes on a worker thread; callers move to their own UI thread.
 * The future never fails for model reasons: a missing model, a model failure or a closed connection gives
 * {@link TranslationStatus#UNAVAILABLE}.
 */
public interface Translator {
    /**
     * Returns the translator that answers every request with {@link TranslationStatus#UNAVAILABLE}.
     *
     * @return the shared unavailable translator
     */
    static @NotNull Translator unavailable() {
        return UnavailableTranslator.INSTANCE;
    }

    /**
     * Translates a text.
     *
     * @param request the request
     * @return the future of the translation, completed on a worker thread or at once
     */
    @NotNull CompletableFuture<@NotNull Translation> translate(@NotNull TranslationRequest request);
}
