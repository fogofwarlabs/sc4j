package org.fogofwarlabs.sc4j.beaconlink.client.firewall;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands as child processes: the output and standard error are read together on a virtual thread, and a command that runs longer
 * than its timeout is ended.
 */
final class ProcessCommandRunner implements CommandRunner {
    /**
     * Runs a command and waits for it to end.
     *
     * @param command       the program and its arguments
     * @param timeoutMillis the longest time the command may run, in milliseconds
     * @return the command's exit code and output
     * @throws IOException if the command cannot be started or runs longer than the timeout
     */
    @Override
    public @NotNull CommandResult run(@NotNull List<@NotNull String> command, long timeoutMillis) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(output);
            } catch (IOException exception) {
                // The output ends where the stream failed; the exit code still tells the outcome.
            }
        });

        try {
            process.getOutputStream().close();

            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();

                throw Exceptions.newIOException("The command {0} ran longer than {1} ms.", command.getFirst(), timeoutMillis);
            }

            reader.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();

            throw Exceptions.newIOException(exception, "The command {0} was interrupted.", command.getFirst());
        }

        return new CommandResult(process.exitValue(), output.toString(Charset.defaultCharset()));
    }
}
