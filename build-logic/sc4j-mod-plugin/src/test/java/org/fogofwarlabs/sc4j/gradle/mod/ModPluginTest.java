package org.fogofwarlabs.sc4j.gradle.mod;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Tests the mod plugin on a throwaway mod: the mod id it reads from {@code sc4j-mod.json}, the versions it writes into it, the source
 * manifests it refuses and the mod API every mod compiles against.
 */
final class ModPluginTest {
    private static final @NotNull String MOD_VERSION = "1.2.0-SNAPSHOT";
    private static final @NotNull String SOURCE_MANIFEST = "src/main/resources/sc4j-mod.json";
    private static final @NotNull String BUILT_MANIFEST = "build/resources/main/sc4j-mod.json";
    private static final @NotNull String SETTINGS_SCRIPT = "rootProject.name = 'test-mod'\n";
    private static final @NotNull String BUILD_SCRIPT = """
                                                        plugins {
                                                            id 'org.fogofwarlabs.sc4j.mod'
                                                        }
                                                        
                                                        version = '%s'
                                                        %s
                                                        tasks.register('printJarName') {
                                                            def jarName = tasks.named('jar', Jar).flatMap { task -> task.archiveFileName }
                                                            doLast {
                                                                println('Mod jar: ' + jarName.get())
                                                            }
                                                        }
                                                        
                                                        tasks.register('printCompileModules') {
                                                            def modules = provider {
                                                                configurations.compileClasspath.allDependencies.collect { dependency ->
                                                                    dependency.name
                                                                }.sort()
                                                            }
                                                            doLast {
                                                                println('Compile modules: ' + modules.get().join(' '))
                                                            }
                                                        }
                                                        """;
    private static final @NotNull String MOD_ID_BLOCK = """
                                                        
                                                        sc4jMod {
                                                            id = '%s'
                                                        }
                                                        """;
    private static final @NotNull String PRINT_JAR_NAME_TASK = "printJarName";
    private static final @NotNull String PRINT_COMPILE_MODULES_TASK = "printCompileModules";
    private static final @NotNull String PROCESS_RESOURCES_TASK = "processResources";

    @Test
    void engine_api_version_should_be_the_major_and_minor_version_of_the_engine_version() throws IOException {
        Properties engine = readEngineProperties();
        String[] parts = engine.getProperty("version").split("[.-]");

        Assertions.assertEquals(parts[0] + "." + parts[1], engine.getProperty("engineApi"));
    }

    @Test
    void process_resources_should_write_the_project_version_and_the_engine_api_version_after_the_opening_brace(@TempDir @NotNull Path project) throws IOException {
        String engineApi = readEngineProperties().getProperty("engineApi");

        writeProject(project, MOD_VERSION, "{\n  \"format\": 1,\n  \"id\": \"test-mod\"\n}\n");
        createRunner(project).build();

        Assertions.assertEquals("{\n  \"version\": \"" + MOD_VERSION + "\",\n  \"engine\": \"" + engineApi + "\",\n  \"format\": 1,\n"
                                + "  \"id\": \"test-mod\"\n}\n", Files.readString(project.resolve(BUILT_MANIFEST), StandardCharsets.UTF_8));
    }

    @Test
    void process_resources_should_write_the_versions_into_a_manifest_on_one_line(@TempDir @NotNull Path project) throws IOException {
        String engineApi = readEngineProperties().getProperty("engineApi");

        writeProject(project, MOD_VERSION, "{\"format\": 1, \"id\": \"test-mod\"}");
        createRunner(project).build();

        Assertions.assertEquals("{\"version\": \"" + MOD_VERSION + "\", \"engine\": \"" + engineApi + "\", \"format\": 1, \"id\": \"test-mod\"}",
                                Files.readString(project.resolve(BUILT_MANIFEST), StandardCharsets.UTF_8));
    }

    @Test
    void process_resources_should_refuse_a_source_manifest_with_a_version_or_an_engine(@TempDir @NotNull Path project) throws IOException {
        writeProject(project, MOD_VERSION, "{\n  \"format\": 1,\n  \"id\": \"test-mod\",\n  \"version\": \"1.0.0\"\n}\n");
        BuildResult version = createRunner(project).buildAndFail();

        writeProject(project, MOD_VERSION, "{\n  \"format\": 1,\n  \"id\": \"test-mod\",\n  \"engine\": \"0.1\"\n}\n");
        BuildResult engine = createRunner(project).buildAndFail();

        Assertions.assertTrue(version.getOutput().contains("must not have the field \"version\": the mod plugin writes it."), version.getOutput());
        Assertions.assertTrue(engine.getOutput().contains("must not have the field \"engine\": the mod plugin writes it."), engine.getOutput());
    }

    @Test
    void process_resources_should_refuse_a_source_manifest_without_a_format_the_engine_reads(@TempDir @NotNull Path project) throws IOException {
        Properties engine = readEngineProperties();
        int format = Integer.parseInt(engine.getProperty("manifestFormat"));

        writeProject(project, MOD_VERSION, "{\n  \"id\": \"test-mod\"\n}\n");
        BuildResult missing = createRunner(project).buildAndFail();

        writeProject(project, MOD_VERSION, "{\n  \"format\": " + (format + 1) + ",\n  \"id\": \"test-mod\"\n}\n");
        BuildResult other = createRunner(project).buildAndFail();

        Assertions.assertTrue(missing.getOutput().contains("The manifest sc4j-mod.json has no \"format\": set it to " + format + "."),
                              missing.getOutput());
        Assertions.assertTrue(other.getOutput().contains("The manifest sc4j-mod.json has the format " + (format + 1) + "; SC4J "
                                                         + engine.getProperty("version") + " reads format " + format + "."), other.getOutput());
    }

