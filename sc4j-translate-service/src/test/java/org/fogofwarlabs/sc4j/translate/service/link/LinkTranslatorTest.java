package org.fogofwarlabs.sc4j.translate.service.link;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.launcher.link.LauncherLinkAddress;
import org.fogofwarlabs.sc4j.launcher.link.LauncherLinkClient;
import org.fogofwarlabs.sc4j.launcher.link.LauncherLinkServer;
import org.fogofwarlabs.sc4j.launcher.link.SocketTempDirFactory;
import org.fogofwarlabs.sc4j.translate.Translation;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.fogofwarlabs.sc4j.translate.TranslationStatus;
import org.fogofwarlabs.sc4j.translate.Translator;
import org.fogofwarlabs.sc4j.translate.service.FakeTranslationPreset;
import org.fogofwarlabs.sc4j.translate.service.TranslationService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Tests the translator of a runtime over a launcher link in the same process: requests are answered by the launcher's service, a closed link
 * moves the requests to a local service created once, or to {@link TranslationStatus#UNAVAILABLE} without one, and a failure of the
 * launcher's service answers {@link TranslationStatus#UNAVAILABLE} without the local service.
 */
final class LinkTranslatorTest {
    private static final @NotNull String VERSION = "0.1.0-TEST";
    private static final @NotNull String SOCKET_NAME = "t.sock";
    private static final @NotNull String KOREAN_TEXT = "안녕하세요";
    private static final int LARGE_INPUT_LIMIT = 512;
    private static final @NotNull Duration IDLE_TIME = Duration.ofMinutes(10);
    private static final long TIMEOUT_SECONDS = 10L;

    @TempDir(factory = SocketTempDirFactory.class)
    Path folder;

    private static @NotNull Translation translate(@NotNull Translator translator, @NotNull TranslationRequest request) throws Exception {
        return translator.translate(request).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitClosed(@NotNull LauncherLinkClient client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

        while (!client.isClosed() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        Assertions.assertTrue(client.isClosed());
    }

    @Test
    void translate_should_answer_through_the_launcher_service() throws Exception {
        FakeTranslationPreset launcherPreset = this.createPreset();
        TranslationRequest request = new TranslationRequest.Builder("^1" + KOREAN_TEXT, Locale.ENGLISH).addProtectedSpan(0, 2).build();

        try (TranslationService launcherService = this.createService(launcherPreset);
             LauncherLinkServer server = this.openServer();
             LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION);
             LinkTranslator translator = new LinkTranslator(client, () -> Assertions.fail("the link is open"))) {
            server.register(TranslationLinkService.SERVICE_NAME, new TranslationLinkService(launcherService));

            Translation translation = translate(translator, request);

            Assertions.assertEquals(TranslationStatus.TRANSLATED, translation.getStatus());
            Assertions.assertEquals("^1[ko>en] " + KOREAN_TEXT, translation.getTranslatedText());
            Assertions.assertEquals(request.getText(), translation.getOriginalText());
            Assertions.assertEquals(Locale.KOREAN, translation.getSource());
            Assertions.assertEquals(1, launcherPreset.getModelLoads());
            Assertions.assertFalse(translator.isLocal());
        }
    }

    @Test
    void translate_should_move_to_one_local_service_once_the_link_closes() throws Exception {
        FakeTranslationPreset localPreset = this.createPreset();
        AtomicInteger created = new AtomicInteger();
        Supplier<TranslationService> localFactory = () -> {
            created.incrementAndGet();

            return this.createService(localPreset);
        };
        LauncherLinkServer server = this.openServer();
        CompletableFuture<Void> received = new CompletableFuture<>();

        server.register(TranslationLinkService.SERVICE_NAME, payload -> {
            received.complete(null);

            return new CompletableFuture<>();
        });

        try (LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION);
             LinkTranslator translator = new LinkTranslator(client, localFactory)) {
            CompletableFuture<Translation> pending = translator.translate(new TranslationRequest.Builder(KOREAN_TEXT, Locale.ENGLISH).build());

            received.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server.close();
            awaitClosed(client);

            Assertions.assertEquals("[ko>en] " + KOREAN_TEXT, pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getTranslatedText(),
                                    "a request the closing link failed is translated locally");
            Assertions.assertEquals(TranslationStatus.TRANSLATED,
                                    translate(translator, new TranslationRequest.Builder(KOREAN_TEXT + "!", Locale.ENGLISH).build()).getStatus());
            Assertions.assertTrue(translator.isLocal());
            Assertions.assertEquals(1, created.get());
        } finally {
            server.close();
        }

        Assertions.assertEquals(1, localPreset.getModelCloses(), "closing the translator closes its local service");
    }

    @Test
    void translate_should_answer_unavailable_after_the_link_closes_without_a_local_service() throws Exception {
        LauncherLinkServer server = this.openServer();

        try (LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION);
             LinkTranslator translator = new LinkTranslator(client, null)) {
            server.close();
            awaitClosed(client);

            Translation translation = translate(translator, new TranslationRequest.Builder(KOREAN_TEXT, Locale.ENGLISH).build());

            Assertions.assertEquals(TranslationStatus.UNAVAILABLE, translation.getStatus());
            Assertions.assertEquals(KOREAN_TEXT, translation.getTranslatedText());
        } finally {
            server.close();
        }
    }

    @Test
    void translate_should_answer_unavailable_when_the_launcher_fails_the_request() throws Exception {
        try (LauncherLinkServer server = this.openServer();
             LauncherLinkClient client = LauncherLinkClient.connect(server.getAddress(), VERSION);
             LinkTranslator translator = new LinkTranslator(client, () -> Assertions.fail("the link is open"))) {
            server.register(TranslationLinkService.SERVICE_NAME,
                            payload -> CompletableFuture.failedFuture(Exceptions.newIllegalStateException("no model")));

            Assertions.assertEquals(TranslationStatus.UNAVAILABLE,
                                    translate(translator, new TranslationRequest.Builder(KOREAN_TEXT, Locale.ENGLISH).build()).getStatus());
            Assertions.assertFalse(client.isClosed());
            Assertions.assertFalse(translator.isLocal());
        }
    }

    private @NotNull FakeTranslationPreset createPreset() {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);

        preset.setIdentified(KOREAN_TEXT, "ko");
        preset.setIdentified(KOREAN_TEXT + "!", "ko");

        return preset;
    }

    private @NotNull TranslationService createService(@NotNull FakeTranslationPreset preset) {
        return new TranslationService(preset, this.folder.resolve("presets"), null, IDLE_TIME);
    }

    private @NotNull LauncherLinkServer openServer() throws Exception {
        return LauncherLinkServer.open(LauncherLinkAddress.create(this.folder.resolve(SOCKET_NAME)), VERSION);
    }
}
