package org.fogofwarlabs.sc4j.translate;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Describes what a text is, for the translators that use it: the kind of text (free text such as {@code chat message} or
 * {@code product description}), a description of the domain and how to translate it, and a glossary of terms with their translations
 * per target language. Every part may be empty. A context is immutable; equal contents have the same {@link #getId() id}.
 * <p>
 * A translator may ignore the context; one that uses it picks the glossary entries of the request's target language whose source term
 * occurs in the text.
 */
public final class TranslationContext {
    private static final @NotNull String DIGEST_ALGORITHM = "SHA-256";

    /**
     * Builds {@link TranslationContext} instances.
     */
    public static final class Builder {
        private @NotNull String textKind;
        private @NotNull String description;
        private final @NotNull Set<@NotNull GlossaryEntry> glossary;

        /**
         * Creates a {@link Builder} instance for an empty context.
         */
        public Builder() {
            this.textKind    = "";
            this.description = "";
            this.glossary    = new LinkedHashSet<>();
        }

        /**
         * Creates a {@link Builder} instance that starts with the contents of a context.
         *
         * @param context the context whose text kind, description and glossary the builder starts with
         */
        public Builder(@NotNull TranslationContext context) {
            this.textKind    = context.textKind;
            this.description = context.description;
            this.glossary    = new LinkedHashSet<>(context.glossary);
        }

        /**
         * Sets the kind of text, such as {@code chat message}.
         *
         * @param textKind the kind of text, or an empty text for none
         * @return this instance for chaining
         */
        public @NotNull Builder setTextKind(@NotNull String textKind) {
            this.textKind = textKind;

            return this;
        }

        /**
         * Sets the description of the domain and of how to translate it.
         *
         * @param description the description, or an empty text for none
         * @return this instance for chaining
         */
        public @NotNull Builder setDescription(@NotNull String description) {
            this.description = description;

            return this;
        }

        /**
         * Adds a glossary entry; an entry equal to one the builder holds is added once.
         *
         * @param sourceTerm the term as it is written in the source text
         * @param target     the language the term is translated into
         * @param targetTerm the translation of the term in the target language
         * @return this instance for chaining
         * @throws IllegalArgumentException if a term is empty
         */
        public @NotNull Builder addGlossaryEntry(@NotNull String sourceTerm, @NotNull Locale target, @NotNull String targetTerm) {
            if (sourceTerm.isEmpty() || targetTerm.isEmpty()) {
                throw Exceptions.newIllegalArgumentException("The glossary entry {0} to {1} has an empty term", sourceTerm, targetTerm);
            }

            this.glossary.add(new GlossaryEntry(sourceTerm, target, targetTerm));

            return this;
        }

        /**
         * Removes every glossary entry.
         *
         * @return this instance for chaining
         */
        public @NotNull Builder clearGlossary() {
            this.glossary.clear();

            return this;
        }

        /**
         * Builds a {@link TranslationContext} instance with the glossary in its canonical order: by target language tag, source term and
         * target term.
         *
         * @return the built {@link TranslationContext} instance
         */
        public @NotNull TranslationContext build() {
            List<GlossaryEntry> entries = new ArrayList<>(this.glossary);

            entries.sort(GlossaryEntry.CANONICAL_ORDER);

            return new TranslationContext(this.textKind, this.description, List.copyOf(entries));
        }
    }


    /**
     * Computes the id of a context: the SHA-256 checksum, in lowercase hexadecimal, of its canonical form. The form is the text kind, the
     * description and the number of glossary entries, followed by the source term, target language tag and target term of each entry in
     * canonical order; a text is an {@code int} byte length followed by its UTF-8 bytes, numbers are big-endian.
     */
    private static @NotNull String computeId(@NotNull String textKind, @NotNull String description, @NotNull List<@NotNull GlossaryEntry> glossary) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeText(output, textKind);
            writeText(output, description);
            output.writeInt(glossary.size());

            for (GlossaryEntry entry : glossary) {
                writeText(output, entry.getSourceTerm());
                writeText(output, entry.getTarget().toLanguageTag());
                writeText(output, entry.getTargetTerm());
            }
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST_ALGORITHM).digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no {0}.", DIGEST_ALGORITHM);
        }
    }

    private static void writeText(@NotNull DataOutputStream output, @NotNull String text) throws IOException {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);

        output.writeInt(encoded.length);
        output.write(encoded);
    }


    private final @NotNull String textKind;
    private final @NotNull String description;
    private final @NotNull List<@NotNull GlossaryEntry> glossary;
    private final @NotNull String id;

    private TranslationContext(@NotNull String textKind, @NotNull String description, @NotNull List<@NotNull GlossaryEntry> glossary) {
        this.textKind    = textKind;
        this.description = description;
        this.glossary    = glossary;
        this.id          = computeId(textKind, description, glossary);
    }

    /**
     * @return the SHA-256 checksum of the canonical form in lowercase hexadecimal, equal for contexts of equal contents
     */
    public @NotNull String getId() {
        return this.id;
    }

    /**
     * @return the kind of text, or an empty text for none
     */
    public @NotNull String getTextKind() {
        return this.textKind;
    }

    /**
     * @return the description of the domain and of how to translate it, or an empty text for none
     */
    public @NotNull String getDescription() {
        return this.description;
    }

    /**
     * @return the glossary entries in canonical order, without duplicates; unmodifiable
     */
    public @NotNull List<@NotNull GlossaryEntry> getGlossary() {
        return this.glossary;
    }

    /**
     * @return {@code true} if the text kind, the description and the glossary are all empty, {@code false} otherwise
     */
    public boolean isEmpty() {
        return this.textKind.isEmpty() && this.description.isEmpty() && this.glossary.isEmpty();
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     *
     * @param other the reference object with which to compare
     * @return {@code true} if the other object is a context with the same id, {@code false} otherwise
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TranslationContext context && this.id.equals(context.id);
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return this.id.hashCode();
    }
}
