package org.fogofwarlabs.sc4j.manifest.settings;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.manifest.test.FakeInstallationRecords;
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
import java.util.List;
import java.util.Map;

/**
 * Tests the checks of a directory setting and its detection in the computer's installation records, with recorded uninstall entries and
 * program folders that the test lays out in a temporary folder.
 */
final class DirectorySettingTest {
    private static final @NotNull String SETTINGS = """
                                                    [{"key": "gameFolder", "type": "directory", "required": true, "label": {"en": "Example Game folder"},
                                                      "check": {"contains": ["game.info", "bin/game.exe"]},
                                                      "detect": {"uninstallEntries": ["Example Game"], "programFolders": ["Example Game"]}},
                                                     {"key": "dataFolder", "type": "directory", "label": {"en": "Data folder"}, "check": {"contains": ["game.info"]}},
                                                     {"key": "anyFolder", "type": "directory", "label": {"en": "Any build folder"},
                                                      "check": {"contains": ["game.info"], "containsAny": ["bin/game.exe", "Game.app"]}}]
                                                    """;
    private static final @NotNull String ENTRY_NAME = "Example Game";

    private static @NotNull SettingsSchema readSchema() throws IOException {
        ValidationResult.Builder problems = new ValidationResult.Builder();
        SettingsSchema schema = SettingsSchema.read((List<?>) JsonReader.parse(SETTINGS.getBytes(StandardCharsets.UTF_8)), "settings", problems);

        Assertions.assertTrue(problems.isValid(), problems.build().describe());

        return schema;
    }

    private static @NotNull DirectorySetting requireDirectory(@NotNull SettingsSchema schema, @NotNull String key) {
        DirectorySetting directory = (DirectorySetting) schema.findDefinition(key);

        Assertions.assertNotNull(directory, key);

        return directory;
    }

    private static @NotNull Path createGameFolder(@NotNull Path directory) throws IOException {
        Files.createDirectories(directory.resolve("bin"));
        Files.writeString(directory.resolve("game.info"), "info");
        Files.writeString(directory.resolve("bin").resolve("game.exe"), "exe");

        return directory.toAbsolutePath().normalize();
    }

    @Test
    void detect_should_take_the_first_valid_uninstall_location_then_a_program_folder(@TempDir @NotNull Path directory) throws IOException {
        DirectorySetting folder = requireDirectory(readSchema(), "gameFolder");
        Path missing = directory.resolve("missing");
        Path incomplete = Files.createDirectories(directory.resolve("incomplete"));
        Path recorded = createGameFolder(directory.resolve("recorded"));
        Path programs = Files.createDirectories(directory.resolve("Program Files"));
        Path defaultFolder = createGameFolder(programs.resolve(ENTRY_NAME));

        InstallationRecords both = new FakeInstallationRecords(Map.of(ENTRY_NAME, List.of(missing, incomplete, recorded)), List.of(programs));
        InstallationRecords programsOnly = new FakeInstallationRecords(Map.of("Other Game", List.of(recorded)), List.of(missing, programs));
        InstallationRecords nothing = new FakeInstallationRecords(Map.of(ENTRY_NAME, List.of(incomplete)), List.of(missing));

        Assertions.assertEquals(recorded, folder.detect(both));
        Assertions.assertEquals(defaultFolder, folder.detect(programsOnly));
        Assertions.assertNull(folder.detect(nothing));
        Assertions.assertNull(folder.detect(InstallationRecords.NONE));
    }

    @Test
    void detect_should_find_nothing_for_a_setting_without_detect(@TempDir @NotNull Path directory) throws IOException {
        DirectorySetting data = requireDirectory(readSchema(), "dataFolder");
        Path recorded = createGameFolder(directory.resolve("recorded"));

        Assertions.assertNull(data.detect(new FakeInstallationRecords(Map.of(ENTRY_NAME, List.of(recorded)), List.of(directory))));
    }

    @Test
    void validate_should_report_the_check_failures_under_the_setting_key(@TempDir @NotNull Path directory) throws IOException {
        SettingsSchema schema = readSchema();
        Path folder = createGameFolder(directory.resolve("game"));

        Assertions.assertTrue(schema.validate(Map.of("gameFolder", folder.toString())).isValid());
        Assertions.assertTrue(schema.validate(Map.of()).hasProblem(ProblemCode.VALUE_REQUIRED));
        Assertions.assertTrue(schema.validate(Map.of("gameFolder", directory.toString())).hasProblem(ProblemCode.VALUE_CONTAINS));
        Assertions.assertEquals("gameFolder", schema.validate(Map.of()).getProblems().getFirst().getSubject());
    }

    @Test
    void validate_should_need_every_contained_path_and_one_of_the_alternatives(@TempDir @NotNull Path directory) throws IOException {
        SettingsSchema schema = readSchema();
        Path windowsBuild = createGameFolder(directory.resolve("windows"));
        Path macBuild = Files.createDirectories(directory.resolve("mac").resolve("Game.app"));
        Path neither = Files.createDirectories(directory.resolve("neither"));
        Path noInfo = Files.createDirectories(directory.resolve("no-info").resolve("Game.app"));

        Files.writeString(macBuild.resolveSibling("game.info"), "info");
        Files.writeString(neither.resolve("game.info"), "info");

        String game = windowsBuild.toString();
        List<ProblemCode> missingAlternatives = schema.validate(Map.of("gameFolder", game, "anyFolder", neither.toString())).getProblems().stream()
                                                      .map(ValidationProblem::getCode).toList();
        List<ProblemCode> missingInfo = schema.validate(Map.of("gameFolder", game, "anyFolder", noInfo.getParent().toString())).getProblems()
                                              .stream().map(ValidationProblem::getCode).toList();

        Assertions.assertTrue(schema.validate(Map.of("gameFolder", game, "anyFolder", game)).isValid());
        Assertions.assertTrue(schema.validate(Map.of("gameFolder", game, "anyFolder", macBuild.getParent().toString())).isValid());
        Assertions.assertEquals(List.of(ProblemCode.VALUE_CONTAINS_ANY), missingAlternatives);
        Assertions.assertEquals(List.of(ProblemCode.VALUE_CONTAINS), missingInfo);
    }
}
