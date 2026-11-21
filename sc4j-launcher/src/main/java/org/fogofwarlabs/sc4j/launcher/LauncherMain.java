package org.fogofwarlabs.sc4j.launcher;

import javafx.application.Application;
import org.fogofwarlabs.sc4j.launcher.catalog.ModCatalog;
import org.fogofwarlabs.sc4j.launcher.catalog.ModEntry;
import org.fogofwarlabs.sc4j.launcher.catalog.ModIssue;
import org.fogofwarlabs.sc4j.launcher.catalog.ModStatus;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.settings.InstallationRecords;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Starts the SC4J launcher: {@code sc4j-launcher [--install <dir>] [--list | tool [<mod id> [<tool id> [<arguments>...]]]]}.
 * <p>
 * The install folder comes from {@code --install} or from the launcher's own location ({@code <install>/launcher/lib}, or
 * the development install for a run from the source tree). Without {@code --list} and {@code tool} the launcher window opens;
 * {@code --list} prints the mod list with each mod's status and reasons and exits without opening a window (status 0 when
 * no mod has an error, 1 otherwise). {@code tool} lists or runs the command line tools of the installed mods ({@link ToolCommand}) and exits
 * with the tool's status; every argument after it belongs to the command.
 */
public final class LauncherMain {
    /**
     * The JavaFX parameter that passes the install folder to {@link LauncherApplication}.
     */
    static final @NotNull String INSTALL_PARAMETER = "install";

    private static final @NotNull String INSTALL_OPTION = "--install";
    private static final @NotNull String LIST_OPTION = "--list";
    private static final @NotNull String HELP_OPTION = "--help";
    private static final @NotNull String NAMED_PARAMETER_PREFIX = "--";
    private static final @NotNull String NAMED_PARAMETER_SEPARATOR = "=";
    private static final @NotNull String USAGE = "Usage: sc4j-launcher [--install <dir>] [--list | " + ToolCommand.USAGE + "]";
    private static final @NotNull String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";
    private static final @NotNull String LIST_FORMAT = "%-7s %-20s %s";
    private static final @NotNull String ISSUE_PREFIX = "        ";
    private static final int SUCCESS_STATUS = 0;
    private static final int FAILURE_STATUS = 1;
    private static final int USAGE_STATUS = 2;

    /**
     * Runs the launcher.
     *
     * @param arguments the command line
     */
    public static void main(@NotNull String @NotNull [] arguments) {
        Path installOption = null;
        boolean list = false;
        List<String> toolArguments = null;

        for (int index = 0; index < arguments.length && toolArguments == null; index++) {
            String argument = arguments[index];

            if (argument.equals(ToolCommand.COMMAND) && !list) {
                toolArguments = List.of(arguments).subList(index + 1, arguments.length);
            } else if (argument.equals(LIST_OPTION)) {
                list = true;
            } else if (argument.equals(INSTALL_OPTION)) {
                index++;
                installOption = index < arguments.length ? parsePath(arguments[index]) : null;

                if (installOption == null) {
                    exitWithUsage("The option --install needs a valid folder.");
                }
            } else if (argument.equals(HELP_OPTION)) {
                System.out.println(USAGE);

                return;
            } else {
                exitWithUsage("Unknown argument: " + argument);
            }
        }

        InstallLayout install = installOption != null ? InstallLayout.of(installOption)
                                                      : InstallLayout.detect(LauncherMain.class, InstallLayout.LAUNCHER_FOLDER);

        if (install == null) {
            exitWithUsage("The install folder cannot be found from the launcher location; pass --install.");

            return;
        }

        createTemporaryDirectory();

        if (toolArguments != null) {
            System.exit(ToolCommand.run(install, toolArguments, System.out, System.err, ToolCommand::startProcess));
        }

        if (list) {
            System.exit(printCatalog(install, System.out));
        }

        Application.launch(LauncherApplication.class, NAMED_PARAMETER_PREFIX + INSTALL_PARAMETER + NAMED_PARAMETER_SEPARATOR + install.getRoot());
    }

    /**
     * Prints the mod list of an install: one line per jar with its status, id and file, followed by the reasons of a
     * warning or an error. An empty directory setting that declares {@code detect} is detected and saved first ({@link ModCatalog#scan}).
     *
     * @param install the install
     * @param output  receives the lines
     * @return {@code 0} when no mod has an error, {@code 1} otherwise
     */
    static int printCatalog(@NotNull InstallLayout install, @NotNull PrintStream output) {
        ModCatalog catalog;

        try {
            catalog = ModCatalog.scan(install, InstallationRecords.system());
        } catch (IOException exception) {
            output.println("The mods folder cannot be listed: " + exception.getMessage());

            return FAILURE_STATUS;
        }

        output.println("Mods in " + catalog.getModsDirectory());

        for (ModEntry entry : catalog.getEntries()) {
            ModManifest manifest = entry.getManifest();

            output.println(String.format(Locale.ROOT, LIST_FORMAT,
                                         entry.getStatus().name(), manifest == null ? "-" : manifest.getId(), entry.getJarName()));

            for (ModIssue issue : entry.getIssues()) {
                output.println(ISSUE_PREFIX + issue.describeDetail());
            }
        }

        return catalog.countStatus(ModStatus.ERROR) == 0 ? SUCCESS_STATUS : FAILURE_STATUS;
    }

    // SC4J.exe starts the JVM directly, so no start script has made its temporary folder.
    private static void createTemporaryDirectory() {
        try {
            Files.createDirectories(Path.of(System.getProperty(TEMPORARY_DIRECTORY_PROPERTY)));
        } catch (IOException | InvalidPathException exception) {
            // Only creating a temporary file fails then.
        }
    }

    private static @Nullable Path parsePath(@NotNull String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static void exitWithUsage(@NotNull String message) {
        System.err.println(message);
        System.err.println(USAGE);
        System.exit(USAGE_STATUS);
    }


    private LauncherMain() {
    }
}
