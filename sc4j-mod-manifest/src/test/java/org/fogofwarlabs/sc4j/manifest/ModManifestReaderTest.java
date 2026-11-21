package org.fogofwarlabs.sc4j.manifest;

import org.fogofwarlabs.sc4j.manifest.settings.ChoiceSetting;
import org.fogofwarlabs.sc4j.manifest.settings.DirectorySetting;
import org.fogofwarlabs.sc4j.manifest.settings.SettingDefinition;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.ProblemCode;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationProblem;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationResult;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests reading and validating a mod manifest from bytes and from a mod jar.
 */
final class ModManifestReaderTest {
    private static final @NotNull String VALID_MANIFEST = """
                                                          {
                                                            "format": 1,
                                                            "id": "example-mod",
                                                            "name": {"en": "Example", "ko": "예제"},
                                                            "version": "1.2.3-beta.1",
                                                            "engine": "0.1",
                                                            "entry": "org.example.ExampleMod",
                                                            "description": {"en": "An example."},
                                                            "authors": ["Fog of War Labs"],
                                                            "license": "MIT",
                                                            "website": "https://example.org",
                                                            "images": {"icon": "meta/icon.png", "screenshots": ["meta/screenshots/1.png"]},
                                                            "settings": [
                                                              {"key": "gameFolder", "type": "directory", "required": true, "label": {"en": "Folder", "ko": "폴더"},
                                                               "check": {"contains": ["game.info", "bin/game.exe"]},
                                                               "message": {"en": "Choose the game folder.", "ko": "게임 폴더를 선택하세요."},
                                                               "detect": {"uninstallEntries": ["Example Game"], "programFolders": ["Example Game"]},
                                                               "shared": "example-game"},
                                                              {"key": "dataDirectory", "type": "directory", "label": {"en": "Data"}},
                                                              {"key": "locale", "type": "choice", "options": ["en-US", "ko-KR"], "default": "ko-KR",
                                                               "label": {"en": "Text language"}}
                                                            ]
                                                          }
                                                          """;

    private static final @NotNull String ICON_ENTRY = "meta/icon.png";
    private static final @NotNull String SCREENSHOT_ENTRY = "meta/screenshots/1.png";

    private static @NotNull ValidationResult parseInvalid(@NotNull String manifest) {
        return Assertions.assertThrows(ManifestException.class, () -> ModManifestReader.parse(manifest.getBytes(StandardCharsets.UTF_8))).getResult();
    }

