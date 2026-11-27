package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Identifies the language of a text. It may hold a mapped model file until it is closed; it is used by one thread at a time.
 */
public interface LanguageIdentifier extends AutoCloseable {
    /**
     * Identifies the most probable language of a text.
     *
     * @param text the text, on one line
     * @return the most probable language, or {@code null} when the text gives the model nothing to read
     */
    @Nullable IdentifiedLanguage identify(@NotNull String text);

    /**
     * Releases the model file.
     */
    @Override
    void close();
}
