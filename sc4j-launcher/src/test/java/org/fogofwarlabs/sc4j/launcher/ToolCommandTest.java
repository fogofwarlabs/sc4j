package org.fogofwarlabs.sc4j.launcher;

import org.fogofwarlabs.sc4j.launcher.launch.RuntimeCommandLine;
import org.fogofwarlabs.sc4j.launcher.launch.ToolCommandLine;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.test.TestJarUtils;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the launcher command {@code tool}: the lists of the mods' tools, the refusal of an unknown mod or tool and of a mod with an error,
 * and a tool run in a Java process of its own with its arguments, the install property, the mod's class path and its exit status.
 */
final class ToolCommandTest {
    private static final @NotNull String MANIFEST = """
                                                    {"format": 1, "id": "%s", "name": {"en": "Example"}, "version": "1.0.0", "engine": "%s",
                                                     "entry": "org.example.ExampleMod", "tools": %s}
                                                    """;
    private static final @NotNull String ECHO_TOOLS = "[{\"id\": \"echo\", \"main\": \"" + EchoTool.class.getName() + "\"}," +
                                                      " {\"id\": \"other\", \"main\": \"org.example.Other\"}]";
    private static final @NotNull String LAUNCH_TEXT = """
                                                       -XX:-UsePerfData
                                                       -Djava.io.tmpdir=${APP_HOME}/.cache/tmp
                                                       -cp
                                                       ${APP_HOME}/lib/*
                                                       main.Main
                                                       """;
    private static final int TOOL_STATUS = 7;
    private static final int USAGE_STATUS = 2;
    private static final int FAILURE_STATUS = 1;

    /**
     * Writes the install property and its other arguments to the file its first argument names and exits with the status of its second.
     */
    public static final class EchoTool {
        /**
         * Runs the tool.
         *
         * @param arguments the output file, the exit status and the arguments to write
         * @throws IOException if the file cannot be written
         */
        public static void main(@NotNull String @NotNull [] arguments) throws IOException {
            List<String> lines = new ArrayList<>();

            lines.add(String.valueOf(System.getProperty(InstallLayout.INSTALL_PROPERTY)));
            lines.addAll(List.of(arguments).subList(2, arguments.length));
            Files.write(Path.of(arguments[0]), lines, StandardCharsets.UTF_8);
            System.exit(Integer.parseInt(arguments[1]));
        }


        private EchoTool() {
        }
    }

    /**
     * Collects what one command printed.
     */
    private static final class Output {
        private final @NotNull ByteArrayOutputStream output;
        private final @NotNull ByteArrayOutputStream errors;

        /**
         * Creates an {@link Output} instance.
         */
        private Output() {
            this.output = new ByteArrayOutputStream();
            this.errors = new ByteArrayOutputStream();
        }

        private int run(@NotNull InstallLayout install, @NotNull List<@NotNull String> arguments, ToolCommand.@NotNull ToolStarter starter) {
            return ToolCommand.run(install, arguments, new PrintStream(this.output, true, StandardCharsets.UTF_8),
                                   new PrintStream(this.errors, true, StandardCharsets.UTF_8), starter);
        }

        private @NotNull String getOutput() {
            return this.output.toString(StandardCharsets.UTF_8);
        }

        private @NotNull String getErrors() {
            return this.errors.toString(StandardCharsets.UTF_8);
        }
    }

    private static void writeMod(@NotNull InstallLayout install, @NotNull String jarName, @NotNull String modId, @NotNull String engine,
                                 @NotNull String tools) throws IOException {
        Files.createDirectories(install.getModsDirectory());
        TestJarUtils.write(install.getModsDirectory().resolve(jarName), TestJarUtils.createEntries(MANIFEST.formatted(modId, engine, tools), EchoTool.class));
    }

    private static void writeRuntime(@NotNull InstallLayout install) throws IOException {
        Files.createDirectories(install.getRuntimeDirectory().resolve("lib"));
        Files.createDirectories(install.getRuntimeDirectory().resolve("bin"));
        Files.writeString(install.getRuntimeDirectory().resolve("bin").resolve(RuntimeCommandLine.LAUNCH_FILE), LAUNCH_TEXT);
    }

    private static int refuseStart(@NotNull ToolCommandLine commandLine) {
        throw new AssertionError("No tool may start.");
    }

