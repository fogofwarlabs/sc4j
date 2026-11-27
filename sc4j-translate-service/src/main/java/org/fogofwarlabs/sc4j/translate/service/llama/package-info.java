/**
 * Text generation for the translation models of large language models: a {@link org.fogofwarlabs.sc4j.translate.service.llama.TextGenerator}
 * generates texts greedily from prompts, and the {@link org.fogofwarlabs.sc4j.translate.service.llama.TextGeneratorLoader} loads one on the
 * GPU or the CPU, by the device option, from a {@link org.fogofwarlabs.sc4j.translate.service.llama.TextGeneratorFactory}, such as the
 * llama.cpp one of {@link org.fogofwarlabs.sc4j.translate.service.llama.LlamaTextGeneratorFactory}.
 */
package org.fogofwarlabs.sc4j.translate.service.llama;
