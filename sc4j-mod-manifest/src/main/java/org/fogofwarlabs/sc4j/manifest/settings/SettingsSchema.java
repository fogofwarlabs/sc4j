package org.fogofwarlabs.sc4j.manifest.settings;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.JsonFieldReader;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Holds the pre-run settings a mod declares in its manifest ({@code settings}), in the order the launcher's form shows
 * them, and validates saved values against them.
 * <p>
 * Each entry has the common fields {@code key}, {@code type}, {@code label}, {@code description}, {@code required},
 * {@code default} and {@code message}, and the fields of its type: {@code check.contains}, {@code check.containsAny},
 * {@code detect.uninstallEntries}, {@code detect.programFolders} and {@code shared} ({@code directory}), {@code extensions} ({@code file}), {@code options} and
 * {@code systemDefault} ({@code choice}), {@code min} and {@code max} ({@code integer}) and {@code pattern} ({@code string}).
 * No two settings of one mod have the same {@code shared} name.
 */
public final class SettingsSchema {
    /**
     * The schema without settings.
     */
    public static final @NotNull SettingsSchema EMPTY = new SettingsSchema(Map.of());

    private static final @NotNull Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final @NotNull Pattern SHARED_NAME_PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final @NotNull Pattern ENTRY_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._()+-]*");
    private static final @NotNull String PARENT_DIRECTORY = "..";

    private static final @NotNull String KEY_FIELD = "key";
    private static final @NotNull String TYPE_FIELD = "type";
    private static final @NotNull String LABEL_FIELD = "label";
    private static final @NotNull String DESCRIPTION_FIELD = "description";
    private static final @NotNull String REQUIRED_FIELD = "required";
    private static final @NotNull String DEFAULT_FIELD = "default";
    private static final @NotNull String MESSAGE_FIELD = "message";
    private static final @NotNull String CHECK_FIELD = "check";
    private static final @NotNull String CONTAINS_FIELD = "contains";
    private static final @NotNull String CONTAINS_ANY_FIELD = "containsAny";
    private static final @NotNull String DETECT_FIELD = "detect";
    private static final @NotNull String UNINSTALL_ENTRIES_FIELD = "uninstallEntries";
    private static final @NotNull String PROGRAM_FOLDERS_FIELD = "programFolders";
    private static final @NotNull String SHARED_FIELD = "shared";
    private static final @NotNull String EXTENSIONS_FIELD = "extensions";
    private static final @NotNull String OPTIONS_FIELD = "options";
    private static final @NotNull String SYSTEM_DEFAULT_FIELD = "systemDefault";
    private static final @NotNull String MINIMUM_FIELD = "min";
    private static final @NotNull String MAXIMUM_FIELD = "max";
    private static final @NotNull String PATTERN_FIELD = "pattern";

    /**
     * Creates a {@link SettingsSchema} instance from definitions.
     *
     * @param definitions the definitions in form order
     * @return the created {@link SettingsSchema} instance
     * @throws IllegalArgumentException if two definitions have the same key or the same shared name
     */
    public static @NotNull SettingsSchema of(@NotNull List<@NotNull SettingDefinition> definitions) {
        Map<String, SettingDefinition> byKey = new LinkedHashMap<>();
        Set<String> sharedNames = new HashSet<>();

        for (SettingDefinition definition : definitions) {
            String sharedName = definition.getSharedName();

            if (byKey.putIfAbsent(definition.getKey(), definition) != null) {
                throw Exceptions.newIllegalArgumentException("Duplicate setting key: {0}", definition.getKey());
            }

            if (sharedName != null && !sharedNames.add(sharedName)) {
                throw Exceptions.newIllegalArgumentException("Duplicate shared name: {0}", sharedName);
            }
        }

        return new SettingsSchema(byKey);
    }

    /**
     * Reads the manifest's {@code settings} list. Every problem is reported; the schema holds the entries without problems.
     *
     * @param settings the parsed list, or {@code null} when the manifest has no settings
     * @param path     the path of the list in the manifest, such as {@code settings}
     * @param problems receives the problems
     * @return the read schema
     */
    public static @NotNull SettingsSchema read(@Nullable List<?> settings, @NotNull String path, @NotNull ValidationResult.Builder problems) {
        if (settings == null) {
            return EMPTY;
        }

        Map<String, SettingDefinition> byKey = new LinkedHashMap<>();
        Set<String> sharedNames = new HashSet<>();

        for (int index = 0; index < settings.size(); index++) {
            String entryPath = path + "[" + index + "]";
            JsonFieldReader entry = JsonFieldReader.of(settings.get(index), entryPath, problems);
            SettingDefinition definition = entry == null ? null : readDefinition(entry);

            if (definition == null) {
                continue;
            }

            String sharedName = definition.getSharedName();

            if (byKey.putIfAbsent(definition.getKey(), definition) != null) {
                problems.add(ProblemCode.SETTING_DUPLICATE, entry.getPath(KEY_FIELD),
                             "Another setting already has the key {0}.", definition.getKey());
            }

            if (sharedName != null && !sharedNames.add(sharedName)) {
                problems.add(ProblemCode.SETTING_DUPLICATE, entry.getPath(SHARED_FIELD),
                             "Another setting already has the shared name {0}.", sharedName);
            }
        }

        return new SettingsSchema(byKey);
    }

    private static @Nullable SettingDefinition readDefinition(@NotNull JsonFieldReader entry) {
        ValidationResult.Builder problems = entry.getProblems();
        String key = entry.readRequiredString(KEY_FIELD);
        String typeName = entry.readRequiredString(TYPE_FIELD);
        LocalizedText label = entry.readRequiredLocalizedText(LABEL_FIELD);
        LocalizedText description = entry.readOptionalLocalizedText(DESCRIPTION_FIELD);
        Boolean required = entry.readOptionalBoolean(REQUIRED_FIELD);
        LocalizedText message = entry.readOptionalLocalizedText(MESSAGE_FIELD);

        if (key != null && !KEY_PATTERN.matcher(key).matches()) {
            problems.add(ProblemCode.FIELD_VALUE, entry.getPath(KEY_FIELD),
                         "Invalid key {0}: expected a letter followed by letters, digits, _ or -.", key);
            key = null;
        }

        SettingType type = typeName == null ? null : SettingType.fromName(typeName);

        if (typeName != null && type == null) {
            problems.add(ProblemCode.SETTING_TYPE_UNKNOWN, entry.getPath(TYPE_FIELD), "Unknown setting type {0}.", typeName);
        }

        if (key == null || type == null || label == null) {
            return null;
        }

        Object savedDefault = entry.get(DEFAULT_FIELD);
        Object defaultValue = savedDefault == null ? null : type.convert(savedDefault);

        if (savedDefault != null && defaultValue == null) {
            problems.add(ProblemCode.SETTING_INVALID, entry.getPath(DEFAULT_FIELD), "The default must be {0}.", type.getExpectation());

            return null;
        }

        boolean isRequired = required != null && required;
        SettingDefinition definition = switch (type) {
            case DIRECTORY -> readDirectorySetting(entry, key, label, description, isRequired, (Path) defaultValue, message);
            case FILE -> new FileSetting(key, label, description, isRequired, (Path) defaultValue, message, readList(entry, EXTENSIONS_FIELD));
            case CHOICE -> readChoiceSetting(entry, key, label, description, isRequired, (String) defaultValue, message);
            case BOOLEAN -> new BooleanSetting(key, label, description, isRequired, (Boolean) defaultValue, message);
            case INTEGER -> readIntegerSetting(entry, key, label, description, isRequired, (Integer) defaultValue, message);
            case STRING -> readStringSetting(entry, key, label, description, isRequired, (String) defaultValue, message);
        };

        if (definition == null || defaultValue == null || type == SettingType.DIRECTORY || type == SettingType.FILE) {
            return definition;
        }

        ValidationResult.Builder defaultProblems = new ValidationResult.Builder();

        definition.check(defaultValue, defaultProblems);

        if (!defaultProblems.isValid()) {
            problems.add(ProblemCode.SETTING_INVALID, entry.getPath(DEFAULT_FIELD), "The default {0} fails the setting's own checks.", defaultValue);

            return null;
        }

        return definition;
    }

    /**
     * Reads the type fields of a {@code directory} setting ({@code check.contains}, {@code check.containsAny}, {@code detect} and
     * {@code shared}) and creates the setting. Every problem is reported.
     *
     * @param entry        the setting's JSON object
     * @param key          the setting key
     * @param label        the label
     * @param description  the description, or {@code null}
     * @param required     whether a value must exist before the mod runs
     * @param defaultValue the default directory, or {@code null}
     * @param message      the message of a failed check, or {@code null}
     * @return the created setting, or {@code null} when a type field is invalid
     */
    private static @Nullable DirectorySetting readDirectorySetting(@NotNull JsonFieldReader entry, @NotNull String key, @NotNull LocalizedText label,
                                                                   @Nullable LocalizedText description, boolean required,
                                                                   @Nullable Path defaultValue, @Nullable LocalizedText message) {
        ValidationResult.Builder problems = entry.getProblems();
        JsonFieldReader check = entry.readOptionalObject(CHECK_FIELD);
        JsonFieldReader detect = entry.readOptionalObject(DETECT_FIELD);
        List<String> containedPaths = check == null ? List.of() : readList(check, CONTAINS_FIELD);
        List<String> anyContainedPaths = check == null ? List.of() : readList(check, CONTAINS_ANY_FIELD);
        List<String> uninstallEntries = detect == null ? List.of() : readList(detect, UNINSTALL_ENTRIES_FIELD);
        List<String> programFolders = detect == null ? List.of() : readList(detect, PROGRAM_FOLDERS_FIELD);
        String sharedName = entry.readOptionalString(SHARED_FIELD);
        boolean valid = true;

        for (String containedPath : containedPaths) {
            if (!isRelativeInside(containedPath)) {
                problems.add(ProblemCode.SETTING_INVALID, entry.getPath(CHECK_FIELD) + "." + CONTAINS_FIELD,
                             "{0} is not a relative path inside the directory.", containedPath);
                valid = false;
            }
        }

        for (String anyContainedPath : anyContainedPaths) {
            if (!isRelativeInside(anyContainedPath)) {
                problems.add(ProblemCode.SETTING_INVALID, entry.getPath(CHECK_FIELD) + "." + CONTAINS_ANY_FIELD,
                             "{0} is not a relative path inside the directory.", anyContainedPath);
                valid = false;
            }
        }

        for (String uninstallEntry : uninstallEntries) {
            if (!ENTRY_NAME_PATTERN.matcher(uninstallEntry).matches()) {
                problems.add(ProblemCode.SETTING_INVALID, entry.getPath(DETECT_FIELD) + "." + UNINSTALL_ENTRIES_FIELD,
                             "Invalid uninstall entry name {0}: expected letters, digits, spaces and . _ ( ) + -.", uninstallEntry);
                valid = false;
            }
        }

        for (String programFolder : programFolders) {
            if (!isRelativeInside(programFolder)) {
                problems.add(ProblemCode.SETTING_INVALID, entry.getPath(DETECT_FIELD) + "." + PROGRAM_FOLDERS_FIELD,
                             "{0} is not a relative path inside a program folder.", programFolder);
                valid = false;
            }
        }

        if (sharedName != null && !SHARED_NAME_PATTERN.matcher(sharedName).matches()) {
            problems.add(ProblemCode.SETTING_INVALID, entry.getPath(SHARED_FIELD),
                         "Invalid shared name {0}: expected lower case letters and digits, separated by single hyphens.", sharedName);
            valid = false;
        }

        if (!valid) {
            return null;
        }

        return new DirectorySetting(key, label, description, required, defaultValue, message,
                                    containedPaths, anyContainedPaths, uninstallEntries, programFolders, sharedName);
    }

    private static @Nullable ChoiceSetting readChoiceSetting(@NotNull JsonFieldReader entry, @NotNull String key, @NotNull LocalizedText label,
                                                             @Nullable LocalizedText description, boolean required,
                                                             @Nullable String defaultValue, @Nullable LocalizedText message) {
        List<String> options = readList(entry, OPTIONS_FIELD);
        Boolean systemDefault = entry.readOptionalBoolean(SYSTEM_DEFAULT_FIELD);

        if (options.isEmpty()) {
            entry.getProblems().add(ProblemCode.FIELD_MISSING, entry.getPath(OPTIONS_FIELD), "A choice setting needs at least one option.");

            return null;
        }

        return new ChoiceSetting(key, label, description, required, defaultValue, message, options, systemDefault != null && systemDefault);
    }

    private static @Nullable IntegerSetting readIntegerSetting(@NotNull JsonFieldReader entry, @NotNull String key, @NotNull LocalizedText label,
                                                               @Nullable LocalizedText description, boolean required,
                                                               @Nullable Integer defaultValue, @Nullable LocalizedText message) {
        Integer minimum = readBound(entry, MINIMUM_FIELD);
        Integer maximum = readBound(entry, MAXIMUM_FIELD);

        if (minimum != null && maximum != null && minimum > maximum) {
            entry.getProblems().add(ProblemCode.SETTING_INVALID, entry.getPath(MINIMUM_FIELD), "min {0} is above max {1}.", minimum, maximum);

            return null;
        }

        return new IntegerSetting(key, label, description, required, defaultValue, message, minimum, maximum);
    }

    private static @Nullable StringSetting readStringSetting(@NotNull JsonFieldReader entry, @NotNull String key, @NotNull LocalizedText label,
                                                             @Nullable LocalizedText description, boolean required,
                                                             @Nullable String defaultValue, @Nullable LocalizedText message) {
        String expression = entry.readOptionalString(PATTERN_FIELD);
        Pattern pattern;

        try {
            pattern = expression == null ? null : Pattern.compile(expression);
        } catch (PatternSyntaxException exception) {
            entry.getProblems().add(ProblemCode.SETTING_INVALID, entry.getPath(PATTERN_FIELD),
                                    "Invalid regular expression: {0}", exception.getDescription());

            return null;
        }

        return new StringSetting(key, label, description, required, defaultValue, message, pattern);
    }

    /**
     * Reads an optional list of strings.
     *
     * @param entry the JSON object
     * @param name  the field name
     * @return the strings, empty when the field is missing or invalid
     */
    private static @NotNull List<@NotNull String> readList(@NotNull JsonFieldReader entry, @NotNull String name) {
        List<String> values = entry.readOptionalStringList(name);

        return values == null ? List.of() : values;
    }

    private static @Nullable Integer readBound(@NotNull JsonFieldReader entry, @NotNull String name) {
        Long bound = entry.readOptionalInteger(name);

        if (bound == null) {
            return null;
        }

        Object converted = SettingType.INTEGER.convert(bound);

        if (converted == null) {
            entry.getProblems().add(ProblemCode.SETTING_INVALID, entry.getPath(name), "{0} is not a 32-bit integer.", bound);
        }

        return (Integer) converted;
    }

    /**
     * Checks whether a path names a file or folder inside a folder.
     *
     * @param path the path, with {@code /} separators
     * @return {@code true} if the path is relative, not empty and does not leave the folder, {@code false} otherwise
     */
    private static boolean isRelativeInside(@NotNull String path) {
        try {
            Path relative = Path.of(path);

            return !relative.isAbsolute() && relative.getRoot() == null && !relative.normalize().startsWith(PARENT_DIRECTORY) &&
                   !relative.normalize().toString().isEmpty();
        } catch (InvalidPathException exception) {
            return false;
        }
    }


    private final @NotNull List<@NotNull SettingDefinition> definitions;
    private final @NotNull Map<@NotNull String, @NotNull SettingDefinition> definitionsByKey;

    /**
     * Creates a {@link SettingsSchema} instance.
     *
     * @param definitionsByKey the definitions by key, in form order
     */
    private SettingsSchema(@NotNull Map<@NotNull String, @NotNull SettingDefinition> definitionsByKey) {
        this.definitions      = List.copyOf(definitionsByKey.values());
        this.definitionsByKey = Map.copyOf(definitionsByKey);
    }

    /**
     * @return the definitions in form order
     */
    public @NotNull List<@NotNull SettingDefinition> getDefinitions() {
        return this.definitions;
    }

    /**
     * Returns the definition of a key.
     *
     * @param key the setting key
     * @return the definition, or {@code null} when the schema has no setting with the key
     */
    public @Nullable SettingDefinition findDefinition(@NotNull String key) {
        return this.definitionsByKey.get(key);
    }

    /**
     * Validates saved values: every required setting has a value or a default, and every value has its type and passes
     * its checks. Saved values without a setting are ignored.
     *
     * @param savedValues the parsed values by key, as the settings value file holds them
     * @return the problems, keyed by setting
     */
    public @NotNull ValidationResult validate(@NotNull Map<@NotNull String, ?> savedValues) {
        ValidationResult.Builder problems = new ValidationResult.Builder();

        for (SettingDefinition definition : this.definitions) {
            definition.validate(savedValues.get(definition.getKey()), problems);
        }

        return problems.build();
    }
}