    @Test
    void process_resources_should_refuse_a_mod_project_without_a_version(@TempDir @NotNull Path project) throws IOException {
        writeProject(project, "unspecified", "{\n  \"format\": 1,\n  \"id\": \"test-mod\"\n}\n");
        BuildResult result = createRunner(project).buildAndFail();

        Assertions.assertTrue(result.getOutput().contains("The mod project test-mod has no version"), result.getOutput());
    }

    @Test
    void jar_should_be_named_after_the_id_of_the_source_manifest(@TempDir @NotNull Path project) throws IOException {
        writeProject(project, MOD_VERSION, null, "{\n  \"format\": 1,\n  \"id\": \"other-mod\"\n}\n");
        BuildResult result = createRunner(project, PRINT_JAR_NAME_TASK).build();

        Assertions.assertTrue(result.getOutput().contains("Mod jar: other-mod.jar"), result.getOutput());
    }

    @Test
    void mod_id_should_be_refused_when_it_is_not_the_id_of_the_manifest(@TempDir @NotNull Path project) throws IOException {
        String manifest = "{\n  \"format\": 1,\n  \"id\": \"test-mod\"\n}\n";

        writeProject(project, MOD_VERSION, "test-mod", manifest);
        BuildResult same = createRunner(project, PRINT_JAR_NAME_TASK, PROCESS_RESOURCES_TASK).build();

        writeProject(project, MOD_VERSION, "other-mod", manifest);
        BuildResult other = createRunner(project, PROCESS_RESOURCES_TASK).buildAndFail();

        Assertions.assertTrue(same.getOutput().contains("Mod jar: test-mod.jar"), same.getOutput());
        Assertions.assertTrue(other.getOutput().contains("The manifest sc4j-mod.json has the id test-mod but sc4jMod.id is other-mod."),
                              other.getOutput());
    }

    @Test
    void a_mod_without_a_manifest_or_a_manifest_id_should_fail_the_build(@TempDir @NotNull Path project) throws IOException {
        String manifestWithoutId = "{\n  \"format\": 1\n}\n";

        writeProject(project, MOD_VERSION, null, null);
        BuildResult missing = createRunner(project, PRINT_JAR_NAME_TASK).buildAndFail();

        writeProject(project, MOD_VERSION, null, manifestWithoutId);
        BuildResult withoutId = createRunner(project, PRINT_JAR_NAME_TASK).buildAndFail();

        writeProject(project, MOD_VERSION, "test-mod", manifestWithoutId);
        BuildResult withoutIdButModId = createRunner(project, PROCESS_RESOURCES_TASK).buildAndFail();

        Assertions.assertTrue(missing.getOutput().contains("The mod project test-mod has no manifest sc4j-mod.json in its resources."),
                              missing.getOutput());
        Assertions.assertTrue(withoutId.getOutput().contains("sc4j-mod.json has no \"id\"."), withoutId.getOutput());
        Assertions.assertTrue(withoutIdButModId.getOutput().contains("The manifest sc4j-mod.json has no \"id\"."), withoutIdButModId.getOutput());
    }

    @Test
    void every_mod_should_compile_against_the_mod_api(@TempDir @NotNull Path project) throws IOException {
        List<String> modApi = Arrays.stream(readEngineProperties().getProperty("modApi").split(",")).sorted().toList();
        BuildResult result;

        writeProject(project, MOD_VERSION, "{\n  \"format\": 1,\n  \"id\": \"test-mod\"\n}\n");
        result = createRunner(project, PRINT_COMPILE_MODULES_TASK).build();

        Assertions.assertTrue(modApi.contains("sc4j-engine"), modApi.toString());
        Assertions.assertTrue(result.getOutput().contains("Compile modules: " + String.join(" ", modApi)), result.getOutput());
    }

    private static @NotNull Properties readEngineProperties() throws IOException {
        Properties properties = new Properties();

        try (InputStream input = ModExtension.class.getResourceAsStream("engine.properties")) {
            Assertions.assertNotNull(input);
            properties.load(input);
        }

        return properties;
    }

    private static void writeProject(@NotNull Path project, @NotNull String version, @NotNull String manifest) throws IOException {
        writeProject(project, version, null, manifest);
    }

    private static void writeProject(@NotNull Path project, @NotNull String version, @Nullable String modId, @Nullable String manifest) throws IOException {
        Path manifestFile = project.resolve(SOURCE_MANIFEST);
        String modIdBlock = modId == null ? "" : MOD_ID_BLOCK.formatted(modId);

        Files.createDirectories(manifestFile.getParent());
        Files.writeString(project.resolve("settings.gradle"), SETTINGS_SCRIPT, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle"), BUILD_SCRIPT.formatted(version, modIdBlock), StandardCharsets.UTF_8);

        if (manifest == null) {
            Files.deleteIfExists(manifestFile);
        } else {
            Files.writeString(manifestFile, manifest, StandardCharsets.UTF_8);
        }
    }

    private static @NotNull GradleRunner createRunner(@NotNull Path project) {
        return createRunner(project, PROCESS_RESOURCES_TASK);
    }

    private static @NotNull GradleRunner createRunner(@NotNull Path project, @NotNull String @NotNull ... tasks) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(tasks);
    }
}
