package org.fogofwarlabs.sc4j.launcher.launch;

import org.fogofwarlabs.sc4j.manifest.ModTool;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the command line that runs a mod tool: the Java program the runtime uses, the JVM options of the runtime's
 * {@link RuntimeCommandLine#LAUNCH_FILE}, the install folder as the system property {@link InstallLayout#INSTALL_PROPERTY}, the class path the
 * runtime gives a mod (the runtime's libraries and the mod jar), the tool's main class and the tool's arguments:
 * {@snippet lang = "text":
 * <java home>/bin/java <the JVM options of <install>/runtime/bin/sc4j-runtime.launch, ${APP_HOME} being <install>/runtime>
 *     -Dsc4j.install=<install>
 *     -cp <install>/runtime/lib/*<path separator><mod jar>
 *     <tool main class> <tool arguments>
 *}
 * A program file the install lacks is reported by {@link #getMissingProgram()}.
 */
public final class ToolCommandLine {
    private static final @NotNull String CLASS_PATH_OPTION = "-cp";
    private static final @NotNull String SYSTEM_PROPERTY_PREFIX = "-D";
    private static final @NotNull String SYSTEM_PROPERTY_SEPARATOR = "=";
    private static final @NotNull String WINDOWS_PATH_SEPARATOR = ";";
    private static final @NotNull String UNIX_PATH_SEPARATOR = ":";

    /**
     * Creates a {@link ToolCommandLine} instance for a tool of a mod of an install.
     *
     * @param install       the install
     * @param modJar        the jar of the mod that declares the tool
     * @param tool          the tool
     * @param toolArguments the arguments passed to the tool's main class
     * @param javaHome      the Java runtime that runs the tool
     * @param windows       whether the tool runs on Windows (Java program {@code java.exe}, class path separator {@code ;})
     * @return the created {@link ToolCommandLine} instance
     */
    public static @NotNull ToolCommandLine create(@NotNull InstallLayout install, @NotNull Path modJar, @NotNull ModTool tool,
                                                  @NotNull List<@NotNull String> toolArguments, @NotNull Path javaHome, boolean windows) {
        Path java = RuntimeCommandLine.getJavaProgram(javaHome, windows);
        List<String> launchArguments = RuntimeCommandLine.readLaunchArguments(install);
        int classPathIndex = launchArguments == null ? -1 : launchArguments.indexOf(CLASS_PATH_OPTION);
        boolean launchFileRead = launchArguments != null && classPathIndex >= 0 && classPathIndex + 1 < launchArguments.size();
        List<String> arguments = new ArrayList<>();

        arguments.add(java.toString());

        if (launchFileRead) {
            arguments.addAll(launchArguments.subList(0, classPathIndex));
        }

        arguments.add(SYSTEM_PROPERTY_PREFIX + InstallLayout.INSTALL_PROPERTY + SYSTEM_PROPERTY_SEPARATOR + install.getRoot());
        arguments.add(CLASS_PATH_OPTION);
        arguments.add((launchFileRead ? launchArguments.get(classPathIndex + 1) + (windows ? WINDOWS_PATH_SEPARATOR : UNIX_PATH_SEPARATOR) : "") +
                      modJar.toAbsolutePath().normalize());
        arguments.add(tool.getMain());
        arguments.addAll(toolArguments);

        return new ToolCommandLine(arguments, RuntimeCommandLine.findMissingProgram(install, java, launchFileRead));
    }


    private final @NotNull List<@NotNull String> arguments;
    private final @Nullable Path missingProgram;

    /**
     * Creates a {@link ToolCommandLine} instance.
     *
     * @param arguments      the Java program followed by its arguments
     * @param missingProgram the program file the install lacks, or {@code null} when it has them all
     */
    private ToolCommandLine(@NotNull List<@NotNull String> arguments, @Nullable Path missingProgram) {
        this.arguments      = List.copyOf(arguments);
        this.missingProgram = missingProgram;
    }

    /**
     * @return the Java program followed by its arguments, one path per argument
     */
    public @NotNull List<@NotNull String> getArguments() {
        return this.arguments;
    }

    /**
     * @return the JVM's temporary folder the command line names, or {@code null} when it names none
     */
    public @Nullable Path getTemporaryDirectory() {
        return RuntimeCommandLine.findTemporaryDirectory(this.arguments);
    }

    /**
     * @return the Java program, the launch file or the runtime library folder that the install lacks, or {@code null} when it has them all
     */
    public @Nullable Path getMissingProgram() {
        return this.missingProgram;
    }
}
