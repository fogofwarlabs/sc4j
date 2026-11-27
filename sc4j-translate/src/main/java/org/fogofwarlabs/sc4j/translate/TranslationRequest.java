package org.fogofwarlabs.sc4j.translate;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Describes a text to translate: the text, the target language, the protected spans that stay verbatim, the priority and the
 * {@link TranslationContext}, if any. The translator identifies the source language.
 */
public final class TranslationRequest {
    /**
     * Builds {@link TranslationRequest} instances.
     */
    public static final class Builder {
        private final @NotNull String text;
        private final @NotNull Locale target;

        private final @NotNull List<@NotNull ProtectedSpan> protectedSpans;
        private @NotNull TranslationPriority priority;
        private @Nullable TranslationContext context;

        /**
         * Creates a {@link Builder} instance for a text and a target language, with no protected span, the priority
         * {@link TranslationPriority#CHAT} and no context.
         *
         * @param text   the text to translate
         * @param target the language to translate into
         */
        public Builder(@NotNull String text, @NotNull Locale target) {
            this.text   = text;
            this.target = target;

            this.protectedSpans = new ArrayList<>();
            this.priority       = TranslationPriority.CHAT;
            this.context        = null;
        }

        /**
         * Adds a protected span, a range of the text that stays verbatim.
         *
         * @param start the index of the first character of the span
         * @param end   the index after the last character of the span
         * @return this instance for chaining
         * @throws IllegalArgumentException if the span is empty or lies outside the text
         */
        public @NotNull Builder addProtectedSpan(int start, int end) {
            if (end > this.text.length()) {
                throw Exceptions.newIllegalArgumentException("The protected span {0} to {1} ends after the text of length {2}", start, end,
                                                             this.text.length());
            }

            this.protectedSpans.add(new ProtectedSpan(start, end));

            return this;
        }

        /**
         * Sets the priority.
         *
         * @param priority the priority
         * @return this instance for chaining
         */
        public @NotNull Builder setPriority(@NotNull TranslationPriority priority) {
            this.priority = priority;

            return this;
        }

        /**
         * Sets the context of the text.
         *
         * @param context the context, or {@code null} for none
         * @return this instance for chaining
         */
        public @NotNull Builder setContext(@Nullable TranslationContext context) {
            this.context = context;

            return this;
        }

        /**
         * Builds a {@link TranslationRequest} instance with the protected spans in text order.
         *
         * @return the built {@link TranslationRequest} instance
         * @throws IllegalArgumentException if two protected spans overlap
         */
        public @NotNull TranslationRequest build() {
            List<ProtectedSpan> spans = new ArrayList<>(this.protectedSpans);

            spans.sort(Comparator.comparingInt(ProtectedSpan::getStart));

            for (int index = 1; index < spans.size(); index++) {
                if (spans.get(index).getStart() < spans.get(index - 1).getEnd()) {
                    throw Exceptions.newIllegalArgumentException("The protected spans starting at {0} and {1} overlap", spans.get(index - 1).getStart(),
                                                                 spans.get(index).getStart());
                }
            }

            return new TranslationRequest(this.text, this.target, List.copyOf(spans), this.priority, this.context);
        }
    }


    private final @NotNull String text;
    private final @NotNull Locale target;
    private final @NotNull List<@NotNull ProtectedSpan> protectedSpans;
    private final @NotNull TranslationPriority priority;
    private final @Nullable TranslationContext context;

    private TranslationRequest(@NotNull String text, @NotNull Locale target, @NotNull List<@NotNull ProtectedSpan> protectedSpans,
                               @NotNull TranslationPriority priority, @Nullable TranslationContext context) {
        this.text           = text;
        this.target         = target;
        this.protectedSpans = protectedSpans;
        this.priority       = priority;
        this.context        = context;
    }

    /**
     * @return the text to translate
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the language to translate into
     */
    public @NotNull Locale getTarget() {
        return this.target;
    }

    /**
     * @return the protected spans in text order, not overlapping; unmodifiable
     */
    public @NotNull List<@NotNull ProtectedSpan> getProtectedSpans() {
        return this.protectedSpans;
    }

    /**
     * @return the priority
     */
    public @NotNull TranslationPriority getPriority() {
        return this.priority;
    }

    /**
     * @return the context of the text, or {@code null} for none
     */
    public @Nullable TranslationContext getContext() {
        return this.context;
    }
}
