package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

/**
 * Converts text to the pieces of a model's vocabulary and back.
 */
public interface TextTokenizer {
    /**
     * Converts text to pieces.
     *
     * @param text the text
     * @return the pieces, empty when the text has nothing but spaces
     */
    @NotNull String @NotNull [] encode(@NotNull String text);

    /**
     * Converts pieces to text; control pieces, the unknown piece and pieces outside the vocabulary give no text.
     *
     * @param pieces the pieces
     * @return the text
     */
    @NotNull String decode(@NotNull String @NotNull [] pieces);
}
