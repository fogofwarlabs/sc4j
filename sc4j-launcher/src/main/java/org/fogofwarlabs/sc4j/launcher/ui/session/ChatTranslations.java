package org.fogofwarlabs.sc4j.launcher.ui.session;

import org.fogofwarlabs.sc4j.translate.Translation;
import org.fogofwarlabs.sc4j.translate.TranslationRequest;
import org.fogofwarlabs.sc4j.translate.Translator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Translates the chat lines of other players for the Chat screen and remembers the answers and the lines the user switched back to their
 * original. Each text is asked once per target language while the same preset translates; another preset, or none, forgets the answers.
 * The answers come in on the publisher, the user interface thread, which then calls the change listener; only translated answers change a
 * line. Every method runs on the user interface thread. It lives as long as the account stays logged in.
 */
public final class ChatTranslations {
    /**
     * The most answers and switched lines remembered; the oldest are forgotten first.
     */
    private static final int REMEMBERED_ENTRIES = 1000;
    private static final char KEY_SEPARATOR = '\n';

    private static <K, V> @NotNull Map<K, V> createBoundedMap() {
        return new LinkedHashMap<>() {
            /**
             * Returns {@code true} if this map should remove its eldest entry.
             *
             * @param eldest the least recently inserted entry in the map
             * @return {@code true} if the eldest entry should be removed from the map; {@code false} if it should be retained
             */
            @Override
            protected boolean removeEldestEntry(Map.@Nullable Entry<K, V> eldest) {
                return this.size() > REMEMBERED_ENTRIES;
            }
        };
    }


    private final @NotNull Translator translator;
    private final @NotNull Supplier<@Nullable String> activePreset;
    private final @NotNull Executor publisher;

    private final @NotNull Map<@NotNull String, @NotNull Translation> answers;
    private final @NotNull Set<@NotNull String> pending;
    private final @NotNull Set<@NotNull Object> originalsShown;
    private @Nullable String preset;
    private int version;
    private @Nullable Runnable onChanged;

    /**
     * Creates a {@link ChatTranslations} instance without answers.
     *
     * @param translator   the translator of the chat
     * @param activePreset gives the id of the preset that translates, or {@code null} while nothing translates
     * @param publisher    runs the answers on the user interface thread
     */
    public ChatTranslations(@NotNull Translator translator, @NotNull Supplier<@Nullable String> activePreset, @NotNull Executor publisher) {
        this.translator   = translator;
        this.activePreset = activePreset;
        this.publisher    = publisher;

        this.answers        = createBoundedMap();
        this.pending        = new HashSet<>();
        this.originalsShown = Collections.newSetFromMap(createBoundedMap());
        this.preset         = null;
        this.version        = 0;
        this.onChanged      = null;
    }

    /**
     * Sets the listener called when a line shows other text: a translation came in or the user switched a line.
     *
     * @param listener the listener, or {@code null} for none
     */
    public void setOnChanged(@Nullable Runnable listener) {
        this.onChanged = listener;
    }

    /**
     * @return a number that changes each time a line shows other text
     */
    int getVersion() {
        return this.version;
    }

    /**
     * Finds the translation of a text and asks for it when it is not known yet.
     *
     * @param text            the text of the line
     * @param protectedLength the length of the start of the text that stays as it is, such as the name of an emote, or {@code 0}
     * @param target          the language to translate into
     * @return the translated text, or {@code null} while nothing translates, the answer has not come or the text was not translated
     */
    @Nullable String findTranslation(@NotNull String text, int protectedLength, @NotNull Locale target) {
        String active = this.activePreset.get();
        String key = target.toLanguageTag() + KEY_SEPARATOR + text;

        if (!Objects.equals(active, this.preset)) {
            this.preset = active;
            this.answers.clear();
            this.pending.clear();
        }

        if (active == null) {
            return null;
        }

        Translation answer = this.answers.get(key);

        if (answer != null) {
            return answer.isTranslated() ? answer.getTranslatedText() : null;
        }

        if (this.pending.add(key)) {
            TranslationRequest.Builder request = new TranslationRequest.Builder(text, target);

            if (protectedLength > 0) {
                request.addProtectedSpan(0, protectedLength);
            }

            this.translator.translate(request.build()).thenAccept(translation -> this.publisher.execute(() -> this.accept(active, key, translation)));
        }

        return null;
    }

    /**
     * Tells whether the user switched a translated line back to its original text.
     *
     * @param line the view of the line, such as its chat entry
     * @return {@code true} if the line shows its original text, {@code false} otherwise
     */
    boolean isOriginalShown(@NotNull Object line) {
        return this.originalsShown.contains(line);
    }

    /**
     * Switches a translated line between its translation and its original text.
     *
     * @param line the view of the line, such as its chat entry
     */
    void toggle(@NotNull Object line) {
        if (!this.originalsShown.remove(line)) {
            this.originalsShown.add(line);
        }

        this.change();
    }

    private void accept(@Nullable String requestedPreset, @NotNull String key, @NotNull Translation translation) {
        if (!Objects.equals(requestedPreset, this.preset) || !this.pending.remove(key)) {
            return;
        }

        this.answers.put(key, translation);

        if (translation.isTranslated()) {
            this.change();
        }
    }

    private void change() {
        Runnable listener = this.onChanged;

        this.version++;

        if (listener != null) {
            listener.run();
        }
    }
}
