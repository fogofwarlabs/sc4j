package org.fogofwarlabs.sc4j.manifest.validation;

import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads typed fields of one parsed JSON object (the values {@link org.fogofwarlabs.sc4j.base.json.JsonReader} produces)
 * and reports a missing required field or a field of the wrong type as a problem instead of failing, so that one pass
 * finds every problem of a manifest.
 * <p>
 * Each field is named by its path below the manifest root, such as {@code images.icon} or {@code settings[1].label}.
 */
public final class JsonFieldReader {
    private static final char PATH_SEPARATOR = '.';

    /**
     * Creates a {@link JsonFieldReader} instance for a value that must be a JSON object.
     *
     * @param value    the parsed value
     * @param path     the path of the value, empty for the manifest root
     * @param problems receives the problems
     * @return the created {@link JsonFieldReader} instance, or {@code null} after a {@link ProblemCode#FIELD_TYPE} problem when the value is not an object
     */
    public static @Nullable JsonFieldReader of(@Nullable Object value, @NotNull String path, @NotNull ValidationResult.Builder problems) {
        if (!(value instanceof Map<?, ?> object)) {
            problems.add(ProblemCode.FIELD_TYPE, path.isEmpty() ? "manifest" : path, "Expected a JSON object.");

            return null;
        }

        return new JsonFieldReader(object, path, problems);
    }


    private final @NotNull Map<?, ?> object;
    private final @NotNull String path;
    private final @NotNull ValidationResult.Builder problems;

    /**
     * Creates a {@link JsonFieldReader} instance.
     *
     * @param object   the parsed JSON object
     * @param path     the path of the object, empty for the manifest root
     * @param problems receives the problems
     */
    private JsonFieldReader(@NotNull Map<?, ?> object, @NotNull String path, @NotNull ValidationResult.Builder problems) {
        this.object   = object;
        this.path     = path;
        this.problems = problems;
    }

    /**
     * @return the problems this reader reports to
     */
    public @NotNull ValidationResult.Builder getProblems() {
        return this.problems;
    }

    /**
     * Returns the path of a field of this object.
     *
     * @param name the field name
     * @return the field path, such as {@code images.icon}
     */
    public @NotNull String getPath(@NotNull String name) {
        return this.path.isEmpty() ? name : this.path + PATH_SEPARATOR + name;
    }

    /**
     * Checks whether the object has a field, {@code null} counting as absent.
     *
     * @param name the field name
     * @return {@code true} if the field is present and not {@code null}, {@code false} otherwise
     */
    public boolean has(@NotNull String name) {
        return this.object.get(name) != null;
    }

    /**
     * Returns the raw parsed value of a field.
     *
     * @param name the field name
     * @return the parsed value, or {@code null} when the field is absent or {@code null}
     */
    public @Nullable Object get(@NotNull String name) {
        return this.object.get(name);
    }

    /**
     * Reads a string field that must be present and not blank.
     *
     * @param name the field name
     * @return the string, or {@code null} after a problem
     */
    public @Nullable String readRequiredString(@NotNull String name) {
        if (!this.has(name)) {
            this.reportMissing(name);

            return null;
        }

        String value = this.readOptionalString(name);

        if (value != null && value.isBlank()) {
            this.problems.add(ProblemCode.FIELD_VALUE, this.getPath(name), "The text must not be empty.");

            return null;
        }

        return value;
    }

