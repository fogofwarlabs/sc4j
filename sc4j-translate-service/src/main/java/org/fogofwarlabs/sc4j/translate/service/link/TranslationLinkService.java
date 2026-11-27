package org.fogofwarlabs.sc4j.translate.service.link;

import org.fogofwarlabs.sc4j.launcher.link.LinkRequestHandler;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.fogofwarlabs.sc4j.translate.Translator;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Answers the translation requests that the runtimes send over the launcher link with the launcher's {@link Translator}: the handler of
 * the service {@value #SERVICE_NAME}. A payload that is not a request answers with a failure.
 */
public final class TranslationLinkService implements LinkRequestHandler {
    /**
     * The name of the translation service on the launcher link.
     */
    public static final @NotNull String SERVICE_NAME = "translation";

    private final @NotNull Translator translator;

    /**
     * Creates a {@link TranslationLinkService} instance.
     *
     * @param translator the translator that answers the requests
     */
    public TranslationLinkService(@NotNull Translator translator) {
        this.translator = translator;
    }

    /**
     * Handles a request. A future that fails, or an exception thrown here, answers the client with a failure that carries the message.
     *
     * @param payload the request payload
     * @return the future of the response payload
     */
    @Override
    public @NotNull CompletableFuture<byte @NotNull []> handle(byte @NotNull [] payload) {
        TranslationRequest request;

        try {
            request = TranslationCodecUtils.decodeRequest(payload);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        return this.translator.translate(request).thenApply(TranslationCodecUtils::encodeTranslation);
    }
}
