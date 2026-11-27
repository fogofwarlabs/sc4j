package org.fogofwarlabs.sc4j.launcher.launch;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the runtime as a separate process with a {@link RuntimeCommandLine}, its environment variables added to the launcher's, and streams
 * its standard output and standard error, merged in output order, to a {@link RuntimeListener}.
 * <p>
 * The runtime's output is decoded with the platform's native encoding, which a JVM uses for redirected output.
 */
public final class RuntimeProcess {
    private static final @NotNull String OUTPUT_THREAD_NAME = "sc4j-runtime-output";
    private static final @NotNull String NATIVE_ENCODING_PROPERTY = "native.encoding";
    private static final int INTERRUPTED_STATUS = -1;

    /**
     * Starts the runtime.
     *
     * @param commandLine the command line
     * @param listener    receives the output lines and the exit status, on the process's output thread
     * @return the created {@link RuntimeProcess} instance
     * @throws IOException if the JVM's temporary folder cannot be created or the process cannot be started
     */
    public static @NotNull RuntimeProcess start(@NotNull RuntimeCommandLine commandLine, @NotNull RuntimeListener listener) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(commandLine.getArguments());
        Path temporaryDirectory = commandLine.getTemporaryDirectory();

        if (temporaryDirectory != null) {
            Files.createDirectories(temporaryDirectory);
        }

        builder.directory(commandLine.getWorkingDirectory().toFile());
        builder.environment().putAll(commandLine.getEnvironment());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        RuntimeProcess runtime = new RuntimeProcess(process);
        Thread thread = new Thread(() -> runtime.forwardOutput(listener), OUTPUT_THREAD_NAME);

        process.getOutputStream().close();
        thread.setDaemon(true);
        thread.start();

        return runtime;
    }

    private static @NotNull Charset getOutputCharset() {
        String name = System.getProperty(NATIVE_ENCODING_PROPERTY);

        return name == null ? Charset.defaultCharset() : Charset.forName(name, Charset.defaultCharset());
    }


    private final @NotNull Process process;

    /**
     * Creates a {@link RuntimeProcess} instance.
     *
     * @param process the started process
     */
    private RuntimeProcess(@NotNull Process process) {
        this.process = process;
    }

    /**
     * @return the process id of the runtime
     */
    public long getPid() {
        return this.process.pid();
    }

    /**
     * Asks the runtime to end: the processes it started, then the runtime itself. The listener still receives the remaining output and the
     * exit status.
     */
    public void stop() {
        this.process.descendants().forEach(ProcessHandle::destroy);
        this.process.destroy();
    }

    private void forwardOutput(@NotNull RuntimeListener listener) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getInputStream(), getOutputCharset()))) {
            String line = reader.readLine();

            while (line != null) {
                listener.onOutput(line);
                line = reader.readLine();
            }
        } catch (IOException exception) {
            listener.onOutput(String.valueOf(exception.getMessage()));
        }

        int status;

        try {
            status = this.process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = INTERRUPTED_STATUS;
        }

        listener.onExit(status);
    }
}
