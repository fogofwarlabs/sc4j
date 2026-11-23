package org.fogofwarlabs.sc4j.manifest.settings;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Lists the setting types of the manifest's {@code settings} schema with the Java type of their values: a path for
 * {@code directory} and {@code file}, a {@link String} for {@code choice} and {@code string}, a {@link Boolean} for
 * {@code boolean} and an {@link Integer} for {@code integer}.
 * <p>
 * A value is stored in JSON as a string (paths, choices, strings), a boolean or an integer number.
 */
public enum SettingType {
    /**
     * A directory; the JSON value is its path.
     */
    DIRECTORY("directory", "a directory path"),
    /**
     * A file; the JSON value is its path.
     */
    FILE("file", "a file path"),
    /**
     * One of a list of options.
     */
    CHOICE("choice", "one of the options"),
    /**
     * {@code true} or {@code false}.
     */
    BOOLEAN("boolean", "true or false"),
    /**
     * A 32-bit integer.
     */
    INTEGER("integer", "an integer"),
    /**
     * A text.
     */
    STRING("string", "a text");

    /**
     * Returns the type of a manifest type name.
     *
     * @param name the manifest name, such as {@code directory}
     * @return the type, or {@code null} for an unknown name
     */
    public static @Nullable SettingType fromName(@NotNull String name) {
        for (SettingType type : values()) {
            if (type.name.equals(name)) {
                return type;
            }
        }

        return null;
    }

    private static @Nullable Path convertPath(@NotNull String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException exception) {
            return null;
        }
    }


    private final @NotNull String name;
    private final @NotNull String expectation;

    /**
     * Creates a {@link SettingType} instance.
     *
     * @param name        the manifest name
     * @param expectation what a value of the type is, for messages
     */
    SettingType(@NotNull String name, @NotNull String expectation) {
        this.name        = name;
        this.expectation = expectation;
    }

    /**
     * @return the manifest name, such as {@code directory}
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return what a value of the type is, such as {@code a directory path}, for messages
     */
    public @NotNull String getExpectation() {
        return this.expectation;
    }

    /**
     * Converts a parsed JSON value to the Java value of this type. A value of the wrong JSON type, an invalid path and a number outside the 32-bit
     * integer range are not valid.
     *
     * @param value the parsed JSON value
     * @return the {@link Path}, {@link String}, {@link Boolean} or {@link Integer} value, or {@code null} if the value is not valid
     */
    public @Nullable Object convert(@NotNull Object value) {
        return switch (this) {
            case DIRECTORY, FILE -> value instanceof String text && !text.isBlank() ? convertPath(text) : null;
            case CHOICE, STRING -> value instanceof String text ? text : null;
            case BOOLEAN -> value instanceof Boolean flag ? flag : null;
            case INTEGER -> value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                            ? Integer.valueOf(number.intValue())
                            : null;
        };
    }

    /**
     * Converts a Java value of this type to its JSON value.
     *
     * @param value the {@link Path}, {@link String}, {@link Boolean} or {@link Integer} value
     * @return the JSON value: a string, a boolean or a {@link Long}
     * @throws IllegalArgumentException if the value does not have the Java type of this type
     */
    public @NotNull Object toJson(@NotNull Object value) {
        return switch (this) {
            case DIRECTORY, FILE -> requireType(value, Path.class).toString();
            case CHOICE, STRING -> requireType(value, String.class);
            case BOOLEAN -> requireType(value, Boolean.class);
            case INTEGER -> requireType(value, Integer.class).longValue();
        };
    }

    private <T> @NotNull T requireType(@NotNull Object value, @NotNull Class<T> javaType) {
        if (!javaType.isInstance(value)) {
            throw Exceptions.newIllegalArgumentException("A {0} setting value must be a {1}: {2}", this.name, javaType.getSimpleName(), value);
        }

        return javaType.cast(value);
    }
}
