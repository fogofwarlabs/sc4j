package org.fogofwarlabs.sc4j.launcher.i18n;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Lists the languages of the launcher's own texts. The language comes from an explicit choice in the launcher settings, or
 * from the operating system's locale when there is none ({@link #select(UiLanguage, Locale)}).
 */
public enum UiLanguage {
    /**
     * English, also the language used when the system language has no launcher texts.
     */
    ENGLISH("en", Locale.ENGLISH),
    /**
     * Korean.
     */
    KOREAN("ko", Locale.KOREAN),
    /**
     * Simplified Chinese, also the language of a system in any other Chinese locale.
     */
    CHINESE("zh", Locale.SIMPLIFIED_CHINESE);

    /**
     * Selects the language of the launcher's texts.
     *
     * @param override     the language chosen in the launcher settings, or {@code null} to follow the system
     * @param systemLocale the operating system's locale, normally {@link Locale#getDefault()}
     * @return the chosen language; without one, the system's language when the launcher has texts for it, {@link #ENGLISH} otherwise
     */
    public static @NotNull UiLanguage select(@Nullable UiLanguage override, @NotNull Locale systemLocale) {
        if (override != null) {
            return override;
        }

        UiLanguage system = fromCode(systemLocale.getLanguage());

        return system != null ? system : ENGLISH;
    }

    /**
     * Finds a language by its code.
     *
     * @param code the language code, such as {@code ko}; case does not matter
     * @return the language, or {@code null} when the launcher has no texts in it
     */
    public static @Nullable UiLanguage fromCode(@NotNull String code) {
        for (UiLanguage language : values()) {
            if (language.code.equalsIgnoreCase(code)) {
                return language;
            }
        }

        return null;
    }


    private final @NotNull String code;
    private final @NotNull Locale locale;

    /**
     * Creates a {@link UiLanguage} instance.
     *
     * @param code   the language code, also the key of manifest texts
     * @param locale the locale of the resource bundle
     */
    UiLanguage(@NotNull String code, @NotNull Locale locale) {
        this.code   = code;
        this.locale = locale;
    }

    /**
     * @return the language code, such as {@code ko}; manifest texts are looked up with it
     */
    public @NotNull String getCode() {
        return this.code;
    }

    /**
     * @return the locale of the launcher's resource bundle
     */
    public @NotNull Locale getLocale() {
        return this.locale;
    }
}
