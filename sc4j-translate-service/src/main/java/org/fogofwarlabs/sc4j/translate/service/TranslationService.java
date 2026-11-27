package org.fogofwarlabs.sc4j.translate.service;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.translate.*;
import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translates with the models of one preset on one worker thread of low priority. Requests wait in a queue, {@link
 * org.fogofwarlabs.sc4j.translate.TranslationPriority#CHAT} before {@link org.fogofwarlabs.sc4j.translate.TranslationPriority#BATCH}, each
 * priority in arrival order. The models are loaded when a request first needs them and closed after the idle time without requests; a
 * model that fails to load is tried again after the idle time.
 * <p>
 * A request is decided in this order: the text runs between the protected spans are what is translated, and a text longer than the
 * limit or without letters in them is skipped; the source language is the top label of the preset's language identifier for the runs; a
 * source language equal to the target is the same language. A preset without identifier, or a label that is no language of the preset,
 * gives {@link TranslationStatus#UNAVAILABLE}. Each run goes to the model whole with the request's context, a run longer than the model's
 * input limit in chunks of whole sentences ({@link TextSplitter#chunk}), and its translation is kept in a {@link TranslationCache} under the
 * preset id, the languages, the context id and the run.
 * <p>
 * The worker translates in batches: it takes the first waiting request and the requests of the same priority queued behind it until their
 * runs that are not in the cache reach {@link #MAXIMUM_BATCH_RUNS}. A request whose runs are all in the cache is answered at once; the
 * chunks of the other runs, each distinct run and context once, are translated in one call of the model.
 */
public final class TranslationService implements Translator, AutoCloseable {
    /**
     * The time without requests after which the models are closed.
     */
    public static final @NotNull Duration IDLE_TIME = Duration.ofMinutes(10);

    /**
     * The longest text translated, in characters; longer texts are skipped.
     */
    public static final int MAXIMUM_TEXT_LENGTH = 4096;

    /**
     * The number of runs not in the cache after which a batch takes no more requests; a request is never split between batches.
     */
    public static final int MAXIMUM_BATCH_RUNS = 16;

    private static final @NotNull Logger LOGGER = Logger.of(TranslationService.class);
    private static final @NotNull String WORKER_NAME = "SC4J translation";
    private static final long CLOSE_WAIT_MILLIS = 10_000L;
    private static final @NotNull String SPACE = " ";
    private static final char KEY_SEPARATOR = '\u0000';
    /**
     * The context id of a request without context.
     */
    private static final @NotNull String NO_CONTEXT_ID = "";
    /**
     * The model input index of a chunk without letters, which stays as it is.
     */
    private static final int KEPT_CHUNK = -1;

    /**
     * A queued request with its future; a task without request stops the worker.
     */
    private static final class Task {
        private final @Nullable TranslationRequest request;
        private final @NotNull CompletableFuture<@NotNull Translation> future;
        private final long sequence;

        private Task(@Nullable TranslationRequest request, @NotNull CompletableFuture<@NotNull Translation> future, long sequence) {
            this.request  = request;
            this.future   = future;
            this.sequence = sequence;
        }

        private int getOrder() {
            return this.request == null ? -1 : this.request.getPriority().ordinal();
        }
    }

    /**
     * A request of a batch that waits for the model: its runs, its languages, its context id and the translation of each run, {@code null}
     * until known.
     */
    private static final class PendingRequest {
        private final @NotNull TranslationRequest request;
        private final @NotNull CompletableFuture<@NotNull Translation> future;
        private final @NotNull List<int @NotNull []> runs;
        private final @NotNull Locale source;
        private final @NotNull String sourceCode;
        private final @NotNull String targetCode;
        private final @NotNull String contextId;
        private final @Nullable String @NotNull [] translatedRuns;

        private PendingRequest(@NotNull TranslationRequest request, @NotNull CompletableFuture<@NotNull Translation> future,
                               @NotNull List<int @NotNull []> runs, @NotNull Locale source, @NotNull String sourceCode, @NotNull String targetCode) {
            TranslationContext context = request.getContext();

            this.request        = request;
            this.future         = future;
            this.runs           = runs;
            this.source         = source;
            this.sourceCode     = sourceCode;
            this.targetCode     = targetCode;
            this.contextId      = context == null ? NO_CONTEXT_ID : context.getId();
            this.translatedRuns = new String[runs.size()];
        }

        private @NotNull String getRunText(int index) {
            int[] run = this.runs.get(index);

            return this.request.getText().substring(run[0], run[1]);
        }

        private @NotNull String getRunKey(int index) {
            return this.sourceCode + KEY_SEPARATOR + this.targetCode + KEY_SEPARATOR + this.contextId + KEY_SEPARATOR + this.getRunText(index);
        }
    }

    /**
     * A distinct run of a batch that the model translates: its text, its target language, codes and context id, and its chunks, each with
     * the index of the model input that translates it or {@link #KEPT_CHUNK}.
     */
    private static final class BatchRun {
        private final @NotNull String text;
        private final @NotNull Locale target;
        private final @NotNull String sourceCode;
        private final @NotNull String targetCode;
        private final @NotNull String contextId;

        private final @NotNull List<@NotNull String> chunks;
        private final @NotNull List<@NotNull Integer> inputIndexes;

        private BatchRun(@NotNull String text, @NotNull Locale target, @NotNull String sourceCode, @NotNull String targetCode,
                         @NotNull String contextId) {
            this.text       = text;
            this.target     = target;
            this.sourceCode = sourceCode;
            this.targetCode = targetCode;
            this.contextId  = contextId;

            this.chunks       = new ArrayList<>();
            this.inputIndexes = new ArrayList<>();
        }
    }


    private static @NotNull Translation createUntranslated(@NotNull TranslationRequest request, @Nullable Locale source, @NotNull TranslationStatus status) {
        return Translation.untranslated(request.getText(), source, status);
    }

    /**
     * Joins the text of a request with the translations of its runs.
     */
    private static @NotNull Translation createTranslated(@NotNull PendingRequest pending) {
        String text = pending.request.getText();
        StringBuilder translated = new StringBuilder(text.length());
        int position = 0;

        for (int index = 0; index < pending.runs.size(); index++) {
            int[] run = pending.runs.get(index);

            translated.append(text, position, run[0]);
            translated.append(pending.translatedRuns[index]);
            position = run[1];
        }

        translated.append(text, position, text.length());

        return new Translation(text, translated.toString(), pending.source, TranslationStatus.TRANSLATED);
    }


    /**
     * Finds the runs to translate: the text between the protected spans without its leading and trailing white space, where it has a
     * letter.
     *
     * @return the start and end index of each run, in text order
     */
    private static @NotNull List<int @NotNull []> findRuns(@NotNull TranslationRequest request) {
        String text = request.getText();
        List<int[]> runs = new ArrayList<>();
        int start = 0;

        for (int index = 0; index <= request.getProtectedSpans().size(); index++) {
            ProtectedSpan span = index < request.getProtectedSpans().size() ? request.getProtectedSpans().get(index) : null;
            int end = span == null ? text.length() : span.getStart();
            int runStart = start;
            int runEnd = end;

            while (runStart < runEnd && Character.isWhitespace(text.charAt(runStart))) {
                runStart++;
            }

            while (runEnd > runStart && Character.isWhitespace(text.charAt(runEnd - 1))) {
                runEnd--;
            }

            if (TextSplitter.hasLetters(text.substring(runStart, runEnd))) {
                runs.add(new int[]{runStart, runEnd});
            }

            start = span == null ? text.length() : span.getEnd();
        }

        return runs;
    }


    private final @NotNull TranslationPreset preset;
    private final @NotNull TranslationModelOptions options;
    private final @NotNull Path presetFolder;
    private final long idleNanos;

    private final @NotNull TranslationCache cache;
    private final @NotNull PriorityBlockingQueue<@NotNull Task> queue;
    private final @NotNull AtomicLong sequence;
    private final @NotNull Thread worker;
    private volatile boolean closed;

    /**
     * The state of the worker thread: the loaded models, when they last failed to load, and the time of the last request.
     */
    private @Nullable TranslationModel model;
    private @Nullable LanguageIdentifier identifier;
    private boolean identifierLoaded;
    private long modelFailedNanos;
    private long identifierFailedNanos;
    private long lastRequestNanos;

    /**
     * Creates a {@link TranslationService} instance for a preset installed in the translation storage, with a disk cache there and the
     * idle time {@link #IDLE_TIME}, and starts its worker thread. No model is loaded until a request needs it.
     *
     * @param preset  the preset
     * @param options the options the preset creates its translation model with
     * @param storage the translation storage of the install
     */
    public TranslationService(@NotNull TranslationPreset preset, @NotNull TranslationModelOptions options, @NotNull TranslationStorage storage) {
        this(preset, options, storage.getPresetFolder(preset), storage.getCacheFolder(), IDLE_TIME);
    }

    /**
     * Creates a {@link TranslationService} instance and starts its worker thread. No model is loaded until a request needs it.
     *
     * @param preset       the preset
     * @param options      the options the preset creates its translation model with
     * @param presetFolder the folder of the preset's installed files
     * @param cacheFolder  the folder of the disk cache, or {@code null} to keep translations in memory only
     * @param idleTime     the time without requests after which the models are closed
     */
    public TranslationService(@NotNull TranslationPreset preset, @NotNull TranslationModelOptions options,
                              @NotNull Path presetFolder, @Nullable Path cacheFolder, @NotNull Duration idleTime) {
        this.preset       = preset;
        this.options      = options;
        this.presetFolder = presetFolder;
        this.idleNanos    = idleTime.toNanos();

        this.cache    = new TranslationCache(cacheFolder);
        this.queue    = new PriorityBlockingQueue<>(16, Comparator.comparingInt(Task::getOrder).thenComparingLong(task -> task.sequence));
        this.sequence = new AtomicLong();
        this.worker   = new Thread(this::runWorker, WORKER_NAME);
        this.closed   = false;

        this.model                 = null;
        this.identifier            = null;
        this.identifierLoaded      = false;
        this.modelFailedNanos      = 0L;
        this.identifierFailedNanos = 0L;
        this.lastRequestNanos      = System.nanoTime();

        this.worker.setDaemon(true);
        this.worker.setPriority(Thread.MIN_PRIORITY);
        this.worker.start();
    }

    /**
     * @return the preset
     */
    public @NotNull TranslationPreset getPreset() {
        return this.preset;
    }

    /**
     * @return the options the preset creates its translation model with
     */
    public @NotNull TranslationModelOptions getOptions() {
        return this.options;
    }

    /**
     * Translates a text.
     *
     * @param request the request
     * @return the future of the translation, completed on a worker thread or at once
     */
    @Override
    public @NotNull CompletableFuture<@NotNull Translation> translate(@NotNull TranslationRequest request) {
        if (this.closed) {
            return CompletableFuture.completedFuture(createUntranslated(request, null, TranslationStatus.UNAVAILABLE));
        }

        Task task = new Task(request, new CompletableFuture<>(), this.sequence.getAndIncrement());

        this.queue.add(task);

        // A request that raced with close is answered here unless the worker took it.
        if (this.closed && this.queue.remove(task)) {
            task.future.complete(createUntranslated(request, null, TranslationStatus.UNAVAILABLE));
        }

        return task.future;
    }

    private void runWorker() {
        while (true) {
            Task task;

            try {
                task = this.queue.poll(this.findIdleWaitNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                break;
            }

            if (task == null) {
                this.closeModelsIfIdle();

                continue;
            }

            if (task.request == null) {
                break;
            }

            this.lastRequestNanos = System.nanoTime();

            this.translateBatch(task.request, task.future);
        }

        this.closeModels();
    }

    private long findIdleWaitNanos() {
        if (this.model == null && this.identifier == null) {
            return Long.MAX_VALUE;
        }

        return Math.max(0L, this.lastRequestNanos + this.idleNanos - System.nanoTime());
    }

    /**
     * Takes the requests of a batch, the first one and those of its priority queued behind it, answers the requests that need no model and
     * translates the runs of the others in one call of the model.
     */
    private void translateBatch(@NotNull TranslationRequest firstRequest, @NotNull CompletableFuture<@NotNull Translation> firstFuture) {
        List<PendingRequest> pending = new ArrayList<>();
        int uncachedRuns = 0;
        TranslationRequest request = firstRequest;
        CompletableFuture<Translation> future = firstFuture;

        while (true) {
            uncachedRuns += this.prepare(request, future, pending);

            if (uncachedRuns >= MAXIMUM_BATCH_RUNS) {
                break;
            }

            Task next = this.queue.poll();

            if (next == null) {
                break;
            }

            // A request of another priority, or the stop task, waits for the next batch.
            if (next.request == null || next.request.getPriority() != firstRequest.getPriority()) {
                this.queue.add(next);

                break;
            }

            request = next.request;
            future  = next.future;
        }

        if (pending.isEmpty()) {
            return;
        }

        try {
            this.translatePending(pending);
        } catch (ModelException exception) {
            LOGGER.warning("The translation model failed: {0}", exception.getMessage());

            for (PendingRequest waiting : pending) {
                waiting.future.complete(createUntranslated(waiting.request, waiting.source, TranslationStatus.UNAVAILABLE));
            }
        } catch (RuntimeException exception) {
            LOGGER.exception(LogLevel.WARNING, exception, "The translation failed");

            for (PendingRequest waiting : pending) {
                waiting.future.complete(createUntranslated(waiting.request, null, TranslationStatus.UNAVAILABLE));
            }
        }
    }

    /**
     * Answers a request whose runs need no model, or adds it to the pending requests with the cached translations of its runs.
     *
     * @return the number of runs of the request that are not in the cache
     */
    private int prepare(@NotNull TranslationRequest request, @NotNull CompletableFuture<@NotNull Translation> future,
                        @NotNull List<@NotNull PendingRequest> pending) {
        // A request taken after close began is answered as close answers the queued ones.
        if (this.closed) {
            future.complete(createUntranslated(request, null, TranslationStatus.UNAVAILABLE));

            return 0;
        }

        try {
            PendingRequest waiting = this.decide(request, future);

            if (waiting == null) {
                return 0;
            }

            int uncachedRuns = 0;

            for (int index = 0; index < waiting.runs.size(); index++) {
                waiting.translatedRuns[index] = this.cache.find(this.preset.getId(), waiting.sourceCode, waiting.targetCode, waiting.contextId,
                                                                waiting.getRunText(index));

                if (waiting.translatedRuns[index] == null) {
                    uncachedRuns++;
                }
            }

            if (uncachedRuns == 0) {
                waiting.future.complete(createTranslated(waiting));
            } else {
                pending.add(waiting);
            }

            return uncachedRuns;
        } catch (RuntimeException exception) {
            LOGGER.exception(LogLevel.WARNING, exception, "The translation failed");
            future.complete(createUntranslated(request, null, TranslationStatus.UNAVAILABLE));

            return 0;
        }
    }

    /**
     * Decides a request: answers it when it is skipped, unavailable or in the target language, else returns it with its runs and languages.
     *
     * @return the request waiting for the translations of its runs, or {@code null} when it is answered
     */
    private @Nullable PendingRequest decide(@NotNull TranslationRequest request, @NotNull CompletableFuture<@NotNull Translation> future) {
        String text = request.getText();
        String targetCode = this.preset.findCode(request.getTarget());

        if (targetCode == null) {
            future.complete(createUntranslated(request, null, TranslationStatus.UNAVAILABLE));

            return null;
        }

        if (text.length() > MAXIMUM_TEXT_LENGTH) {
            future.complete(createUntranslated(request, null, TranslationStatus.SKIPPED));

            return null;
        }

        List<int[]> runs = findRuns(request);
        StringBuilder joinedRuns = new StringBuilder();

        for (int[] run : runs) {
            if (!joinedRuns.isEmpty()) {
                joinedRuns.append(SPACE);
            }

            joinedRuns.append(text, run[0], run[1]);
        }

        String content = joinedRuns.toString();

        if (runs.isEmpty()) {
            future.complete(createUntranslated(request, null, TranslationStatus.SKIPPED));

            return null;
        }

        Locale source = this.identify(content);

        if (source == null) {
            future.complete(createUntranslated(request, null, TranslationStatus.UNAVAILABLE));

            return null;
        }

        String sourceCode = this.preset.findCode(source);

        if (sourceCode == null) {
            future.complete(createUntranslated(request, source, TranslationStatus.UNAVAILABLE));

            return null;
        }

        if (sourceCode.equals(targetCode)) {
            future.complete(createUntranslated(request, source, TranslationStatus.SAME_LANGUAGE));

            return null;
        }

        return new PendingRequest(request, future, runs, source, sourceCode, targetCode);
    }

    /**
     * Identifies the language of the text runs: the identifier's top label.
     *
     * @return the language, or {@code null} when the preset has no identifier, it cannot be loaded or its label is no language of the preset
     */
    private @Nullable Locale identify(@NotNull String content) {
        LanguageIdentifier languageIdentifier = this.loadIdentifier();
        IdentifiedLanguage identified = languageIdentifier == null ? null : languageIdentifier.identify(content.replace('\n', ' ').replace('\r', ' '));

        return identified == null ? null : this.preset.findLanguage(identified.getCode());
    }

    /**
     * Translates the runs of the pending requests that are not in the cache with the model, which is loaded here when needed, keeps their
     * translations in the cache and answers the requests. A run is one model input with its request's context, or one input per chunk when
     * it has more tokens than the model's input limit; a chunk without letters stays as it is.
     *
     * @throws ModelException if the model cannot be loaded or fails
     */
    private void translatePending(@NotNull List<@NotNull PendingRequest> pending) throws ModelException {
        TranslationModel translationModel = this.loadModel();

        if (translationModel == null) {
            throw new ModelException(Exceptions.format("The translation model of {0} is not loaded", this.preset.getId()));
        }

        Map<String, BatchRun> batchRuns = new LinkedHashMap<>();
        List<TranslationInput> inputs = new ArrayList<>();
        int maximumTokens = translationModel.getMaximumInputTokens();

        for (PendingRequest request : pending) {
            Locale target = request.request.getTarget();

            for (int index = 0; index < request.runs.size(); index++) {
                String key = request.getRunKey(index);

                if (request.translatedRuns[index] != null || batchRuns.containsKey(key)) {
                    continue;
                }

                String text = request.getRunText(index);
                BatchRun batchRun = new BatchRun(text, target, request.sourceCode, request.targetCode, request.contextId);

                for (String piece : TextSplitter.chunk(text, request.source, translationModel::countTokens, maximumTokens)) {
                    String chunk = piece.strip();

                    if (chunk.isEmpty()) {
                        continue;
                    }

                    batchRun.chunks.add(chunk);

                    if (TextSplitter.hasLetters(chunk)) {
                        batchRun.inputIndexes.add(inputs.size());
                        inputs.add(new TranslationInput(chunk, request.source, target, request.request.getContext()));
                    } else {
                        batchRun.inputIndexes.add(KEPT_CHUNK);
                    }
                }

                batchRuns.put(key, batchRun);
            }
        }

        List<String> outputs = inputs.isEmpty() ? List.of() : translationModel.translate(inputs);
        Map<String, String> translations = new HashMap<>();

        for (Map.Entry<String, BatchRun> entry : batchRuns.entrySet()) {
            BatchRun batchRun = entry.getValue();
            List<String> translatedChunks = new ArrayList<>(batchRun.chunks.size());

            for (int index = 0; index < batchRun.chunks.size(); index++) {
                int inputIndex = batchRun.inputIndexes.get(index);

                translatedChunks.add(inputIndex == KEPT_CHUNK ? batchRun.chunks.get(index) : outputs.get(inputIndex));
            }

            String translated = TextSplitter.join(translatedChunks, batchRun.target);

            translations.put(entry.getKey(), translated);
            this.cache.store(this.preset.getId(), batchRun.sourceCode, batchRun.targetCode, batchRun.contextId, batchRun.text, translated);
        }

        for (PendingRequest request : pending) {
            for (int index = 0; index < request.runs.size(); index++) {
                if (request.translatedRuns[index] == null) {
                    request.translatedRuns[index] = translations.get(request.getRunKey(index));
                }
            }

            request.future.complete(createTranslated(request));
        }
    }

    private @Nullable TranslationModel loadModel() {
        if (this.model != null || this.isRetryWaiting(this.modelFailedNanos)) {
            return this.model;
        }

        try {
            this.model = this.preset.createTranslationModel(this.presetFolder, this.options);
        } catch (ModelException | RuntimeException | LinkageError exception) {
            LOGGER.warning("Cannot load the translation model of {0}: {1}", this.preset.getId(), exception.getMessage());
            this.modelFailedNanos = System.nanoTime();
        }

        return this.model;
    }

    private @Nullable LanguageIdentifier loadIdentifier() {
        if (this.identifierLoaded || this.isRetryWaiting(this.identifierFailedNanos)) {
            return this.identifier;
        }

        try {
            this.identifier       = this.preset.createLanguageIdentifier(this.presetFolder);
            this.identifierLoaded = true;
        } catch (ModelException | RuntimeException exception) {
            LOGGER.warning("Cannot load the language identifier of {0}: {1}", this.preset.getId(), exception.getMessage());
            this.identifierFailedNanos = System.nanoTime();
        }

        return this.identifier;
    }

    private boolean isRetryWaiting(long failedNanos) {
        return failedNanos != 0L && System.nanoTime() - failedNanos < this.idleNanos;
    }

    private void closeModelsIfIdle() {
        if (System.nanoTime() - this.lastRequestNanos >= this.idleNanos) {
            this.closeModels();
        }
    }

    private void closeModels() {
        LanguageIdentifier loadedIdentifier = this.identifier;
        TranslationModel loadedModel = this.model;

        this.identifier       = null;
        this.identifierLoaded = false;
        this.model            = null;

        IgnoreException.execute(
                () -> {
                    if (loadedIdentifier != null) {
                        loadedIdentifier.close();
                    }
                },
                () -> {
                    if (loadedModel != null) {
                        loadedModel.close();
                    }
                }
        );
    }

    /**
     * Stops the worker thread after the batch it is translating, closes the models and answers the queued requests with
     * {@link TranslationStatus#UNAVAILABLE}.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;
        this.queue.add(new Task(null, new CompletableFuture<>(), this.sequence.getAndIncrement()));

        try {
            this.worker.join(CLOSE_WAIT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

        Task task = this.queue.poll();

        while (task != null) {
            if (task.request != null) {
                task.future.complete(createUntranslated(task.request, null, TranslationStatus.UNAVAILABLE));
            }

            task = this.queue.poll();
        }
    }
}