    private static @NotNull Map<String, byte[]> createEntries(boolean withIcon, boolean withScreenshot) {
        Map<String, byte[]> entries = new LinkedHashMap<>();

        entries.put(TestJarUtils.MANIFEST_ENTRY, VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        if (withIcon) {
            entries.put(ICON_ENTRY, TestJarUtils.PNG_CONTENTS);
        }

        if (withScreenshot) {
            entries.put(SCREENSHOT_ENTRY, TestJarUtils.PNG_CONTENTS);
        }

        return entries;
    }

    private static @NotNull String createManifestWithNotices(@NotNull String notices) {
        return VALID_MANIFEST.replace("\"website\": \"https://example.org\",",
                                      "\"website\": \"https://example.org\",\n  \"notices\": " + notices + ",");
    }

    private static @NotNull List<@NotNull String> listSubjects(@NotNull String notices) {
        return parseInvalid(createManifestWithNotices(notices)).getProblems().stream().map(ValidationProblem::getSubject).toList();
    }

    private static @NotNull String createManifestWithTools(@NotNull String tools) {
        return VALID_MANIFEST.replace("\"website\": \"https://example.org\",", "\"website\": \"https://example.org\",\n  \"tools\": " + tools + ",");
    }

    private static @NotNull List<@NotNull String> listToolSubjects(@NotNull String tools) {
        return parseInvalid(createManifestWithTools(tools)).getProblems().stream().map(ValidationProblem::getSubject).toList();
    }

    @Test
    void parse_should_read_every_field_of_a_valid_manifest() throws ManifestException {
        ModManifest manifest = ModManifestReader.parse(VALID_MANIFEST.getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals(1, manifest.getFormat());
        Assertions.assertEquals("example-mod", manifest.getId());
        Assertions.assertEquals("1.2.3-beta.1", manifest.getVersion().toString());
        Assertions.assertEquals(EngineVersion.parse("0.1"), manifest.getEngine());
        Assertions.assertEquals("org.example.ExampleMod", manifest.getEntry());
        Assertions.assertEquals("예제", manifest.getName().get("ko"));
        Assertions.assertEquals("An example.", manifest.getDescription() == null ? null : manifest.getDescription().get("ko"));
        Assertions.assertEquals(List.of("Fog of War Labs"), manifest.getAuthors());
        Assertions.assertEquals("MIT", manifest.getLicense());
        Assertions.assertEquals("https://example.org", manifest.getWebsite());
        Assertions.assertEquals(List.of(ICON_ENTRY, SCREENSHOT_ENTRY), manifest.getImages().getEntries());

        List<SettingDefinition> settings = manifest.getSettings().getDefinitions();

        Assertions.assertEquals(3, settings.size());

        DirectorySetting folder = (DirectorySetting) settings.get(0);
        DirectorySetting data = (DirectorySetting) settings.get(1);

        Assertions.assertEquals("gameFolder", folder.getKey());
        Assertions.assertTrue(folder.isRequired());
        Assertions.assertEquals(List.of("game.info", "bin/game.exe"), folder.getContainedPaths());
        Assertions.assertEquals("게임 폴더를 선택하세요.", folder.getMessage() == null ? null : folder.getMessage().get("ko"));
        Assertions.assertEquals(List.of("Example Game"), folder.getUninstallEntries());
        Assertions.assertEquals(List.of("Example Game"), folder.getProgramFolders());
        Assertions.assertEquals("example-game", folder.getSharedName());
        Assertions.assertFalse(data.isRequired());
        Assertions.assertEquals(List.of(), data.getUninstallEntries());
        Assertions.assertNull(data.getSharedName());

        ChoiceSetting locale = (ChoiceSetting) settings.get(2);

        Assertions.assertNull(locale.getSharedName());
        Assertions.assertEquals("ko-KR", locale.getDefaultValue());
        Assertions.assertEquals(List.of("en-US", "ko-KR"), locale.getOptions());
        Assertions.assertTrue(manifest.checkEngine(EngineVersion.CURRENT).isValid());
    }

    @Test
    void parse_should_reject_broken_json() {
        ValidationResult result = parseInvalid("{\"format\": 1, \"id\": ");

        Assertions.assertTrue(result.hasProblem(ProblemCode.MANIFEST_SYNTAX), result.describe());
        Assertions.assertTrue(parseInvalid("[1, 2]").hasProblem(ProblemCode.FIELD_TYPE));
    }

    @Test
    void parse_should_report_every_missing_required_field() {
        ValidationResult result = parseInvalid("{}");
        List<String> subjects = result.getProblems().stream().filter(problem -> problem.getCode() == ProblemCode.FIELD_MISSING)
                                      .map(ValidationProblem::getSubject).toList();

        Assertions.assertEquals(List.of("format", "id", "version", "engine", "entry", "name"), subjects);
    }

    @Test
    void parse_should_reject_bad_id_version_engine_and_entry_syntax() {
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("\"example-mod\"", "\"Example\"")).hasProblem(ProblemCode.ID_SYNTAX));
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("\"example-mod\"", "\"example--mod\"")).hasProblem(ProblemCode.ID_SYNTAX));
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("\"example-mod\"", "\"../mod\"")).hasProblem(ProblemCode.ID_SYNTAX));
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("\"1.2.3-beta.1\"", "\"1.2\"")).hasProblem(ProblemCode.VERSION_SYNTAX));
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("\"engine\": \"0.1\"", "\"engine\": \"one\"")).hasProblem(ProblemCode.ENGINE_SYNTAX));
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("org.example.ExampleMod", "org.example.Example Mod")).hasProblem(ProblemCode.ENTRY_SYNTAX));
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("\"format\": 1", "\"format\": 2")).hasProblem(ProblemCode.FORMAT_UNSUPPORTED));
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("\"meta/icon.png\"", "\"../icon.png\"")).hasProblem(ProblemCode.FIELD_VALUE));
        Assertions.assertTrue(parseInvalid(VALID_MANIFEST.replace("\"ko\": \"예제\"", "\"Korean\": \"예제\"")).hasProblem(ProblemCode.FIELD_VALUE));
    }

    @Test
    void parse_should_reject_invalid_localized_message() {
        ValidationResult result = parseInvalid(VALID_MANIFEST.replace("\"message\": {\"en\": \"Choose the game folder.\", \"ko\": \"게임 폴더를 선택하세요.\"}",
                                                                      "\"message\": \"Choose the game folder.\""));

        Assertions.assertEquals(List.of("settings[0].message"), result.getProblems().stream().map(ValidationProblem::getSubject).toList());
        Assertions.assertTrue(result.hasProblem(ProblemCode.FIELD_TYPE));
    }

    @Test
    void parse_should_reject_invalid_detect_and_shared_fields_of_a_directory_setting() {
        ValidationResult badShared = parseInvalid(VALID_MANIFEST.replace("\"shared\": \"example-game\"", "\"shared\": \"Example Game\""));
        ValidationResult badEntry = parseInvalid(VALID_MANIFEST.replace("\"uninstallEntries\": [\"Example Game\"]",
                                                                        "\"uninstallEntries\": [\"Game & del\"]"));
        ValidationResult badFolder = parseInvalid(VALID_MANIFEST.replace("\"programFolders\": [\"Example Game\"]",
                                                                         "\"programFolders\": [\"../Game\"]"));
        ValidationResult sameShared = parseInvalid(VALID_MANIFEST.replace("\"label\": {\"en\": \"Data\"}",
                                                                          "\"label\": {\"en\": \"Data\"}, \"shared\": \"example-game\""));

        Assertions.assertEquals(List.of("settings[0].shared"), badShared.getProblems().stream().map(ValidationProblem::getSubject).toList());
        Assertions.assertEquals(List.of("settings[0].detect.uninstallEntries"), badEntry.getProblems().stream().map(ValidationProblem::getSubject).toList());
        Assertions.assertEquals(List.of("settings[0].detect.programFolders"), badFolder.getProblems().stream().map(ValidationProblem::getSubject).toList());
        Assertions.assertEquals(List.of("settings[1].shared"), sameShared.getProblems().stream().map(ValidationProblem::getSubject).toList());
        Assertions.assertTrue(sameShared.hasProblem(ProblemCode.SETTING_DUPLICATE), sameShared.describe());
    }

    @Test
    void parse_should_read_the_tools_in_listed_order() throws ManifestException {
        ModManifest manifest = ModManifestReader.parse(createManifestWithTools("""
                                                                               [{"id": "script", "main": "org.example.tool.ScriptTool"},
                                                                                {"id": "map-check", "main": "org.example.tool.MapCheck"}]
                                                                               """).getBytes(StandardCharsets.UTF_8));
        ModTool script = manifest.findTool("script");

        Assertions.assertEquals(List.of("script", "map-check"), manifest.getTools().stream().map(ModTool::getId).toList());
        Assertions.assertNotNull(script);
        Assertions.assertEquals("org.example.tool.ScriptTool", script.getMain());
        Assertions.assertNull(manifest.findTool("missing"));
        Assertions.assertEquals(List.of(), ModManifestReader.parse(VALID_MANIFEST.getBytes(StandardCharsets.UTF_8)).getTools());
    }

    @Test
    void parse_should_reject_invalid_tools() {
        Assertions.assertEquals(List.of("tools"), listToolSubjects("{\"id\": \"script\"}"));
        Assertions.assertEquals(List.of("tools[0]"), listToolSubjects("[\"script\"]"));
        Assertions.assertEquals(List.of("tools[0].main"), listToolSubjects("[{\"id\": \"script\"}]"));
        Assertions.assertEquals(List.of("tools[0].id"), listToolSubjects("[{\"id\": \"Script Tool\", \"main\": \"org.example.ScriptTool\"}]"));
        Assertions.assertEquals(List.of("tools[0].main"), listToolSubjects("[{\"id\": \"script\", \"main\": \"org.example.Script Tool\"}]"));
        Assertions.assertEquals(List.of("tools[1].id"), listToolSubjects("[{\"id\": \"script\", \"main\": \"org.example.First\"}, " +
                                                                         "{\"id\": \"script\", \"main\": \"org.example.Second\"}]"));
    }

    @Test
    void check_engine_should_reject_another_major_and_a_higher_minor_version() throws ManifestException {
        ModManifest higherMinor = ModManifestReader.parse(VALID_MANIFEST.replace("\"engine\": \"0.1\"", "\"engine\": \"0.2\"")
                                                                        .getBytes(StandardCharsets.UTF_8));
        ModManifest otherMajor = ModManifestReader.parse(VALID_MANIFEST.replace("\"engine\": \"0.1\"", "\"engine\": \"1.0\"")
                                                                       .getBytes(StandardCharsets.UTF_8));

        Assertions.assertTrue(higherMinor.checkEngine(EngineVersion.parse("0.1")).hasProblem(ProblemCode.ENGINE_UNSUPPORTED));
        Assertions.assertTrue(higherMinor.checkEngine(EngineVersion.parse("0.3")).isValid());
        Assertions.assertTrue(otherMajor.checkEngine(EngineVersion.parse("0.9")).hasProblem(ProblemCode.ENGINE_UNSUPPORTED));
    }

    @Test
    void read_should_return_the_manifest_and_the_named_images(@TempDir @NotNull Path directory) throws IOException, ManifestException {
        Path jar = directory.resolve("example.jar");

        TestJarUtils.write(jar, createEntries(true, true));

        ModArchive archive = ModManifestReader.read(jar);

        Assertions.assertEquals("example-mod", archive.getManifest().getId());
        Assertions.assertArrayEquals(TestJarUtils.PNG_CONTENTS, archive.getImage(ICON_ENTRY));
        Assertions.assertArrayEquals(TestJarUtils.PNG_CONTENTS, archive.getImage(SCREENSHOT_ENTRY));
        Assertions.assertNull(archive.getImage("meta/banner.png"));
    }

    @Test
    void read_should_reject_missing_and_non_png_images(@TempDir @NotNull Path directory) throws IOException {
        Path jar = directory.resolve("example.jar");
        Map<String, byte[]> entries = createEntries(false, true);

        entries.put(SCREENSHOT_ENTRY, "not an image".getBytes(StandardCharsets.UTF_8));
        TestJarUtils.write(jar, entries);

        ValidationResult result = Assertions.assertThrows(ManifestException.class, () -> ModManifestReader.read(jar)).getResult();

        Assertions.assertEquals(List.of(ICON_ENTRY, SCREENSHOT_ENTRY), result.getProblems().stream().map(ValidationProblem::getSubject).toList());
        Assertions.assertTrue(result.getProblems().stream().allMatch(problem -> problem.getCode() == ProblemCode.IMAGE_MISSING));
    }

    @Test
    void read_should_reject_a_jar_without_manifest_and_a_file_that_is_not_a_jar(@TempDir @NotNull Path directory) throws IOException {
        Path withoutManifest = directory.resolve("empty.jar");
        Path notJar = directory.resolve("text.jar");

        TestJarUtils.write(withoutManifest, Map.of(ICON_ENTRY, TestJarUtils.PNG_CONTENTS));
        Files.writeString(notJar, "not a zip file");

        Assertions.assertTrue(Assertions.assertThrows(ManifestException.class, () -> ModManifestReader.read(withoutManifest))
                                        .getResult().hasProblem(ProblemCode.MANIFEST_MISSING));
        Assertions.assertTrue(Assertions.assertThrows(ManifestException.class, () -> ModManifestReader.read(notJar))
                                        .getResult().hasProblem(ProblemCode.ARCHIVE_UNREADABLE));
    }

    @Test
    void parse_should_read_no_notices_when_the_field_is_absent() throws ManifestException {
        Assertions.assertEquals(List.of(), ModManifestReader.parse(VALID_MANIFEST.getBytes(StandardCharsets.UTF_8)).getNotices());
    }

    @Test
    void parse_should_read_an_english_only_notice_for_every_language() throws ManifestException {
        String manifest = createManifestWithNotices("[{\"en\": \"Art: Example Artist, CC BY 4.0.\"}]");
        List<LocalizedText> notices = ModManifestReader.parse(manifest.getBytes(StandardCharsets.UTF_8)).getNotices();

        Assertions.assertEquals(1, notices.size());
        Assertions.assertEquals("Art: Example Artist, CC BY 4.0.", notices.getFirst().get("en"));
        Assertions.assertEquals("Art: Example Artist, CC BY 4.0.", notices.getFirst().get("ko"));
    }

    @Test
    void parse_should_read_english_and_korean_notices_in_listed_order() throws ManifestException {
        String manifest = createManifestWithNotices("""
                                                    [{"en": "Example Game is a trademark of Example Company.", "ko": "Example Game은 Example Company의 상표입니다."},
                                                       {"en": "Music: Example Band.", "ko": "음악: Example Band."}]""");
        List<LocalizedText> notices = ModManifestReader.parse(manifest.getBytes(StandardCharsets.UTF_8)).getNotices();

        Assertions.assertEquals(2, notices.size());
        Assertions.assertEquals("Example Game is a trademark of Example Company.", notices.get(0).get("en"));
        Assertions.assertEquals("Example Game은 Example Company의 상표입니다.", notices.get(0).get("ko"));
        Assertions.assertEquals("음악: Example Band.", notices.get(1).get("ko-KR"));
    }

    @Test
    void parse_should_reject_malformed_notices() {
        Assertions.assertTrue(parseInvalid(createManifestWithNotices("\"Example Game is a trademark.\"")).hasProblem(ProblemCode.FIELD_TYPE));
        Assertions.assertEquals(List.of("notices"), listSubjects("\"Example Game is a trademark.\""));
        Assertions.assertEquals(List.of("notices[1]"), listSubjects("[{\"en\": \"Fine.\"}, \"Not localized.\"]"));
        Assertions.assertEquals(List.of("notices[0]", "notices[1]"), listSubjects("[{}, null]"));
        Assertions.assertEquals(List.of("notices[0].en"), listSubjects("[{\"en\": \" \"}]"));
        Assertions.assertEquals(List.of("notices[0].English"), listSubjects("[{\"English\": \"Text.\"}]"));
    }
}
