package org.fogofwarlabs.sc4j.manifest.settings;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Defines a {@code choice} setting: the value must be one of the {@code options}.
 * <p>
 * With {@code systemDefault} the options name locales, such as {@code enUS} or {@code ko-KR}, and the default is the option of the
 * operating system's locale ({@link #findOption}); the declared {@code default} stands in when no option matches.
 */
public final class ChoiceSetting extends SettingDefinition {
    private static final @NotNull String SEPARATORS = "[-_]";

    private final @NotNull List<@NotNull String> options;
    private final boolean systemDefault;

    /**
     * Creates a {@link ChoiceSetting} instance.
     *
     * @param key           the key of the value in the settings value file
     * @param label         the label of the launcher's form field
     * @param description   the longer description, or {@code null}
     * @param required      whether a value (or a default) must exist before the mod runs
     * @param defaultValue  the option used when none is saved, or {@code null}
     * @param message       the localized message the launcher shows when a check fails, or {@code null} for the generic one
     * @param options       the allowed values, in the order the form shows them
     * @param systemDefault whether the options name locales and the option of the operating system's locale is the default
     * @throws IllegalArgumentException if there is no option
     */
    public ChoiceSetting(@NotNull String key, @NotNull LocalizedText label, @Nullable LocalizedText description,
                         boolean required, @Nullable String defaultValue, @Nullable LocalizedText message,
                         @NotNull List<@NotNull String> options, boolean systemDefault) {
        super(key, label, description, required, defaultValue, message);

        if (options.isEmpty()) {
            throw Exceptions.newIllegalArgumentException("The choice setting {0} has no option.", key);
        }

        this.options       = List.copyOf(options);
        this.systemDefault = systemDefault;
    }

    /**
     * @return the type of the setting
     */
    @Override
    public @NotNull SettingType getType() {
        return SettingType.CHOICE;
    }

    /**
     * Returns the value used when none is saved: with {@code systemDefault} the option of the operating system's locale when there is one.
     *
     * @return the locale's option, otherwise the declared default or {@code null}
     */
    @Override
    public @Nullable Object getDefaultValue() {
        String systemOption = this.systemDefault ? this.findOption(Locale.getDefault()) : null;

        return systemOption != null ? systemOption : super.getDefaultValue();
    }

    /**
     * @return the allowed values, in the order the form shows them
     */
    public @NotNull List<@NotNull String> getOptions() {
        return this.options;
    }

    /**
     * @return {@code true} if the options name locales and the option of the operating system's locale is the default, {@code false} otherwise
     */
    public boolean isSystemDefault() {
        return this.systemDefault;
    }

    /**
     * Finds the option that names a locale: the option of its language and country (such as {@code koKR}, {@code ko-KR} or
     * {@code ko_KR} for Korean in Korea), otherwise the first option of its language. Letter case does not count.
     *
     * @param locale the locale
     * @return the option, or {@code null} when no option names the locale's language
     */
    public @Nullable String findOption(@NotNull Locale locale) {
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        String languageAndCountry = language + locale.getCountry().toLowerCase(Locale.ROOT);
        String languageOption = null;

        if (language.isEmpty()) {
            return null;
        }

        for (String option : this.options) {
            String normalized = option.replaceAll(SEPARATORS, "").toLowerCase(Locale.ROOT);

            if (normalized.equals(languageAndCountry)) {
                return option;
            }

            if (languageOption == null && normalized.startsWith(language)) {
                languageOption = option;
            }
        }

        return languageOption;
    }

    /**
     * Runs the type's declarative checks on a value.
     *
     * @param value    the value, of the type's Java type
     * @param problems receives a problem for each failed check, with this setting's {@link #getMessage() message}
     */
    @Override
    void check(@NotNull Object value, @NotNull ValidationResult.Builder problems) {
        if (!this.options.contains((String) value)) {
            problems.addCheckFailure(ProblemCode.VALUE_OPTION, this.getKey(), this.getMessage(), "{0} is not one of {1}.", value, this.options);
        }
    }
}
