package org.fogofwarlabs.sc4j.launcher.settings;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.launcher.i18n.UiLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the launcher settings file {@code userdata/launcher.json}:
 * {@snippet lang = "json":
 * {
 *   "format": 1,
 *   "language": "ko",
 *   "lastMod": "my-mod.jar",
 *   "window": {"x": 120, "y": 80, "width": 1280, "height": 800, "maximized": false},
 *   "updateRepository": "owner/name",
 *   "skippedVersion": "1.2.0",
 *   "chatSoundMuted": true,
 *   "translationPreset": "nllb-200-distilled-600m",
 *   "highPerformanceGpu": false
 * }
 *}
 * Every field but {@code format} is optional: without {@code language} the launcher follows the operating system's language, without
 * {@code updateRepository} it updates from {@link LauncherSettings#DEFAULT_UPDATE_REPOSITORY}, without {@code skippedVersion} it skips
 * no version, without {@code chatSoundMuted} the chat sound is on, without {@code translationPreset} the chat is not translated, and without
 * {@code highPerformanceGpu} games run on the high-performance GPU.
 */
public final class LauncherSettingsFile {
    /**
     * The launcher settings format this release reads and writes.
     */
    public static final int FORMAT = 1;

    private static final @NotNull String FORMAT_FIELD = "format";
    private static final @NotNull String LANGUAGE_FIELD = "language";
    private static final @NotNull String LAST_MOD_FIELD = "lastMod";
    private static final @NotNull String WINDOW_FIELD = "window";
    private static final @NotNull String X_FIELD = "x";
    private static final @NotNull String Y_FIELD = "y";
    private static final @NotNull String WIDTH_FIELD = "width";
    private static final @NotNull String HEIGHT_FIELD = "height";
    private static final @NotNull String MAXIMIZED_FIELD = "maximized";
    private static final @NotNull String UPDATE_REPOSITORY_FIELD = "updateRepository";
    private static final @NotNull String SKIPPED_VERSION_FIELD = "skippedVersion";
    private static final @NotNull String CHAT_SOUND_MUTED_FIELD = "chatSoundMuted";
    private static final @NotNull String TRANSLATION_PRESET_FIELD = "translationPreset";
    private static final @NotNull String HIGH_PERFORMANCE_GPU_FIELD = "highPerformanceGpu";

    private static @Nullable UiLanguage readLanguage(@NotNull Path path, @Nullable Object value) throws IOException {
        if (value == null) {
            return null;
        }

        UiLanguage language = value instanceof String code ? UiLanguage.fromCode(code) : null;

        if (language == null) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: unknown language {1}", path, value);
        }

        return language;
    }

    private static @Nullable WindowState readWindow(@NotNull Path path, @Nullable Object value) throws IOException {
        if (value == null) {
            return null;
        }

        if (!(value instanceof Map<?, ?> window) || !(window.get(X_FIELD) instanceof Long x) || !(window.get(Y_FIELD) instanceof Long y) ||
            !(window.get(WIDTH_FIELD) instanceof Long width) || !(window.get(HEIGHT_FIELD) instanceof Long height) ||
            !(window.get(MAXIMIZED_FIELD) instanceof Boolean maximized)) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: expected window {x, y, width, height, maximized}", path);
        }

        try {
            return new WindowState(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(width), Math.toIntExact(height), maximized);
        } catch (ArithmeticException exception) {
            throw Exceptions.newIOException(exception, "Invalid launcher settings file {0}: window value out of range", path);
        }
    }


    private final @NotNull Path path;

    /**
     * Creates a {@link LauncherSettingsFile} instance.
     *
     * @param path the file path, normally {@code <install>/userdata/launcher.json}
     */
    public LauncherSettingsFile(@NotNull Path path) {
        this.path = path;
    }

    /**
     * @return the file path
     */
    public @NotNull Path getPath() {
        return this.path;
    }

    /**
     * Reads the settings. A missing file holds the {@link LauncherSettings#DEFAULT} settings.
     *
     * @return the settings
     * @throws IOException if the file exists but cannot be read, is not valid JSON or is not in the launcher settings format
     */
    public @NotNull LauncherSettings read() throws IOException {
        byte[] contents;

        try {
            contents = Files.readAllBytes(this.path);
        } catch (NoSuchFileException exception) {
            return LauncherSettings.DEFAULT;
        }

        if (!(JsonReader.parse(contents) instanceof Map<?, ?> object) || !(object.get(FORMAT_FIELD) instanceof Long format) || format != FORMAT) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: expected format {1}", this.path, FORMAT);
        }

        Object lastMod = object.get(LAST_MOD_FIELD);

        if (lastMod != null && !(lastMod instanceof String)) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: lastMod must be a jar file name", this.path);
        }

        Object updateRepository = object.get(UPDATE_REPOSITORY_FIELD);

        if (updateRepository != null && (!(updateRepository instanceof String text) || ReleaseRepository.parse(text) == null)) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: updateRepository must be owner/name", this.path);
        }

        Object skippedVersion = object.get(SKIPPED_VERSION_FIELD);

        if (skippedVersion != null && (!(skippedVersion instanceof String version) || ReleaseVersion.parse(version) == null)) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: skippedVersion must be a version", this.path);
        }

        Object chatSoundMuted = object.get(CHAT_SOUND_MUTED_FIELD);

        if (chatSoundMuted != null && !(chatSoundMuted instanceof Boolean)) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: chatSoundMuted must be true or false", this.path);
        }

        Object translationPreset = object.get(TRANSLATION_PRESET_FIELD);

        if (translationPreset != null && !(translationPreset instanceof String)) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: translationPreset must be a preset id", this.path);
        }

        Object highPerformanceGpu = object.get(HIGH_PERFORMANCE_GPU_FIELD);

        if (highPerformanceGpu != null && !(highPerformanceGpu instanceof Boolean)) {
            throw Exceptions.newIOException("Invalid launcher settings file {0}: highPerformanceGpu must be true or false", this.path);
        }

        return new LauncherSettings(readLanguage(this.path, object.get(LANGUAGE_FIELD)), (String) lastMod,
                                    readWindow(this.path, object.get(WINDOW_FIELD)), (String) updateRepository, (String) skippedVersion,
                                    Boolean.TRUE.equals(chatSoundMuted), (String) translationPreset, !Boolean.FALSE.equals(highPerformanceGpu));
    }

    /**
     * Writes the settings, replacing the file atomically where the file system allows; the folder is created when missing.
     *
     * @param settings the settings
     * @throws IOException if the file cannot be written
     */
    public void write(@NotNull LauncherSettings settings) throws IOException {
        Map<String, Object> object = new LinkedHashMap<>();
        UiLanguage language = settings.getLanguage();
        WindowState window = settings.getWindow();

        object.put(FORMAT_FIELD, (long) FORMAT);

        if (language != null) {
            object.put(LANGUAGE_FIELD, language.getCode());
        }

        if (settings.getLastMod() != null) {
            object.put(LAST_MOD_FIELD, settings.getLastMod());
        }

        if (window != null) {
            Map<String, Object> windowObject = new LinkedHashMap<>();

            windowObject.put(X_FIELD, (long) window.getX());
            windowObject.put(Y_FIELD, (long) window.getY());
            windowObject.put(WIDTH_FIELD, (long) window.getWidth());
            windowObject.put(HEIGHT_FIELD, (long) window.getHeight());
            windowObject.put(MAXIMIZED_FIELD, window.isMaximized());
            object.put(WINDOW_FIELD, windowObject);
        }

        if (settings.getSavedUpdateRepository() != null) {
            object.put(UPDATE_REPOSITORY_FIELD, settings.getSavedUpdateRepository());
        }

        if (settings.getSkippedVersion() != null) {
            object.put(SKIPPED_VERSION_FIELD, settings.getSkippedVersion());
        }

        if (settings.isChatSoundMuted()) {
            object.put(CHAT_SOUND_MUTED_FIELD, true);
        }

        if (settings.getTranslationPreset() != null) {
            object.put(TRANSLATION_PRESET_FIELD, settings.getTranslationPreset());
        }

        if (!settings.isHighPerformanceGpu()) {
            object.put(HIGH_PERFORMANCE_GPU_FIELD, false);
        }

        AtomicFileUtils.write(this.path, JsonWriterUtils.write(object).getBytes(StandardCharsets.UTF_8));
    }
}
