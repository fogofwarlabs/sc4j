package org.fogofwarlabs.sc4j.translate;

/**
 * Lists the outcomes of a translation request.
 */
public enum TranslationStatus {
    /**
     * The text was translated.
     */
    TRANSLATED,
    /**
     * The text is already in the target language.
     */
    SAME_LANGUAGE,
    /**
     * The text has nothing to translate: no letters, or more characters than the translator takes.
     */
    SKIPPED,
    /**
     * No translation is available: no model is installed, the model failed, the language is not supported or the translator was closed.
     */
    UNAVAILABLE
}
