package org.fogofwarlabs.sc4j.manifest.validation;

import org.jetbrains.annotations.NotNull;

/**
 * Names the kinds of problems that reading a mod jar, its manifest, its settings schema or a settings value file can
 * find. Each kind has a stable code that the launcher and tools may match; the human message of a problem may change.
 */
public enum ProblemCode {
    /**
     * The mod jar cannot be opened as a zip file.
     */
    ARCHIVE_UNREADABLE("archive.unreadable"),
    /**
     * The mod jar has no {@code sc4j-mod.json} at its root.
     */
    MANIFEST_MISSING("manifest.missing"),
    /**
     * The manifest is not valid JSON, not a JSON object, or too large.
     */
    MANIFEST_SYNTAX("manifest.syntax"),
    /**
     * A required field is missing.
     */
    FIELD_MISSING("field.missing"),
    /**
     * A field has the wrong JSON type.
     */
    FIELD_TYPE("field.type"),
    /**
     * A field has a value of the right type that is not allowed, such as an empty text or a bad language name.
     */
    FIELD_VALUE("field.value"),
    /**
     * The manifest {@code format} is not one this release reads.
     */
    FORMAT_UNSUPPORTED("format.unsupported"),
    /**
     * The mod {@code id} is not lower case letters, digits and single hyphens.
     */
    ID_SYNTAX("id.syntax"),
    /**
     * The mod {@code version} is not {@code major.minor.patch}.
     */
    VERSION_SYNTAX("version.syntax"),
    /**
     * The {@code engine} version is not {@code major.minor}.
     */
    ENGINE_SYNTAX("engine.syntax"),
    /**
     * The runtime's engine version does not support the mod's {@code engine} version.
     */
    ENGINE_UNSUPPORTED("engine.unsupported"),
    /**
     * The {@code entry} is not a Java class name.
     */
    ENTRY_SYNTAX("entry.syntax"),
    /**
     * An image the manifest names is not in the jar, or is not a PNG file.
     */
    IMAGE_MISSING("image.missing"),
    /**
     * Two settings have the same key.
     */
    SETTING_DUPLICATE("setting.duplicate"),
    /**
     * A setting has an unknown {@code type}.
     */
    SETTING_TYPE_UNKNOWN("setting.type.unknown"),
    /**
     * A setting definition is inconsistent, such as a {@code default} that fails its own checks or {@code min} above
     * {@code max}.
     */
    SETTING_INVALID("setting.invalid"),
    /**
     * A settings value file is not valid JSON or not in the settings value format.
     */
    VALUES_SYNTAX("values.syntax"),
    /**
     * A required setting has no value and no default.
     */
    VALUE_REQUIRED("value.required"),
    /**
     * A setting value has the wrong JSON type.
     */
    VALUE_TYPE("value.type"),
    /**
     * A {@code directory} value names no directory.
     */
    VALUE_DIRECTORY("value.directory"),
    /**
     * A {@code directory} value lacks a path that {@code check.contains} lists.
     */
    VALUE_CONTAINS("value.contains"),
    /**
     * A {@code directory} value holds none of the paths that {@code check.containsAny} lists.
     */
    VALUE_CONTAINS_ANY("value.containsAny"),
    /**
     * A {@code file} value names no file.
     */
    VALUE_FILE("value.file"),
    /**
     * A {@code file} value does not have one of the {@code extensions}.
     */
    VALUE_EXTENSION("value.extension"),
    /**
     * A {@code choice} value is not one of the {@code options}.
     */
    VALUE_OPTION("value.option"),
    /**
     * An {@code integer} value is outside {@code min} and {@code max}.
     */
    VALUE_RANGE("value.range"),
    /**
     * A {@code string} value does not match the {@code pattern}.
     */
    VALUE_PATTERN("value.pattern");

    private final @NotNull String code;

    /**
     * Creates a {@link ProblemCode} instance.
     *
     * @param code the stable code
     */
    ProblemCode(@NotNull String code) {
        this.code = code;
    }

    /**
     * @return the stable code, such as {@code field.missing}
     */
    public @NotNull String getCode() {
        return this.code;
    }
}
