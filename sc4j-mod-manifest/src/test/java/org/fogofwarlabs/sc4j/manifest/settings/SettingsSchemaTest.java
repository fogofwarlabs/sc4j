package org.fogofwarlabs.sc4j.manifest.settings;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationProblem;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests reading the settings schema of a manifest and validating saved values against its declarative checks.
 */
final class SettingsSchemaTest {
    private static final @NotNull String SCHEMA = """
                                                  [
                                                    {"key": "folder", "type": "directory", "required": true, "label": {"en": "Folder"},
                                                     "check": {"contains": ["game.info", "bin/game.exe"]},
                                                     "message": {"en": "Choose the game folder.", "ko": "게임 폴더를 선택하세요."}},
                                                    {"key": "replay", "type": "file", "extensions": [".sc4r"], "label": {"en": "Replay"}},
                                                    {"key": "locale", "type": "choice", "options": ["en-US", "ko-KR"], "default": "ko-KR", "label": {"en": "Language"}},
                                                    {"key": "music", "type": "boolean", "default": true, "label": {"en": "Music"}},
                                                    {"key": "speed", "type": "integer", "min": 0, "max": 6, "default": 4, "label": {"en": "Speed"}},
                                                    {"key": "name", "type": "string", "pattern": "[A-Za-z]{1,12}", "label": {"en": "Name"},
                                                     "message": {"en": "Use 1 to 12 letters."}}
                                                  ]
                                                  """;

    /**
     * Creates the files that the {@code folder} setting's {@code check.contains} lists.
     *
     * @param directory the game folder
     * @throws IOException if a file cannot be written
     */
    private static void createGameFolder(@NotNull Path directory) throws IOException {
        Files.writeString(directory.resolve("game.info"), "info");
        Files.createDirectories(directory.resolve("bin"));
        Files.writeString(directory.resolve("bin").resolve("game.exe"), "exe");
    }

    /**
     * Reads a schema that must be valid.
     *
     * @param json the {@code settings} list
     * @return the schema
     * @throws IOException if the JSON is broken
     */
    private static @NotNull SettingsSchema readValid(@NotNull String json) throws IOException {
        ValidationResult.Builder problems = new ValidationResult.Builder();
        SettingsSchema schema = SettingsSchema.read((List<?>) JsonReader.parse(json.getBytes(StandardCharsets.UTF_8)), "settings", problems);

        Assertions.assertTrue(problems.isValid(), problems.build().describe());

        return schema;
    }

    private static @NotNull ValidationResult readInvalid(@NotNull String json) throws IOException {
        ValidationResult.Builder problems = new ValidationResult.Builder();

        SettingsSchema.read((List<?>) JsonReader.parse(json.getBytes(StandardCharsets.UTF_8)), "settings", problems);

        ValidationResult result = problems.build();

        Assertions.assertFalse(result.isValid());

        return result;
    }

    @Test
    void read_should_create_one_definition_per_type() throws IOException {
        SettingsSchema schema = readValid(SCHEMA);

        Assertions.assertEquals(List.of(SettingType.DIRECTORY, SettingType.FILE, SettingType.CHOICE, SettingType.BOOLEAN, SettingType.INTEGER,
                                        SettingType.STRING),
                                schema.getDefinitions().stream().map(SettingDefinition::getType).toList());
        Assertions.assertEquals(List.of("sc4r"), ((FileSetting) schema.getDefinitions().get(1)).getExtensions());
        Assertions.assertEquals(4, schema.getDefinitions().get(4).getDefaultValue());
        Assertions.assertNull(schema.findDefinition("missing"));
    }

