/**
 * The model layer of the translation service: a {@link org.fogofwarlabs.sc4j.translate.service.model.TranslationPreset} describes the files
 * of a downloadable model set and creates its {@link org.fogofwarlabs.sc4j.translate.service.model.TranslationModel} and
 * {@link org.fogofwarlabs.sc4j.translate.service.model.LanguageIdentifier}, with the
 * {@link org.fogofwarlabs.sc4j.translate.service.model.TranslationModelOptions} such as its device; a translation model translates batches
 * of {@link org.fogofwarlabs.sc4j.translate.service.model.TranslationInput} with their contexts, an encoder-decoder model through a
 * {@link org.fogofwarlabs.sc4j.translate.service.model.TextTokenizer} and a {@link org.fogofwarlabs.sc4j.translate.service.model.SequenceModel}.
 * The {@link org.fogofwarlabs.sc4j.translate.service.model.TextSplitter} splits texts into sentences and chunks and joins translations.
 */
package org.fogofwarlabs.sc4j.translate.service.model;