    /**
     * Reads an optional string field.
     *
     * @param name the field name
     * @return the string, or {@code null} when the field is absent or after a problem
     */
    public @Nullable String readOptionalString(@NotNull String name) {
        Object value = this.object.get(name);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String text)) {
            this.reportType(name, "a string");

            return null;
        }

        return text;
    }

    /**
     * Reads an integer field that must be present.
     *
     * @param name the field name
     * @return the number, or {@code null} after a problem
     */
    public @Nullable Long readRequiredInteger(@NotNull String name) {
        if (!this.has(name)) {
            this.reportMissing(name);

            return null;
        }

        return this.readOptionalInteger(name);
    }

    /**
     * Reads an optional integer field.
     *
     * @param name the field name
     * @return the number, or {@code null} when the field is absent or after a problem
     */
    public @Nullable Long readOptionalInteger(@NotNull String name) {
        Object value = this.object.get(name);

        if (value == null) {
            return null;
        }

        if (!(value instanceof Long number)) {
            this.reportType(name, "an integer");

            return null;
        }

        return number;
    }

    /**
     * Reads an optional boolean field.
     *
     * @param name the field name
     * @return the value, or {@code null} when the field is absent or after a problem
     */
    public @Nullable Boolean readOptionalBoolean(@NotNull String name) {
        Object value = this.object.get(name);

        if (value == null) {
            return null;
        }

        if (!(value instanceof Boolean flag)) {
            this.reportType(name, "true or false");

            return null;
        }

        return flag;
    }

    /**
     * Reads a localized text field that must be present: an object from language to text, such as
     * {@code {"en": "Text language", "ko": "텍스트 언어"}}.
     *
     * @param name the field name
     * @return the text, or {@code null} after a problem
     */
    public @Nullable LocalizedText readRequiredLocalizedText(@NotNull String name) {
        if (!this.has(name)) {
            this.reportMissing(name);

            return null;
        }

        return this.readOptionalLocalizedText(name);
    }

    /**
     * Reads an optional localized text field: an object from language to non-empty text.
     *
     * @param name the field name
     * @return the text, or {@code null} when the field is absent or after a problem
     */
    public @Nullable LocalizedText readOptionalLocalizedText(@NotNull String name) {
        Object value = this.object.get(name);

        return value == null ? null : this.readLocalizedText(value, this.getPath(name));
    }

    /**
     * Reads an optional list of localized texts, each an object from language to non-empty text.
     *
     * @param name the field name
     * @return the texts in listed order, or {@code null} when the field is absent or after a problem
     */
    public @Nullable List<@NotNull LocalizedText> readOptionalLocalizedTextList(@NotNull String name) {
        List<?> values = this.readOptionalList(name);

        if (values == null) {
            return null;
        }

        List<LocalizedText> texts = new ArrayList<>(values.size());
        boolean valid = true;

        for (int index = 0; index < values.size(); index++) {
            LocalizedText text = this.readLocalizedText(values.get(index), this.getPath(name) + "[" + index + "]");

            if (text == null) {
                valid = false;
            } else {
                texts.add(text);
            }
        }

        return valid ? List.copyOf(texts) : null;
    }

    private @Nullable LocalizedText readLocalizedText(@Nullable Object value, @NotNull String path) {
        if (!(value instanceof Map<?, ?> languages) || languages.isEmpty()) {
            this.problems.add(ProblemCode.FIELD_TYPE, path, "Expected {0}.", "an object from language to text");

            return null;
        }

        Map<String, String> texts = new LinkedHashMap<>();
        boolean valid = true;

        for (Map.Entry<?, ?> entry : languages.entrySet()) {
            String language = String.valueOf(entry.getKey());
            String languagePath = path + PATH_SEPARATOR + language;

            if (!LocalizedText.isLanguage(language)) {
                this.problems.add(ProblemCode.FIELD_VALUE, languagePath,
                                  "Invalid language {0}: expected a lower case language code such as en or ko.", language);
                valid = false;
            } else if (!(entry.getValue() instanceof String text) || text.isBlank()) {
                this.problems.add(ProblemCode.FIELD_TYPE, languagePath, "Expected a non-empty text.");
                valid = false;
            } else {
                texts.put(language, text);
            }
        }

        return valid ? LocalizedText.of(texts) : null;
    }

    /**
     * Reads an optional list of strings.
     *
     * @param name the field name
     * @return the strings, or {@code null} when the field is absent or after a problem
     */
    public @Nullable List<@NotNull String> readOptionalStringList(@NotNull String name) {
        List<?> values = this.readOptionalList(name);

        if (values == null) {
            return null;
        }

        List<String> strings = new ArrayList<>(values.size());

        for (int index = 0; index < values.size(); index++) {
            if (!(values.get(index) instanceof String text) || text.isBlank()) {
                this.problems.add(ProblemCode.FIELD_TYPE, this.getPath(name) + "[" + index + "]", "Expected a non-empty text.");

                return null;
            }

            strings.add(text);
        }

        return List.copyOf(strings);
    }

    /**
     * Reads an optional list field.
     *
     * @param name the field name
     * @return the parsed elements, or {@code null} when the field is absent or after a problem
     */
    public @Nullable List<?> readOptionalList(@NotNull String name) {
        Object value = this.object.get(name);

        if (value == null) {
            return null;
        }

        if (!(value instanceof List<?> list)) {
            this.reportType(name, "a list");

            return null;
        }

        return list;
    }

    /**
     * Reads an optional object field.
     *
     * @param name the field name
     * @return a reader of the object, or {@code null} when the field is absent or after a problem
     */
    public @Nullable JsonFieldReader readOptionalObject(@NotNull String name) {
        Object value = this.object.get(name);

        return value == null ? null : of(value, this.getPath(name), this.problems);
    }

    private void reportMissing(@NotNull String name) {
        this.problems.add(ProblemCode.FIELD_MISSING, this.getPath(name), "The field is required.");
    }

    private void reportType(@NotNull String name, @NotNull String expected) {
        this.problems.add(ProblemCode.FIELD_TYPE, this.getPath(name), "Expected {0}.", expected);
    }
}
