package org.fogofwarlabs.sc4j.manifest.validation;

import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes one problem found in a mod jar, its manifest or a settings value: its kind, where it was found and a human
 * message. A failed declarative setting check also carries the setting's own localized {@code message}, which the
 * launcher shows instead of the generic text.
 */
public final class ValidationProblem {
    private final @NotNull ProblemCode code;
    private final @NotNull String subject;
    private final @NotNull String text;
    private final @Nullable LocalizedText message;

    /**
     * Creates a {@link ValidationProblem} instance.
     *
     * @param code    the kind of the problem
     * @param subject where the problem was found: a manifest field path such as {@code settings[0].key}, a setting key or a jar entry
     * @param text    the generic human message, in English
     * @param message the setting's localized message for a failed check, or {@code null} when there is none
     */
    public ValidationProblem(@NotNull ProblemCode code, @NotNull String subject, @NotNull String text, @Nullable LocalizedText message) {
        this.code    = code;
        this.subject = subject;
        this.text    = text;
        this.message = message;
    }

    /**
     * @return the kind of the problem
     */
    public @NotNull ProblemCode getCode() {
        return this.code;
    }

    /**
     * @return where the problem was found: a manifest field path, a setting key or a jar entry
     */
    public @NotNull String getSubject() {
        return this.subject;
    }

    /**
     * @return the generic human message, in English
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the setting's localized message for a failed check, or {@code null} when there is none
     */
    public @Nullable LocalizedText getMessage() {
        return this.message;
    }

    /**
     * Returns the message to show in a language: the setting's own message when there is one, the generic text otherwise.
     *
     * @param language the display language, such as {@code ko}
     * @return the message to show
     */
    public @NotNull String getDisplayText(@NotNull String language) {
        return this.message != null ? this.message.get(language) : this.text;
    }

    /**
     * Describes the problem on one line for logs: the code, the subject and the generic text.
     *
     * @return the line, such as {@code field.missing (id): the field is required}
     */
    public @NotNull String describe() {
        return this.code.getCode() + " (" + this.subject + "): " + this.text;
    }
}
