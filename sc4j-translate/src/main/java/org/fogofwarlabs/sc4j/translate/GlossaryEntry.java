package org.fogofwarlabs.sc4j.translate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/**
 * Holds one term of a {@link TranslationContext} glossary: a term of the source text, the language it is translated into and its
 * translation in that language.
 */
public final class GlossaryEntry {
    /**
     * Orders entries by target language tag, source term and target term; the canonical order of a glossary.
     */
    static final @NotNull Comparator<@NotNull GlossaryEntry> CANONICAL_ORDER = Comparator.comparing(GlossaryEntry::getTargetTag)
                                                                                         .thenComparing(GlossaryEntry::getSourceTerm)
                                                                                         .thenComparing(GlossaryEntry::getTargetTerm);

    private final @NotNull String sourceTerm;
    private final @NotNull Locale target;
    private final @NotNull String targetTerm;

    /**
     * Creates a {@link GlossaryEntry} instance.
     *
     * @param sourceTerm the term as it is written in the source text
     * @param target     the language the term is translated into
     * @param targetTerm the translation of the term in the target language
     */
    public GlossaryEntry(@NotNull String sourceTerm, @NotNull Locale target, @NotNull String targetTerm) {
        this.sourceTerm = sourceTerm;
        this.target     = target;
        this.targetTerm = targetTerm;
    }

    /**
     * @return the term as it is written in the source text
     */
    public @NotNull String getSourceTerm() {
        return this.sourceTerm;
    }

    /**
     * @return the language the term is translated into
     */
    public @NotNull Locale getTarget() {
        return this.target;
    }

    /**
     * @return the translation of the term in the target language
     */
    public @NotNull String getTargetTerm() {
        return this.targetTerm;
    }

    private @NotNull String getTargetTag() {
        return this.target.toLanguageTag();
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     *
     * @param other the reference object with which to compare
     * @return {@code true} if the other object is an entry with the same terms and target language, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof GlossaryEntry entry && this.sourceTerm.equals(entry.sourceTerm) && this.target.equals(entry.target) &&
               this.targetTerm.equals(entry.targetTerm);
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.sourceTerm, this.target, this.targetTerm);
    }

    /**
     * Returns a string representation of the object.
     *
     * @return the source term, the target language tag and the target term
     */
    @Override
    public @NotNull String toString() {
        return this.sourceTerm + " -> " + this.target.toLanguageTag() + ": " + this.targetTerm;
    }
}
