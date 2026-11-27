/**
 * The translation contract: a {@link org.fogofwarlabs.sc4j.translate.Translator} answers a
 * {@link org.fogofwarlabs.sc4j.translate.TranslationRequest} with a {@link org.fogofwarlabs.sc4j.translate.Translation} and its
 * {@link org.fogofwarlabs.sc4j.translate.TranslationStatus}. A request may carry a {@link org.fogofwarlabs.sc4j.translate.TranslationContext}:
 * the kind of text, a description and a glossary. Languages are {@link java.util.Locale} values. A translation is for display only; it never
 * enters simulation state, the network, replays or saves.
 */
package org.fogofwarlabs.sc4j.translate;