    @Test
    void read_should_report_inconsistent_definitions() throws IOException {
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"color\", \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.SETTING_TYPE_UNKNOWN));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"boolean\", \"label\": {\"en\": \"A\"}}," +
                                          " {\"key\": \"a\", \"type\": \"boolean\", \"label\": {\"en\": \"B\"}}]")
                                      .hasProblem(ProblemCode.SETTING_DUPLICATE));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"choice\", \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.FIELD_MISSING));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"choice\", \"options\": [\"x\"], \"default\": \"y\", \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.SETTING_INVALID));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"integer\", \"min\": 5, \"max\": 1, \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.SETTING_INVALID));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"integer\", \"default\": \"4\", \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.SETTING_INVALID));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"string\", \"pattern\": \"[\", \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.SETTING_INVALID));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"directory\", \"check\": {\"contains\": [\"../x\"]}, \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.SETTING_INVALID));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"directory\", \"check\": {\"containsAny\": [\"../x\"]}, \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.SETTING_INVALID));
        Assertions.assertTrue(readInvalid("[{\"key\": \"1a\", \"type\": \"boolean\", \"label\": {\"en\": \"A\"}}]")
                                      .hasProblem(ProblemCode.FIELD_VALUE));
        Assertions.assertTrue(readInvalid("[{\"key\": \"a\", \"type\": \"boolean\"}]").hasProblem(ProblemCode.FIELD_MISSING));
    }

    @Test
    void validate_should_accept_values_that_pass_every_check(@TempDir @NotNull Path directory) throws IOException {
        SettingsSchema schema = readValid(SCHEMA);
        Path replay = Files.writeString(directory.resolve("game.SC4R"), "replay");

        createGameFolder(directory);

        Map<String, Object> values = new HashMap<>();

        values.put("folder", directory.toString());
        values.put("replay", replay.toString());
        values.put("name", "Player");

        Assertions.assertTrue(schema.validate(values).isValid(), schema.validate(values).describe());
    }

    @Test
    void validate_should_report_failed_checks_with_the_setting_message(@TempDir @NotNull Path directory) throws IOException {
        SettingsSchema schema = readValid(SCHEMA);
        Map<String, Object> values = new HashMap<>();

        Files.writeString(directory.resolve("game.info"), "info");
        values.put("folder", directory.toString());
        values.put("replay", directory.resolve("game.info").toString());
        values.put("locale", "de-DE");
        values.put("music", "yes");
        values.put("speed", 7L);
        values.put("name", "Player One");

        ValidationResult result = schema.validate(values);
        List<ProblemCode> codes = result.getProblems().stream().map(ValidationProblem::getCode).toList();

        Assertions.assertEquals(List.of(ProblemCode.VALUE_CONTAINS, ProblemCode.VALUE_EXTENSION, ProblemCode.VALUE_OPTION, ProblemCode.VALUE_TYPE,
                                        ProblemCode.VALUE_RANGE, ProblemCode.VALUE_PATTERN),
                                codes);

        ValidationProblem contains = result.getProblems().getFirst();

        Assertions.assertEquals("folder", contains.getSubject());
        Assertions.assertEquals("게임 폴더를 선택하세요.", contains.getDisplayText("ko"));
        Assertions.assertEquals("Use 1 to 12 letters.", result.getProblems().getLast().getDisplayText("ko"));
        Assertions.assertTrue(result.getProblems().get(1).getDisplayText("en").contains("does not have one of the extensions"));
    }

    @Test
    void validate_should_require_a_value_without_default() throws IOException {
        SettingsSchema schema = readValid(SCHEMA);
        ValidationResult result = schema.validate(Map.of());

        Assertions.assertEquals(1, result.getProblems().size());
        Assertions.assertEquals(ProblemCode.VALUE_REQUIRED, result.getProblems().getFirst().getCode());
        Assertions.assertEquals("folder", result.getProblems().getFirst().getSubject());
        Assertions.assertNull(result.getProblems().getFirst().getMessage());
    }

    @Test
    void validate_should_report_a_directory_that_does_not_exist(@TempDir @NotNull Path directory) throws IOException {
        SettingsSchema schema = readValid(SCHEMA);
        ValidationResult result = schema.validate(Map.of("folder", directory.resolve("missing").toString()));

        Assertions.assertEquals(List.of(ProblemCode.VALUE_DIRECTORY), result.getProblems().stream().map(ValidationProblem::getCode).toList());
    }
}
