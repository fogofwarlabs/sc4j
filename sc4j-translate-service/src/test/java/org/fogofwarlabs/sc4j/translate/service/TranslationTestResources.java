package org.fogofwarlabs.sc4j.translate.service;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Locates the test resources of the translation service: the reference fixtures in the repository and the installed preset files, which
 * the repository does not hold. The preset files are read from {@code <folder>/<preset id>/}, the folder named by the system property
 * {@value #PRESETS_PROPERTY}; tests that need them are skipped when it is not set.
 */
public final class TranslationTestResources {
    /**
     * The system property that names the folder of the installed presets.
     */
    public static final @NotNull String PRESETS_PROPERTY = "sc4j.translation.presets";

    /**
     * The token ids and decoded texts of the NLLB SentencePiece model for a set of texts, written by the reference implementation.
     */
    public static final @NotNull String SENTENCEPIECE_FIXTURE = "sentencepiece-nllb.json";

    /**
     * The three most probable labels and their probabilities of the lid218e fastText model for a set of texts, written by the reference
     * implementation.
     */
    public static final @NotNull String FASTTEXT_FIXTURE = "fasttext-lid218e.json";

    /**
     * Reads a JSON fixture of this package, a list of cases.
     *
     * @param name the file name of the fixture
     * @return the cases, each a JSON object
     * @throws IOException if the fixture is missing or is not a JSON list of objects
     */
    public static @NotNull List<@NotNull Map<?, ?>> readFixture(@NotNull String name) throws IOException {
        Object parsed;

        try (InputStream input = TranslationTestResources.class.getResourceAsStream(name)) {
            if (input == null) {
                throw Exceptions.newIOException("The fixture {0} is missing", name);
            }

            parsed = JsonReader.parse(input.readAllBytes());
        }

        if (!(parsed instanceof List<?> list)) {
            throw Exceptions.newIOException("The fixture {0} is not a list", name);
        }

        List<Map<?, ?>> cases = new ArrayList<>(list.size());

        for (Object value : list) {
            if (!(value instanceof Map<?, ?> fields)) {
                throw Exceptions.newIOException("The fixture {0} holds a case that is not an object", name);
            }

            cases.add(fields);
        }

        return cases;
    }

    /**
     * Returns the folder of an installed preset, skipping the calling test when the presets folder is not set or does not hold the preset.
     *
     * @param presetId the id of the preset
     * @return the folder holding the preset's files
     */
    public static @NotNull Path requirePresetFolder(@NotNull String presetId) {
        String presets = System.getProperty(PRESETS_PROPERTY, "");

        Assumptions.assumeFalse(presets.isBlank(), "The system property " + PRESETS_PROPERTY + " names no presets folder");

        Path folder = Path.of(presets).resolve(presetId);

        Assumptions.assumeTrue(Files.isDirectory(folder), "The presets folder holds no " + presetId);

        return folder;
    }

    private TranslationTestResources() {
    }
}
