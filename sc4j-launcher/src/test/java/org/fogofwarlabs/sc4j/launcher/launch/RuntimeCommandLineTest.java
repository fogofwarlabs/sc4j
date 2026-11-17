package org.fogofwarlabs.sc4j.launcher.launch;

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
 * Tests the runtime command line that the launcher builds: the Java program with the runtime's launch file, every install path of the mod and
 * the Beacon.Link account folder, seeds by the one seed rule, the shared peers file and the program files the install lacks.
 */
final class RuntimeCommandLineTest {
    private static final @NotNull String ACCOUNT_ID = "0123456789abcdef";
    private static final @NotNull String NODE_LIST = "{\"format\": 1, \"nodes\": []}";
    private static final @NotNull String LAUNCH_TEXT = """
                                                       -XX:-UsePerfData
                                                       -Djava.io.tmpdir=${APP_HOME}/.cache/tmp
                                                       
                                                       -cp
                                                       ${APP_HOME}/lib/*
                                                       main.Main
                                                       """;

    private static @NotNull Path getAccountDirectory(@NotNull InstallLayout install) {
        return install.getBeaconLinkDirectory().resolve("accounts").resolve(ACCOUNT_ID);
    }

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
    void create_should_pass_every_install_path_of_the_mod(@TempDir @NotNull Path directory) throws IOException {
        Path root = directory.resolve("Games").resolve("SC4J Install");
        InstallLayout install = InstallLayout.of(root);
        Path jar = root.resolve("mods").resolve("my mod.jar");
        Path userData = root.resolve("userdata");
        Path runtime = root.resolve("runtime");
        Path java = writePrograms(install, root.resolve("java"), "java.exe");

        Files.createDirectories(install.getSeedsFile().getParent());
        Files.writeString(install.getSeedsFile(), NODE_LIST);

        RuntimeCommandLine commandLine = RuntimeCommandLine.create(install, "my-mod", jar, getAccountDirectory(install), root.resolve("java"), true);

        Assertions.assertEquals(List.of(java.toString(),
                                        "-XX:-UsePerfData", "-Djava.io.tmpdir=" + runtime + "/.cache/tmp", "-cp", runtime + "/lib/*", "main.Main",
                                        "--install", root.toString(),
                                        "--mod", jar.toString(),
                                        "--settings", userData.resolve("settings").resolve("my-mod.json").toString(),
                                        "--storage", userData.resolve("mods").resolve("my-mod").toString(),
                                        "--log", userData.resolve("logs").resolve("my-mod").toString(),
                                        "--account", userData.resolve("beaconlink").resolve("accounts").resolve(ACCOUNT_ID).toString(),
                                        "--seeds", root.resolve("launcher").resolve("seeds.json").toString(),
                                        "--peers", userData.resolve("beaconlink").resolve("peers.json").toString()),
                                commandLine.getArguments());
        Assertions.assertEquals(root, commandLine.getWorkingDirectory());
        Assertions.assertEquals(userData.resolve("settings").resolve("my-mod.json"), commandLine.getSettingsFile());
        Assertions.assertEquals(userData.resolve("logs").resolve("my-mod"), commandLine.getLogDirectory());
        Assertions.assertNull(commandLine.getMissingProgram());
        Assertions.assertEquals(Path.of(runtime + "/.cache/tmp"), commandLine.getTemporaryDirectory());
    }

    @Test
    void create_should_pass_the_user_node_list_once_saved_and_no_seeds_without_a_list(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        Path jar = directory.resolve("mods").resolve("demo.jar");

        Assertions.assertFalse(RuntimeCommandLine.create(install, "demo", jar, getAccountDirectory(install), directory, true).getArguments()
                                                 .contains("--seeds"),
                               "without a list the peers file is the only seed source");

        Files.createDirectories(install.getNodesFile().getParent());
        Files.writeString(install.getNodesFile(), NODE_LIST);

        List<String> arguments = RuntimeCommandLine.create(install, "demo", jar, getAccountDirectory(install), directory, true).getArguments();

        Assertions.assertEquals(install.getNodesFile().toString(), arguments.get(arguments.indexOf("--seeds") + 1));
    }

    @Test
    void create_should_use_the_unix_java_program_outside_windows(@TempDir @NotNull Path directory) throws IOException {
        Path jar = directory.resolve("mods").resolve("demo.jar");
        InstallLayout install = InstallLayout.of(directory);
        Path java = writePrograms(install, directory.resolve("jdk"), "java");
        RuntimeCommandLine commandLine = RuntimeCommandLine.create(install, "demo", jar, getAccountDirectory(install), directory.resolve("jdk"),
                                                                   false);

        Assertions.assertEquals(java, commandLine.getProgram());
        Assertions.assertNull(commandLine.getMissingProgram());
    }

    @Test
    void create_should_report_each_missing_program_file(@TempDir @NotNull Path directory) throws IOException {
        Path jar = directory.resolve("mods").resolve("demo.jar");
        InstallLayout install = InstallLayout.of(directory);
        Path javaHome = directory.resolve("java");
        Path java = writePrograms(install, javaHome, "java.exe");
        Path launchFile = install.getRuntimeDirectory().resolve("bin").resolve(RuntimeCommandLine.LAUNCH_FILE);
        Path libraries = install.getRuntimeDirectory().resolve("lib");

        Files.delete(libraries);
        Assertions.assertEquals(libraries, RuntimeCommandLine.create(install, "demo", jar, getAccountDirectory(install), javaHome, true)
                                                             .getMissingProgram());

        Files.delete(launchFile);
        Assertions.assertEquals(launchFile, RuntimeCommandLine.create(install, "demo", jar, getAccountDirectory(install), javaHome, true)
                                                              .getMissingProgram());

        Files.delete(java);
        Assertions.assertEquals(java, RuntimeCommandLine.create(install, "demo", jar, getAccountDirectory(install), javaHome, true)
                                                        .getMissingProgram());
    }

    @Test
    void describe_should_quote_arguments_with_spaces(@TempDir @NotNull Path directory) {
        Path root = directory.resolve("SC4J Install");
        Path jar = root.resolve("mods").resolve("my-mod.jar");
        InstallLayout install = InstallLayout.of(root);
        RuntimeCommandLine commandLine = RuntimeCommandLine.create(install, "my-mod", jar, getAccountDirectory(install), root.resolve("java"), true);
        String text = commandLine.describe();
        String java = root.resolve("java").resolve("bin").resolve("java.exe").toString();

        Assertions.assertTrue(text.startsWith("\"" + java + "\" --install \"" + root + "\" --mod "), text);
        Assertions.assertEquals(15, commandLine.getArguments().size());
    }
}
