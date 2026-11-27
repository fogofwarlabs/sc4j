package org.fogofwarlabs.sc4j.translate.service;

import org.fogofwarlabs.sc4j.translate.service.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A preset whose model marks texts as translated ({@code [ko>en] text}) and whose identifier answers from a table, else English, recording
 * what they do.
 * Its codes are the two-letter languages of {@link #LANGUAGES}; a token is a word, plus the language and end tokens. Each call of the model
 * translates one batch.
 */
public final class FakeTranslationPreset implements TranslationPreset {
    /**
     * The languages of the preset.
     */
    public static final @NotNull Set<@NotNull String> LANGUAGES = Set.of("en", "ko", "ja", "zh", "es", "de", "fr", "th");

    private static final @NotNull String DEFAULT_CODE = "en";
    private static final int LANGUAGE_AND_END_TOKENS = 2;
    private static final long BLOCK_SECONDS = 10;

    private final @NotNull List<@NotNull PresetFile> files;
    private final int maximumInputTokens;
    private final @NotNull Map<@NotNull String, @NotNull IdentifiedLanguage> identified;
    private final @NotNull List<@NotNull String> translatedTexts;
    private final @NotNull List<@NotNull List<@NotNull String>> batches;
    private final @NotNull List<@NotNull String> identifiedTexts;
    private final @NotNull AtomicInteger modelLoads;
    private final @NotNull AtomicInteger modelCloses;
    private volatile boolean failing;
    private volatile @Nullable CountDownLatch blocker;

    /**
     * Creates a {@link FakeTranslationPreset} instance without files.
     *
     * @param maximumInputTokens the input limit of the model
     */
    public FakeTranslationPreset(int maximumInputTokens) {
        this(List.of(), maximumInputTokens);
    }

    /**
     * Creates a {@link FakeTranslationPreset} instance.
     *
     * @param files              the files of the preset
     * @param maximumInputTokens the input limit of the model
     */
    public FakeTranslationPreset(@NotNull List<@NotNull PresetFile> files, int maximumInputTokens) {
        this.files              = files;
        this.maximumInputTokens = maximumInputTokens;
        this.identified         = new ConcurrentHashMap<>();
        this.translatedTexts    = new CopyOnWriteArrayList<>();
        this.batches            = new CopyOnWriteArrayList<>();
        this.identifiedTexts    = new CopyOnWriteArrayList<>();
        this.modelLoads         = new AtomicInteger();
        this.modelCloses        = new AtomicInteger();
        this.failing            = false;
        this.blocker            = null;
    }

    /**
     * Sets the identifier's answer for a text.
     *
     * @param text the text as the identifier receives it
     * @param code the code
     */
    public void setIdentified(@NotNull String text, @NotNull String code) {
        this.identified.put(text, new IdentifiedLanguage(code, 1.0f));
    }

    /**
     * Sets whether loading the models fails.
     *
     * @param failing {@code true} to fail
     */
    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    /**
     * Sets a latch that each translation waits for, or {@code null} for none.
     *
     * @param blocker the latch
     */
    public void setBlocker(@Nullable CountDownLatch blocker) {
        this.blocker = blocker;
    }

    /**
     * @return the texts the model translated, in order
     */
    public @NotNull List<@NotNull String> getTranslatedTexts() {
        return this.translatedTexts;
    }

    /**
     * @return the texts of each call of the model, in order
     */
    public @NotNull List<@NotNull List<@NotNull String>> getBatches() {
        return this.batches;
    }

    /**
     * @return the texts the identifier received, in order
     */
    public @NotNull List<@NotNull String> getIdentifiedTexts() {
        return this.identifiedTexts;
    }

    /**
     * @return how often the translation model was loaded
     */
    public int getModelLoads() {
        return this.modelLoads.get();
    }

    /**
     * @return how often the translation model was closed
     */
    public int getModelCloses() {
        return this.modelCloses.get();
    }

    @Override
    public @NotNull String getId() {
        return "fake";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Fake";
    }

    @Override
    public @NotNull String getLicenseName() {
        return "None";
    }

    @Override
    public @NotNull URI getLicenseAddress() {
        return URI.create("https://example.com/license");
    }

    @Override
    public @NotNull List<@NotNull PresetFile> getFiles() {
        return this.files;
    }

    @Override
    public @NotNull List<@NotNull Locale> getSupportedLanguages() {
        return LANGUAGES.stream().sorted().map(Locale::of).toList();
    }

    @Override
    public @Nullable String findCode(@NotNull Locale language) {
        return LANGUAGES.contains(language.getLanguage()) ? language.getLanguage() : null;
    }

    @Override
    public @Nullable Locale findLanguage(@NotNull String code) {
        return LANGUAGES.contains(code) ? Locale.of(code) : null;
    }

    @Override
    public @NotNull TranslationModel createTranslationModel(@NotNull Path folder) throws ModelException {
        if (this.failing) {
            throw new ModelException("The fake model fails");
        }

        this.modelLoads.incrementAndGet();

        return new TranslationModel() {
            @Override
            public int getMaximumInputTokens() {
                return FakeTranslationPreset.this.maximumInputTokens;
            }

            @Override
            public int countTokens(@NotNull String text) {
                String stripped = text.strip();

                return (stripped.isEmpty() ? 0 : stripped.split("\\s+").length) + LANGUAGE_AND_END_TOKENS;
            }

            @Override
            public @NotNull List<@NotNull String> translate(@NotNull List<@NotNull TranslationInput> inputs) throws ModelException {
                CountDownLatch latch = FakeTranslationPreset.this.blocker;

                try {
                    if (latch != null && !latch.await(BLOCK_SECONDS, TimeUnit.SECONDS)) {
                        throw new ModelException("The fake model waited too long");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();

                    throw new ModelException("The fake model was interrupted", exception);
                }

                List<String> texts = new ArrayList<>(inputs.size());
                List<String> translations = new ArrayList<>(inputs.size());

                for (TranslationInput input : inputs) {
                    texts.add(input.getText());
                    translations.add("[" + input.getSource().getLanguage() + ">" + input.getTarget().getLanguage() + "] " + input.getText());
                }

                FakeTranslationPreset.this.translatedTexts.addAll(texts);
                FakeTranslationPreset.this.batches.add(texts);

                return translations;
            }

            @Override
            public void close() {
                FakeTranslationPreset.this.modelCloses.incrementAndGet();
            }
        };
    }

    @Override
    public @Nullable LanguageIdentifier createLanguageIdentifier(@NotNull Path folder) throws ModelException {
        if (this.failing) {
            throw new ModelException("The fake identifier fails");
        }

        return new LanguageIdentifier() {
            @Override
            public @Nullable IdentifiedLanguage identify(@NotNull String text) {
                FakeTranslationPreset.this.identifiedTexts.add(text);

                return FakeTranslationPreset.this.identified.getOrDefault(text, new IdentifiedLanguage(DEFAULT_CODE, 1.0f));
            }

            @Override
            public void close() {
            }
        };
    }
}
