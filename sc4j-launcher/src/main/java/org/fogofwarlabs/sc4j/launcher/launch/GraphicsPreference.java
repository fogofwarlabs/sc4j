package org.fogofwarlabs.sc4j.launcher.launch;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sets the Windows graphics preference of a program for the current user: the string value named by the program's path under
 * {@value #PREFERENCES_KEY}, whose entry {@value #HIGH_PERFORMANCE_ENTRY} runs the program on the high-performance GPU. The registry is read
 * and written with the system's {@code reg} ({@code %SystemRoot%\System32\reg.exe}), without elevation. Other operating systems have no such
 * preference: nothing is done there. A failure is logged and changes nothing.
 */
public final class GraphicsPreference {
    private static final @NotNull Logger LOGGER = Logger.of(GraphicsPreference.class);
    private static final @NotNull String PREFERENCES_KEY = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";
    private static final @NotNull String GPU_PREFERENCE_PREFIX = "GpuPreference=";
    private static final @NotNull String HIGH_PERFORMANCE_ENTRY = "GpuPreference=2";
    /**
     * The data of a preference value that names only the high-performance GPU.
     */
    private static final @NotNull String HIGH_PERFORMANCE_DATA = "GpuPreference=2;";
    private static final @NotNull String ENTRY_SEPARATOR = ";";
    private static final @NotNull String STRING_TYPE = "REG_SZ";
    private static final @NotNull String SYSTEM_ROOT_VARIABLE = "SystemRoot";
    private static final @NotNull String REGISTRY_PROGRAM = "System32\\reg.exe";
    private static final @NotNull String REGISTRY_COMMAND = "reg";
    private static final @NotNull String QUERY_ARGUMENT = "query";
    private static final @NotNull String ADD_ARGUMENT = "add";
    private static final @NotNull String DELETE_ARGUMENT = "delete";
    private static final @NotNull String VALUE_ARGUMENT = "/v";
    private static final @NotNull String TYPE_ARGUMENT = "/t";
    private static final @NotNull String DATA_ARGUMENT = "/d";
    private static final @NotNull String FORCE_ARGUMENT = "/f";
    private static final @NotNull String NATIVE_ENCODING_PROPERTY = "native.encoding";
    private static final @NotNull String LINE_SEPARATOR_PATTERN = "\\R";
    private static final long COMMAND_TIMEOUT_SECONDS = 10L;

    /**
     * Runs a command of the operating system.
     */
    interface CommandRunner {
        /**
         * Runs a command and waits for it to end.
         *
         * @param command the program and its arguments
         * @return the command's output, standard error included, or {@code null} if it ended with an exit code other than {@code 0}
         * @throws IOException if the command cannot be started or runs too long
         */
        @Nullable String run(@NotNull List<@NotNull String> command) throws IOException;
    }

    /**
     * Creates the {@link GraphicsPreference} instance of the operating system the launcher runs on.
     *
     * @return the created {@link GraphicsPreference} instance, which changes nothing outside Windows
     */
    public static @NotNull GraphicsPreference createDefault() {
        return new GraphicsPreference(RuntimeCommandLine.isWindows(), GraphicsPreference::runProcess);
    }

    private static @Nullable String runProcess(@NotNull List<@NotNull String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        try (InputStream output = process.getInputStream()) {
            process.getOutputStream().close();

            // The command ends at once; its output is read to the end before the wait.
            byte[] contents = output.readAllBytes();

            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw Exceptions.newIOException("The command {0} ran longer than {1} s.", command.getFirst(), COMMAND_TIMEOUT_SECONDS);
            }

            Charset charset = Charset.forName(System.getProperty(NATIVE_ENCODING_PROPERTY, Charset.defaultCharset().name()), Charset.defaultCharset());

            return process.exitValue() == 0 ? new String(contents, charset) : null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw Exceptions.newIOException(exception, "The command {0} was interrupted.", command.getFirst());
        } finally {
            process.destroy();
        }
    }

    private static @NotNull String findRegistryCommand() {
        String systemRoot = System.getenv(SYSTEM_ROOT_VARIABLE);

        try {
            Path program = systemRoot == null ? null : Path.of(systemRoot).resolve(REGISTRY_PROGRAM);

            return program != null && Files.isRegularFile(program) ? program.toString() : REGISTRY_COMMAND;
        } catch (InvalidPathException exception) {
            return REGISTRY_COMMAND;
        }
    }

    /**
     * Reads the data of the queried value from the output of {@code reg query}.
     *
     * @return the data, or {@code null} if the output holds no string value
     */
    private static @Nullable String readData(@NotNull String output) {
        for (String line : output.split(LINE_SEPARATOR_PATTERN)) {
            int type = line.indexOf(STRING_TYPE);

            if (type > 0) {
                return line.substring(type + STRING_TYPE.length()).strip();
            }
        }

        return null;
    }

    /**
     * Creates the data of a preference value that runs the program on the high-performance GPU and keeps the other entries of the data.
     *
     * @param data the present data, or {@code null} if there is no value
     * @return the data, or {@code null} if the present data already runs the program on the high-performance GPU
     */
    private static @Nullable String createHighPerformanceData(@Nullable String data) {
        if (data == null) {
            return HIGH_PERFORMANCE_DATA;
        }

        StringBuilder created = new StringBuilder(HIGH_PERFORMANCE_DATA);

        for (String entry : data.split(ENTRY_SEPARATOR)) {
            String stripped = entry.strip();

            if (stripped.equals(HIGH_PERFORMANCE_ENTRY)) {
                return null;
            }

            if (!stripped.isEmpty() && !stripped.startsWith(GPU_PREFERENCE_PREFIX)) {
                created.append(stripped).append(ENTRY_SEPARATOR);
            }
        }

        return created.toString();
    }


    private final boolean supported;
    private final @NotNull CommandRunner runner;
    private final @NotNull String registryCommand;

    /**
     * Creates a {@link GraphicsPreference} instance.
     *
     * @param supported whether the launcher runs on Windows, where the preference exists
     * @param runner    runs {@code reg}
     */
    GraphicsPreference(boolean supported, @NotNull CommandRunner runner) {
        this.supported       = supported;
        this.runner          = runner;
        this.registryCommand = findRegistryCommand();
    }

    /**
     * @return {@code true} if the operating system has the graphics preference, {@code false} otherwise
     */
    public boolean isSupported() {
        return this.supported;
    }

    /**
     * Sets a program to run on the high-performance GPU: writes {@value #HIGH_PERFORMANCE_ENTRY} into its preference value when the value is
     * missing or names another GPU, keeping the value's other entries. Blocks while {@code reg} runs.
     *
     * @param program the program's executable, an absolute path
     */
    public void addHighPerformance(@NotNull Path program) {
        if (!this.supported) {
            return;
        }

        try {
            String data = this.queryData(program);
            String created = createHighPerformanceData(data);

            if (created == null) {
                return;
            }

            if (this.runner.run(List.of(this.registryCommand, ADD_ARGUMENT, PREFERENCES_KEY, VALUE_ARGUMENT, program.toString(), TYPE_ARGUMENT,
                                        STRING_TYPE, DATA_ARGUMENT, created, FORCE_ARGUMENT)) == null) {
                LOGGER.warning("The graphics preference of {0} cannot be written.", program);
            } else {
                LOGGER.info("The graphics preference of {0} is the high-performance GPU.", program);
            }
        } catch (IOException exception) {
            LOGGER.warning("The graphics preference of {0} cannot be written: {1}", program, exception.getMessage());
        }
    }

    /**
     * Removes the preference value of a program when its data is exactly {@value #HIGH_PERFORMANCE_DATA}, what {@link #addHighPerformance}
     * writes for a missing value; a value that the user or another program changed stays. Blocks while {@code reg} runs.
     *
     * @param program the program's executable, an absolute path
     */
    public void removeHighPerformance(@NotNull Path program) {
        if (!this.supported) {
            return;
        }

        try {
            String data = this.queryData(program);

            if (!HIGH_PERFORMANCE_DATA.equals(data)) {
                return;
            }

            if (this.runner.run(List.of(this.registryCommand, DELETE_ARGUMENT, PREFERENCES_KEY, VALUE_ARGUMENT, program.toString(),
                                        FORCE_ARGUMENT)) == null) {
                LOGGER.warning("The graphics preference of {0} cannot be removed.", program);
            } else {
                LOGGER.info("The graphics preference of {0} is removed.", program);
            }
        } catch (IOException exception) {
            LOGGER.warning("The graphics preference of {0} cannot be removed: {1}", program, exception.getMessage());
        }
    }

    /**
     * Reads the data of a program's preference value.
     *
     * @return the data, or {@code null} if the program has no preference value
     */
    private @Nullable String queryData(@NotNull Path program) throws IOException {
        String output = this.runner.run(List.of(this.registryCommand, QUERY_ARGUMENT, PREFERENCES_KEY, VALUE_ARGUMENT, program.toString()));

        return output == null ? null : readData(output);
    }
}
