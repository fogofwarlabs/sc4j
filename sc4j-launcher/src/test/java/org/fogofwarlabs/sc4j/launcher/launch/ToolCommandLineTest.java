package org.fogofwarlabs.sc4j.launcher.launch;

import org.fogofwarlabs.sc4j.manifest.ModTool;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests the command line of a mod tool: the runtime's Java program and JVM options, the install property, the class path of the runtime's
 * libraries and the mod jar, the tool's main class and arguments, and the program files the install lacks.
 */
final class ToolCommandLineTest {
    private static final @NotNull String LAUNCH_TEXT = """
                                                       -XX:-UsePerfData
                                                       -Djava.io.tmpdir=${APP_HOME}/.cache/tmp
                                                       
                                                       -cp
                                                       ${APP_HOME}/lib/*
                                                       main.Main
                                                       """;
    private static final @NotNull ModTool TOOL = new ModTool("script", "org.example.tool.ScriptTool");

    private static @NotNull Path writePrograms(@NotNull InstallLayout install, @NotNull Path javaHome, @NotNull String javaName) throws IOException {
        Path java = javaHome.resolve("bin").resolve(javaName);

        Files.createDirectories(java.getParent());
        Files.writeString(java, "");
        Files.createDirectories(install.getRuntimeDirectory().resolve("lib"));
        Files.createDirectories(install.getRuntimeDirectory().resolve("bin"));
        Files.writeString(install.getRuntimeDirectory().resolve("bin").resolve(RuntimeCommandLine.LAUNCH_FILE), LAUNCH_TEXT);

        return java;
    }

    @Test
    void create_should_run_the_main_class_with_the_runtime_options_and_the_class_path_of_the_mod(@TempDir @NotNull Path directory) throws IOException {
        Path root = directory.resolve("SC4J Install");
        InstallLayout install = InstallLayout.of(root);
        Path runtime = root.resolve("runtime");
        Path jar = root.resolve("mods").resolve("my-mod.jar");
        Path java = writePrograms(install, root.resolve("java"), "java.exe");
        ToolCommandLine windows = ToolCommandLine.create(install, jar, TOOL, List.of("check", "my folder"), root.resolve("java"), true);
        ToolCommandLine unix = ToolCommandLine.create(install, jar, TOOL, List.of(), root.resolve("java"), false);

        Assertions.assertEquals(List.of(java.toString(),
                                        "-XX:-UsePerfData", "-Djava.io.tmpdir=" + runtime + "/.cache/tmp",
                                        "-Dsc4j.install=" + root,
                                        "-cp", runtime + "/lib/*;" + jar,
                                        "org.example.tool.ScriptTool", "check", "my folder"),
                                windows.getArguments());
        Assertions.assertEquals(runtime + "/lib/*:" + jar, unix.getArguments().get(unix.getArguments().indexOf("-cp") + 1));
        Assertions.assertEquals("org.example.tool.ScriptTool", unix.getArguments().getLast());
        Assertions.assertNull(windows.getMissingProgram());
        Assertions.assertEquals(Path.of(runtime + "/.cache/tmp"), windows.getTemporaryDirectory());
    }

    @Test
    void create_should_report_each_missing_program_file(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        Path jar = directory.resolve("mods").resolve("my-mod.jar");
        Path javaHome = directory.resolve("java");
        Path java = writePrograms(install, javaHome, "java.exe");
        Path launchFile = install.getRuntimeDirectory().resolve("bin").resolve(RuntimeCommandLine.LAUNCH_FILE);

        Files.writeString(launchFile, "-XX:-UsePerfData\nmain.Main\n");
        Assertions.assertEquals(launchFile, ToolCommandLine.create(install, jar, TOOL, List.of(), javaHome, true).getMissingProgram(),
                                "a launch file without a class path");
        Assertions.assertEquals(List.of(java.toString(), "-Dsc4j.install=" + directory, "-cp", jar.toString(), "org.example.tool.ScriptTool"),
                                ToolCommandLine.create(install, jar, TOOL, List.of(), javaHome, true).getArguments());

        Files.delete(java);
        Assertions.assertEquals(java, ToolCommandLine.create(install, jar, TOOL, List.of(), javaHome, true).getMissingProgram());
    }
}
