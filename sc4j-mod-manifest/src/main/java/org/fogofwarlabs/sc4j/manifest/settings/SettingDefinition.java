package org.fogofwarlabs.sc4j.manifest.settings;

import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Defines one setting of the manifest's {@code settings} schema: the attributes every type shares ({@code key},
 * {@code label}, {@code description}, {@code required}, {@code default} and the localized {@code message} of a failed
 * check) and, in each subclass, the declarative checks of its type.
 * <p>
 * Checks are declarative only: they look at the value and, for paths, at the file system; they never run mod code.
 */
public abstract sealed class SettingDefinition permits DirectorySetting, FileSetting, ChoiceSetting, BooleanSetting, IntegerSetting, StringSetting {
    private final @NotNull String key;
    private final @NotNull LocalizedText label;
    private final @Nullable LocalizedText description;

    private final boolean required;
    private final @Nullable Object defaultValue;
    private final @Nullable LocalizedText message;

    /**
     * Creates a {@link SettingDefinition} instance.
     *
     * @param key          the key of the value in the settings value file
     * @param label        the label of the launcher's form field
     * @param description  the longer description, or {@code null}
     * @param required     whether a value (or a default) must exist before the mod runs
     * @param defaultValue the value used when none is saved, of the type's Java type, or {@code null}
     * @param message      the localized message the launcher shows when a check fails, or {@code null} for the generic one
     */
    SettingDefinition(@NotNull String key, @NotNull LocalizedText label, @Nullable LocalizedText description,
                      boolean required, @Nullable Object defaultValue, @Nullable LocalizedText message) {
        this.key         = key;
        this.label       = label;
        this.description = description;

        this.required     = required;
        this.defaultValue = defaultValue;
        this.message      = message;
    }

    /**
     * @return the type of the setting
     */
    public abstract @NotNull SettingType getType();

    /**
     * @return the key of the value in the settings value file
     */
    public @NotNull String getKey() {
        return this.key;
    }

    /**
     * @return the label of the launcher's form field
     */
    public @NotNull LocalizedText getLabel() {
        return this.label;
    }

    /**
     * @return the longer description, or {@code null} when there is none
     */
    public @Nullable LocalizedText getDescription() {
        return this.description;
    }

    /**
     * @return {@code true} if a value or a default must exist before the mod runs, {@code false} otherwise
     */
    public boolean isRequired() {
        return this.required;
    }

    /**
     * @return the value used when none is saved, of the type's Java type ({@link SettingType}), or {@code null}
     */
    public @Nullable Object getDefaultValue() {
        return this.defaultValue;
    }

    /**
     * @return the localized message shown when a check fails, or {@code null} for the generic message
     */
    public @Nullable LocalizedText getMessage() {
        return this.message;
    }

    /**
     * Returns the name under which mods share one value of the setting ({@code shared}); only a {@code directory} setting declares one.
     *
     * @return the shared name, or {@code null} when the value belongs to this mod only
     */
    public @Nullable String getSharedName() {
        return null;
    }

    /**
     * Returns the value a saved JSON value stands for, the default when nothing is saved.
     *
     * @param saved the parsed JSON value from the settings value file, or {@code null} when none is saved
     * @return the value of the type's Java type, or {@code null} when nothing is saved and there is no default or the saved value has the wrong type
     */
    public @Nullable Object resolve(@Nullable Object saved) {
        return saved == null ? this.getDefaultValue() : this.getType().convert(saved);
    }

    /**
     * Validates a saved JSON value: a value must exist when the setting is required, it must have the type's JSON type and
     * it must pass the type's declarative checks. Without a saved value the default is checked.
     *
     * @param saved    the parsed JSON value from the settings value file, or {@code null} when none is saved
     * @param problems receives the problems
     */
    public final void validate(@Nullable Object saved, @NotNull ValidationResult.Builder problems) {
        Object value = this.resolve(saved);

        if (value == null && saved != null) {
            problems.add(ProblemCode.VALUE_TYPE, this.key, "Expected {0}.", this.getType().getExpectation());

            return;
        }

        if (value == null) {
            if (this.required) {
                problems.add(ProblemCode.VALUE_REQUIRED, this.key, "A value is required.");
            }

            return;
        }

        this.check(value, problems);
    }

    /**
     * Runs the type's declarative checks on a value.
     *
     * @param value    the value, of the type's Java type
     * @param problems receives a problem for each failed check, with this setting's {@link #getMessage() message}
     */
    abstract void check(@NotNull Object value, @NotNull ValidationResult.Builder problems);
}