    @Test
    void run_should_list_the_tools_of_every_mod_or_of_one(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        Output all = new Output();
        Output one = new Output();
        Output none = new Output();

        writeMod(install, "example.jar", "example", EngineVersion.CURRENT.toString(), ECHO_TOOLS);
        writeMod(install, "plain.jar", "plain", EngineVersion.CURRENT.toString(), "[]");

        Assertions.assertEquals(0, all.run(install, List.of(), ToolCommandTest::refuseStart));
        Assertions.assertEquals(0, one.run(install, List.of("example"), ToolCommandTest::refuseStart));
        Assertions.assertEquals(0, none.run(install, List.of("plain"), ToolCommandTest::refuseStart));
        Assertions.assertTrue(all.getOutput().contains("example              echo                 " + EchoTool.class.getName()), all.getOutput());
        Assertions.assertTrue(all.getOutput().contains("example              other                org.example.Other"), all.getOutput());
        Assertions.assertEquals(one.getOutput(), all.getOutput());
        Assertions.assertEquals("The mod plain has no tools.", none.getOutput().strip());
    }

    @Test
    void run_should_refuse_an_unknown_mod_or_tool_and_a_mod_with_an_error(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        Output unknownMod = new Output();
        Output unknownTool = new Output();
        Output broken = new Output();

        writeMod(install, "example.jar", "example", EngineVersion.CURRENT.toString(), ECHO_TOOLS);
        writeMod(install, "future.jar", "future", "99.0", ECHO_TOOLS);

        Assertions.assertEquals(USAGE_STATUS, unknownMod.run(install, List.of("missing", "echo"), ToolCommandTest::refuseStart));
        Assertions.assertEquals(USAGE_STATUS, unknownTool.run(install, List.of("example", "missing"), ToolCommandTest::refuseStart));
        Assertions.assertEquals(FAILURE_STATUS, broken.run(install, List.of("future", "echo"), ToolCommandTest::refuseStart));
        Assertions.assertTrue(unknownMod.getErrors().startsWith("No mod missing is installed in "), unknownMod.getErrors());
        Assertions.assertTrue(unknownTool.getErrors().startsWith("The mod example has no tool missing."), unknownTool.getErrors());
        Assertions.assertTrue(unknownTool.getErrors().contains("example              echo"), unknownTool.getErrors());
        Assertions.assertTrue(broken.getErrors().contains("engine.unsupported"), broken.getErrors());
    }

    @Test
    void run_should_pass_the_tool_arguments_and_return_the_tool_status(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        Output missingRuntime = new Output();
        Output ran = new Output();
        List<ToolCommandLine> started = new ArrayList<>();

        writeMod(install, "example.jar", "example", EngineVersion.CURRENT.toString(), ECHO_TOOLS);

        Assertions.assertEquals(FAILURE_STATUS, missingRuntime.run(install, List.of("example", "echo"), ToolCommandTest::refuseStart));
        Assertions.assertTrue(missingRuntime.getErrors().startsWith("SC4J files are incomplete:"), missingRuntime.getErrors());

        writeRuntime(install);

        Assertions.assertEquals(TOOL_STATUS, ran.run(install, List.of("example", "echo", "check", "--all"), commandLine -> {
            started.add(commandLine);

            return TOOL_STATUS;
        }));
        List<String> arguments = started.getFirst().getArguments();
        int classPathIndex = arguments.indexOf("-cp");

        Assertions.assertEquals(1, started.size());
        Assertions.assertEquals(List.of(EchoTool.class.getName(), "check", "--all"), arguments.subList(classPathIndex + 2, arguments.size()));
        Assertions.assertTrue(arguments.get(classPathIndex + 1).endsWith(install.getModsDirectory().resolve("example.jar").toString()),
                              arguments.toString());
    }

    @Test
    void start_process_should_run_the_tool_with_its_class_path_and_forward_its_status(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory.resolve("install"));
        Path result = directory.resolve("result.txt");
        Output ran = new Output();

        writeRuntime(install);
        writeMod(install, "example.jar", "example", EngineVersion.CURRENT.toString(), ECHO_TOOLS);

        int status = ran.run(install, List.of("example", "echo", result.toString(), Integer.toString(TOOL_STATUS), "first", "with space"),
                             ToolCommand::startProcess);

        Assertions.assertEquals(TOOL_STATUS, status, ran.getErrors());
        Assertions.assertEquals(List.of(install.getRoot().toString(), "first", "with space"), Files.readAllLines(result, StandardCharsets.UTF_8));
        Assertions.assertTrue(Files.isDirectory(install.getRuntimeDirectory().resolve(".cache").resolve("tmp")));
    }
}
