package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

/**
 * Holds the language a {@link LanguageIdentifier} found for a text: the identifier's code of the language and its probability.
 */
public final class IdentifiedLanguage {
    private final @NotNull String code;
    private final float probability;

    /**
     * Creates a {@link IdentifiedLanguage} instance.
     *
     * @param code        the identifier's code of the language, such as {@code kor_Hang}
     * @param probability the probability of the language, from {@code 0} to {@code 1}
     */
    public IdentifiedLanguage(@NotNull String code, float probability) {
        this.code        = code;
        this.probability = probability;
    }

    /**
     * @return the identifier's code of the language, such as {@code kor_Hang}
     */
    public @NotNull String getCode() {
        return this.code;
    }

    /**
     * @return the probability of the language, from {@code 0} to {@code 1}
     */
    public float getProbability() {
        return this.probability;
    }
}
