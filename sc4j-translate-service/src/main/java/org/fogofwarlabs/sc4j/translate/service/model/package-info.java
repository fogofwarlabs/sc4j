/**
 * The model layer of the translation service: a {@link org.fogofwarlabs.sc4j.translate.service.model.TranslationPreset} describes the files
 * of a downloadable model set and creates its {@link org.fogofwarlabs.sc4j.translate.service.model.TranslationModel} and
 * {@link org.fogofwarlabs.sc4j.translate.service.model.LanguageIdentifier}; a translation model joins a
 * {@link org.fogofwarlabs.sc4j.translate.service.model.TextTokenizer} and a {@link org.fogofwarlabs.sc4j.translate.service.model.SequenceModel}
 * and translates batches of {@link org.fogofwarlabs.sc4j.translate.service.model.TranslationInput}.
 */
package org.fogofwarlabs.sc4j.translate.service.model;
