package org.fogofwarlabs.sc4j.launcher;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.catalog.ModIssue;
import org.fogofwarlabs.sc4j.launcher.catalog.ModStatus;
import org.fogofwarlabs.sc4j.launcher.launch.RuntimeCommandLine;
import org.fogofwarlabs.sc4j.launcher.launch.ToolCommandLine;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.ModTool;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Runs the launcher command {@code tool [<mod id> [<tool id> [<arguments>...]]]}: the command line tools that installed mods declare in their
 * manifests ({@code tools}).
 * <p>
 * Without a mod id it lists the tools of every installed mod, with a mod id only the tools of that mod. With a tool id it runs the tool's
 * main class in a Java process of its own ({@link ToolCommandLine}) that shares the standard input, output and error of the launcher and
 * works in the caller's current folder, and returns the tool's exit status. A mod that is not installed, a mod with an error (such as an
 * unsupported engine version or an id that two jars declare) and an unknown tool are refused with a short message.
 */
final class ToolCommand {
    /**
     * The launcher command that lists and runs mod tools.
     */
    static final @NotNull String COMMAND = "tool";

    /**
     * The usage of the command.
     */
    static final @NotNull String USAGE = "tool [<mod id> [<tool id> [<arguments>...]]]";

    private static final int MOD_ID_ARGUMENT = 0;
    private static final int TOOL_ID_ARGUMENT = 1;
    private static final int FIRST_TOOL_ARGUMENT = 2;
    private static final int SUCCESS_STATUS = 0;
    private static final int FAILURE_STATUS = 1;
    private static final int USAGE_STATUS = 2;
    private static final @NotNull String LIST_FORMAT = "%-20s %-20s %s";

    /**
     * Starts the Java process of a tool and waits for its end.
     */
    @FunctionalInterface
    interface ToolStarter {
        /**
         * Runs a tool.
         *
         * @param commandLine the tool's command line
         * @return the tool's exit status
         * @throws IOException          if the process cannot be started
         * @throws InterruptedException if the wait is interrupted
         */
        int run(@NotNull ToolCommandLine commandLine) throws IOException, InterruptedException;
    }

    /**
     * Lists or runs mod tools.
     *
     * @param install   the install
     * @param arguments the arguments after {@value #COMMAND}
     * @param output    receives the lists
     * @param errors    receives the messages of a refused command
     * @param starter   runs a tool, normally {@link #startProcess}
     * @return the tool's exit status when a tool ran, {@code 0} after a list, {@code 1} when the mods cannot be listed, the mod has an error or the tool cannot be started, {@code 2} for an unknown mod or tool
     */
    static int run(@NotNull InstallLayout install, @NotNull List<@NotNull String> arguments, @NotNull PrintStream output,
                   @NotNull PrintStream errors, @NotNull ToolStarter starter) {
        ModCatalog catalog;

        try {
            catalog = ModCatalog.scan(install, InstallationRecords.NONE);
        } catch (IOException exception) {
            errors.println(Exceptions.format("The mods folder cannot be listed: {0}", exception.getMessage()));

            return FAILURE_STATUS;
        }

        if (arguments.isEmpty()) {
            printTools(catalog, null, output);

            return SUCCESS_STATUS;
        }

        String modId = arguments.get(MOD_ID_ARGUMENT);
        ModEntry entry = findMod(catalog, modId);
        ModManifest manifest = entry == null ? null : entry.getManifest();

        if (entry == null || manifest == null) {
            errors.println(Exceptions.format("No mod {0} is installed in {1}.", modId, catalog.getModsDirectory()));

            return USAGE_STATUS;
        }

        if (arguments.size() <= TOOL_ID_ARGUMENT) {
            printTools(catalog, modId, output);

            return SUCCESS_STATUS;
        }

        String toolId = arguments.get(TOOL_ID_ARGUMENT);
        ModTool tool = manifest.findTool(toolId);

        if (tool == null) {
            errors.println(Exceptions.format("The mod {0} has no tool {1}.", modId, toolId));
            printTools(catalog, modId, errors);

            return USAGE_STATUS;
        }

        if (entry.getStatus() == ModStatus.ERROR) {
            errors.println(Exceptions.format("The mod {0} cannot run:", modId));

            for (ModIssue issue : entry.getIssues()) {
                errors.println(issue.describeDetail());
            }

            return FAILURE_STATUS;
        }

        ToolCommandLine commandLine = ToolCommandLine.create(install, entry.getJar(), tool, arguments.subList(FIRST_TOOL_ARGUMENT, arguments.size()),
                                                             RuntimeCommandLine.getLauncherJavaHome(), RuntimeCommandLine.isWindows());
        Path missingProgram = commandLine.getMissingProgram();

        if (missingProgram != null) {
            errors.println(Exceptions.format("SC4J files are incomplete: {0} is missing.", missingProgram));

            return FAILURE_STATUS;
        }

        try {
            return starter.run(commandLine);
        } catch (IOException exception) {
            errors.println(Exceptions.format("The tool cannot be started: {0}", exception.getMessage()));

            return FAILURE_STATUS;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errors.println("The tool was interrupted.");

            return FAILURE_STATUS;
        }
    }

    /**
     * Runs a tool in a Java process that shares the standard input, output and error of this process and works in its current folder, and
     * waits for its end; the JVM's temporary folder is created first.
     *
     * @param commandLine the tool's command line
     * @return the tool's exit status
     * @throws IOException          if the temporary folder cannot be created or the process cannot be started
     * @throws InterruptedException if the wait is interrupted; the process is stopped then
     */
    static int startProcess(@NotNull ToolCommandLine commandLine) throws IOException, InterruptedException {
        Path temporaryDirectory = commandLine.getTemporaryDirectory();

        if (temporaryDirectory != null) {
            Files.createDirectories(temporaryDirectory);
        }

        Process process = new ProcessBuilder(commandLine.getArguments()).inheritIO().start();

        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            process.destroy();

            throw exception;
        }
    }

    /**
     * Finds the installed mod with an id; with several jars of the id, the first one, which has an error.
     */
    private static @Nullable ModEntry findMod(@NotNull ModCatalog catalog, @NotNull String modId) {
        for (ModEntry entry : catalog.getEntries()) {
            ModManifest manifest = entry.getManifest();

            if (manifest != null && manifest.getId().equals(modId)) {
                return entry;
            }
        }

        return null;
    }

    /**
     * Prints one line per tool, with its mod id, its tool id and its main class, of every installed mod or of one.
     */
    private static void printTools(@NotNull ModCatalog catalog, @Nullable String modId, @NotNull PrintStream output) {
        boolean found = false;

        for (ModEntry entry : catalog.getEntries()) {
            ModManifest manifest = entry.getManifest();

            if (manifest == null || modId != null && !manifest.getId().equals(modId)) {
                continue;
            }

            for (ModTool tool : manifest.getTools()) {
                output.println(String.format(Locale.ROOT, LIST_FORMAT, manifest.getId(), tool.getId(), tool.getMain()));
                found = true;
            }
        }

        if (!found) {
            output.println(modId == null ? "No installed mod has tools." : Exceptions.format("The mod {0} has no tools.", modId));
        }
    }


    private ToolCommand() {
    }
}
