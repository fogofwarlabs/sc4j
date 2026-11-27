package org.fogofwarlabs.sc4j.translate.service;

import org.fogofwarlabs.sc4j.translate.*;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationDevice;
import org.fogofwarlabs.sc4j.translate.service.model.TranslationModelOptions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * Tests the translation service with a fake preset: the decision order, protected spans, whole runs and their chunks, the context and the
 * options, the cache and its key, the queue priority, the batches, the idle close of the models and the answers when the models are missing
 * or the service is closed.
 */
final class TranslationServiceTest {
    private static final int LARGE_INPUT_LIMIT = 512;
    private static final @NotNull Duration LONG_IDLE_TIME = Duration.ofMinutes(10);
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path folder;

    @Test
    void translate_should_translate_from_the_identified_language() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);

        preset.setIdentified("안녕하세요", "ko");

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            Translation translation = translate(service, new TranslationRequest.Builder("안녕하세요", Locale.ENGLISH).build());

            Assertions.assertEquals(TranslationStatus.TRANSLATED, translation.getStatus());
            Assertions.assertEquals("[ko>en] 안녕하세요", translation.getTranslatedText());
            Assertions.assertEquals("안녕하세요", translation.getOriginalText());
            Assertions.assertEquals(Locale.KOREAN, translation.getSource());
        }
    }

    @Test
    void translate_should_answer_same_language_when_the_identified_language_is_the_target() throws Exception {
        try (TranslationService service = this.createService(new FakeTranslationPreset(LARGE_INPUT_LIMIT), LONG_IDLE_TIME)) {
            Translation translation = translate(service, new TranslationRequest.Builder("Hello there", Locale.US).build());

            Assertions.assertEquals(TranslationStatus.SAME_LANGUAGE, translation.getStatus());
            Assertions.assertEquals("Hello there", translation.getTranslatedText());
            Assertions.assertEquals(Locale.ENGLISH, translation.getSource());
        }
    }

    @Test
    void translate_should_skip_texts_without_letters_and_texts_over_the_limit() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            for (String text : List.of("", "   ", "123 456!", ":) ^^", "a".repeat(TranslationService.MAXIMUM_TEXT_LENGTH + 1))) {
                Translation translation = translate(service, new TranslationRequest.Builder(text, Locale.KOREAN).build());

                Assertions.assertEquals(TranslationStatus.SKIPPED, translation.getStatus(), text);
                Assertions.assertEquals(text, translation.getTranslatedText());
            }

            Assertions.assertEquals(List.of(), preset.getIdentifiedTexts());
            Assertions.assertEquals(0, preset.getModelLoads());
        }
    }

    @Test
    void translate_should_keep_protected_spans_and_white_space_verbatim() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);
        String text = "\u0003Hello\u0004  world \u0005!";
        TranslationRequest request = new TranslationRequest.Builder(text, Locale.KOREAN).addProtectedSpan(0, 1)
                                                                                        .addProtectedSpan(6, 7)
                                                                                        .addProtectedSpan(15, 16)
                                                                                        .build();

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            Translation translation = translate(service, request);

            Assertions.assertEquals(TranslationStatus.TRANSLATED, translation.getStatus());
            Assertions.assertEquals("\u0003[en>ko] Hello\u0004  [en>ko] world \u0005!", translation.getTranslatedText());
            Assertions.assertEquals(List.of("Hello world"), preset.getIdentifiedTexts());
        }
    }

    @Test
    void translate_should_give_the_model_whole_runs_with_their_context_and_the_options() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);
        TranslationContext context = new TranslationContext.Builder().setTextKind("chat message").build();
        TranslationModelOptions options = new TranslationModelOptions(TranslationDevice.CPU);

        try (TranslationService service = new TranslationService(preset, options, this.folder.resolve("presets"), null, LONG_IDLE_TIME)) {
            Translation translation = translate(service, new TranslationRequest.Builder("Good game. Want a rematch?  Sure!", Locale.KOREAN)
                    .setContext(context)
                    .build());

            Assertions.assertEquals(List.of("Good game. Want a rematch?  Sure!"), preset.getTranslatedTexts());
            Assertions.assertEquals("[en>ko] Good game. Want a rematch?  Sure!", translation.getTranslatedText());
            Assertions.assertSame(context, preset.getTranslatedInputs().getFirst().getContext());
            Assertions.assertSame(options, preset.getModelOptions());
        }
    }

    @Test
    void translate_should_split_runs_longer_than_the_input_limit_into_chunks_of_sentences() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(6);

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            Translation translation = translate(service, new TranslationRequest.Builder("One two three. Four five. Six seven eight nine ten.",
                                                                                        Locale.KOREAN).build());

            Assertions.assertEquals(TranslationStatus.TRANSLATED, translation.getStatus());
            Assertions.assertEquals(List.of("One two three.", "Four five.", "Six seven eight nine", "ten."), preset.getTranslatedTexts());
            Assertions.assertEquals("[en>ko] One two three. [en>ko] Four five. [en>ko] Six seven eight nine [en>ko] ten.",
                                    translation.getTranslatedText());
        }

        FakeTranslationPreset grouping = new FakeTranslationPreset(7);

        try (TranslationService service = this.createService(grouping, LONG_IDLE_TIME)) {
            translate(service, new TranslationRequest.Builder("One two. Three. Four five six seven.", Locale.KOREAN).build());

            Assertions.assertEquals(List.of("One two. Three.", "Four five six seven."), grouping.getTranslatedTexts());
        }
    }

    @Test
    void translate_should_reuse_cached_translations_from_memory_and_disk() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);
        TranslationRequest request = new TranslationRequest.Builder("See you later", Locale.KOREAN).build();

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            translate(service, request);
            translate(service, request);

            Assertions.assertEquals(List.of("See you later"), preset.getTranslatedTexts());
        }

        FakeTranslationPreset other = new FakeTranslationPreset(LARGE_INPUT_LIMIT);

        try (TranslationService service = this.createService(other, LONG_IDLE_TIME)) {
            Translation translation = translate(service, request);

            Assertions.assertEquals("[en>ko] See you later", translation.getTranslatedText());
            Assertions.assertEquals(List.of(), other.getTranslatedTexts());
            Assertions.assertEquals(0, other.getModelLoads());
        }
    }

    @Test
    void translate_should_keep_translations_by_preset_and_context() throws Exception {
        TranslationContext context = new TranslationContext.Builder().setTextKind("chat message").build();
        TranslationContext otherContext = new TranslationContext.Builder().setTextKind("menu text").build();
        TranslationRequest plain = new TranslationRequest.Builder("See you later", Locale.KOREAN).build();
        TranslationRequest withContext = new TranslationRequest.Builder("See you later", Locale.KOREAN).setContext(context).build();
        TranslationRequest sameContext = new TranslationRequest.Builder("See you later", Locale.KOREAN)
                .setContext(new TranslationContext.Builder().setTextKind("chat message").build())
                .build();
        TranslationRequest withOtherContext = new TranslationRequest.Builder("See you later", Locale.KOREAN).setContext(otherContext).build();
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            for (TranslationRequest request : List.of(plain, withContext, sameContext, withOtherContext, plain)) {
                translate(service, request);
            }

            Assertions.assertEquals(3, preset.getTranslatedTexts().size(), "one translation per context id, none for the repeated requests");
        }

        FakeTranslationPreset otherPreset = new FakeTranslationPreset("other", LARGE_INPUT_LIMIT);

        try (TranslationService service = this.createService(otherPreset, LONG_IDLE_TIME)) {
            translate(service, plain);
            translate(service, withContext);

            Assertions.assertEquals(2, otherPreset.getTranslatedTexts().size(), "another preset does not take the cached translations");
        }
    }

    @Test
    void translate_should_answer_chat_requests_before_batch_requests() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);
        CountDownLatch blocker = new CountDownLatch(1);

        preset.setBlocker(blocker);

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            CompletableFuture<Translation> first = service.translate(new TranslationRequest.Builder("first text", Locale.KOREAN).build());

            // The worker waits inside the first translation while the others queue.
            while (preset.getModelLoads() == 0) {
                Thread.onSpinWait();
            }

            CompletableFuture<Translation> batch = service.translate(new TranslationRequest.Builder("batch text", Locale.KOREAN)
                                                                             .setPriority(TranslationPriority.BATCH)
                                                                             .build());
            CompletableFuture<Translation> chat = service.translate(new TranslationRequest.Builder("chat text", Locale.KOREAN).build());

            blocker.countDown();
            CompletableFuture.allOf(first, batch, chat).get(WAIT_SECONDS, TimeUnit.SECONDS);

            Assertions.assertEquals(List.of("first text", "chat text", "batch text"), preset.getTranslatedTexts());
        }
    }

    @Test
    void translate_should_translate_the_queued_requests_of_one_priority_in_one_batch() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);
        CountDownLatch blocker = new CountDownLatch(1);

        preset.setBlocker(blocker);

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            CompletableFuture<Translation> first = service.translate(new TranslationRequest.Builder("first text", Locale.KOREAN).build());

            // The worker waits inside the first batch while the others queue.
            while (preset.getModelLoads() == 0) {
                Thread.onSpinWait();
            }

            CompletableFuture<Translation> alpha = service.translate(new TranslationRequest.Builder("alpha text", Locale.KOREAN).build());
            CompletableFuture<Translation> gamma = service.translate(new TranslationRequest.Builder("gamma text", Locale.KOREAN)
                                                                             .setPriority(TranslationPriority.BATCH)
                                                                             .build());
            CompletableFuture<Translation> beta = service.translate(new TranslationRequest.Builder("beta text. Second one.", Locale.JAPANESE).build());

            blocker.countDown();
            CompletableFuture.allOf(first, alpha, gamma, beta).get(WAIT_SECONDS, TimeUnit.SECONDS);

            Assertions.assertEquals(List.of(List.of("first text"), List.of("alpha text", "beta text. Second one."), List.of("gamma text")),
                                    preset.getBatches());
            Assertions.assertEquals("[en>ko] alpha text", alpha.get().getTranslatedText());
            Assertions.assertEquals("[en>ja] beta text. Second one.", beta.get().getTranslatedText());
            Assertions.assertEquals("[en>ko] gamma text", gamma.get().getTranslatedText());
        }
    }

    @Test
    void translate_should_end_a_batch_at_the_maximum_runs() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);
        CountDownLatch blocker = new CountDownLatch(1);
        List<CompletableFuture<Translation>> futures = new ArrayList<>();

        preset.setBlocker(blocker);

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            futures.add(service.translate(new TranslationRequest.Builder("first text", Locale.KOREAN).build()));

            while (preset.getModelLoads() == 0) {
                Thread.onSpinWait();
            }

            for (int index = 0; index < TranslationService.MAXIMUM_BATCH_RUNS + 2; index++) {
                futures.add(service.translate(new TranslationRequest.Builder("text " + index, Locale.KOREAN).build()));
            }

            blocker.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).get(WAIT_SECONDS, TimeUnit.SECONDS);

            Assertions.assertEquals(List.of(1, TranslationService.MAXIMUM_BATCH_RUNS, 2), preset.getBatches().stream().map(List::size).toList());
        }
    }

    @Test
    void translate_should_answer_cached_runs_without_the_model_and_translate_a_repeated_run_once() throws Exception {
        TranslationRequest cached = new TranslationRequest.Builder("See you later", Locale.KOREAN).build();

        try (TranslationService service = this.createService(new FakeTranslationPreset(LARGE_INPUT_LIMIT), LONG_IDLE_TIME)) {
            translate(service, cached);
        }

        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);
        CountDownLatch blocker = new CountDownLatch(1);

        preset.setBlocker(blocker);

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            CompletableFuture<Translation> first = service.translate(new TranslationRequest.Builder("first text", Locale.KOREAN).build());

            while (preset.getModelLoads() == 0) {
                Thread.onSpinWait();
            }

            CompletableFuture<Translation> again = service.translate(cached);
            CompletableFuture<Translation> repeated = service.translate(new TranslationRequest.Builder("Good game", Locale.KOREAN).build());
            CompletableFuture<Translation> mixed = service.translate(new TranslationRequest.Builder("\u0003See you later\u0004Good game", Locale.KOREAN)
                                                                             .addProtectedSpan(0, 1)
                                                                             .addProtectedSpan(14, 15)
                                                                             .build());

            blocker.countDown();
            CompletableFuture.allOf(first, again, repeated, mixed).get(WAIT_SECONDS, TimeUnit.SECONDS);

            Assertions.assertEquals(List.of(List.of("first text"), List.of("Good game")), preset.getBatches());
            Assertions.assertEquals("[en>ko] See you later", again.get().getTranslatedText());
            Assertions.assertEquals("[en>ko] Good game", repeated.get().getTranslatedText());
            Assertions.assertEquals("\u0003[en>ko] See you later\u0004[en>ko] Good game", mixed.get().getTranslatedText());
        }
    }

    @Test
    void the_models_should_be_closed_after_the_idle_time_and_loaded_again() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);

        try (TranslationService service = this.createService(preset, Duration.ofMillis(50))) {
            translate(service, new TranslationRequest.Builder("first text", Locale.KOREAN).build());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

            while (preset.getModelCloses() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            Assertions.assertEquals(1, preset.getModelCloses());

            translate(service, new TranslationRequest.Builder("second text", Locale.KOREAN).build());

            Assertions.assertEquals(2, preset.getModelLoads());
        }
    }

    @Test
    void translate_should_answer_unavailable_when_the_models_fail_or_a_language_is_not_supported() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);

        preset.setIdentified("Bonjour", "xx");

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            Assertions.assertEquals(TranslationStatus.UNAVAILABLE,
                                    translate(service, new TranslationRequest.Builder("Hello", Locale.forLanguageTag("tlh")).build()).getStatus());
            Assertions.assertEquals(TranslationStatus.UNAVAILABLE,
                                    translate(service, new TranslationRequest.Builder("Bonjour", Locale.KOREAN).build()).getStatus());
        }

        preset.setFailing(true);

        try (TranslationService service = this.createService(preset, LONG_IDLE_TIME)) {
            Translation translation = translate(service, new TranslationRequest.Builder("Hello", Locale.KOREAN).build());

            Assertions.assertEquals(TranslationStatus.UNAVAILABLE, translation.getStatus());
            Assertions.assertEquals("Hello", translation.getTranslatedText());
        }
    }

    @Test
    void close_should_answer_queued_and_later_requests_with_unavailable() throws Exception {
        FakeTranslationPreset preset = new FakeTranslationPreset(LARGE_INPUT_LIMIT);
        CountDownLatch blocker = new CountDownLatch(1);
        TranslationService service = this.createService(preset, LONG_IDLE_TIME);

        preset.setBlocker(blocker);

        CompletableFuture<Translation> first = service.translate(new TranslationRequest.Builder("first text", Locale.KOREAN).build());

        while (preset.getModelLoads() == 0) {
            Thread.onSpinWait();
        }

        CompletableFuture<Translation> queued = service.translate(new TranslationRequest.Builder("queued text", Locale.KOREAN).build());
        Thread closer = Thread.ofPlatform().start(service::close);

        // The closer waits for the worker only after it marked the service closed.
        while (closer.getState() != Thread.State.TIMED_WAITING) {
            Thread.onSpinWait();
        }

        blocker.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        Assertions.assertEquals(TranslationStatus.TRANSLATED, first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
        Assertions.assertEquals(TranslationStatus.UNAVAILABLE, queued.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
        Assertions.assertEquals(TranslationStatus.UNAVAILABLE,
                                translate(service, new TranslationRequest.Builder("late text", Locale.KOREAN).build()).getStatus());
        Assertions.assertEquals(1, preset.getModelCloses());
    }

    private static @NotNull Translation translate(@NotNull TranslationService service, @NotNull TranslationRequest request) throws ExecutionException,
                                                                                                                                   InterruptedException,
                                                                                                                                   TimeoutException {
        return service.translate(request).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private @NotNull TranslationService createService(@NotNull FakeTranslationPreset preset, @NotNull Duration idleTime) {
        return new TranslationService(preset, TranslationModelOptions.DEFAULT, this.folder.resolve("presets"), this.folder.resolve("cache"), idleTime);
    }
}
