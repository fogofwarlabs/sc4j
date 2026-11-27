/**
 * The translation service: {@link org.fogofwarlabs.sc4j.translate.service.TranslationService} answers translation requests on one worker
 * thread with the models of a preset, which it loads on the first request and closes when idle, and keeps its translations in a cache.
 */
package org.fogofwarlabs.sc4j.translate.service;
