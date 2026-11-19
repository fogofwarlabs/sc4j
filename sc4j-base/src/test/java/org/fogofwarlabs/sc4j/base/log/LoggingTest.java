package org.fogofwarlabs.sc4j.base.log;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tests the level threshold, the line format and the log file switch.
 */
final class LoggingTest {
    private static final @NotNull Logger LOGGER = Logger.of(LoggingTest.class);
    /**
     * The file line of the warning that {@link #logging_drops_messages_below_threshold_and_formats_arguments} writes.
     */
    private static final @NotNull String WARNING_LINE_PATTERN = "\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3} " +
                                                                "WARN  \\[.+] LoggingTest - shown text and \\[1, 2]";

    @Test
    void logging_drops_messages_below_threshold_and_formats_arguments(@TempDir @NotNull Path directory) throws IOException {
        Logging.setLevel(LogLevel.WARNING);
        Logging.enableFile(directory);

        LOGGER.info("hidden {0}", 1);
        LOGGER.warning("shown {0} and {1}", "text", new int[]{1, 2});

        List<String> lines = Files.readAllLines(directory.resolve(Logging.FILE_NAME), StandardCharsets.UTF_8);

        Assertions.assertEquals(1, lines.size());
        Assertions.assertTrue(lines.getFirst().matches(WARNING_LINE_PATTERN), lines.getFirst());
        Assertions.assertFalse(LOGGER.isEnabled(LogLevel.DEBUG));
    }

    @Test
    void logging_keeps_messages_without_arguments_unchanged(@TempDir @NotNull Path directory) throws IOException {
        Logging.enableFile(directory);
        LOGGER.info("COMMAND: /say {0} -> ok");

        String line = Files.readAllLines(directory.resolve(Logging.FILE_NAME), StandardCharsets.UTF_8).getFirst();

        Assertions.assertTrue(line.endsWith("LoggingTest - COMMAND: /say {0} -> ok"), line);
    }

    @Test
    void is_file_enabled_should_follow_enable_and_disable(@TempDir @NotNull Path directory) throws IOException {
        Assertions.assertFalse(Logging.isFileEnabled());

        Logging.enableFile(directory);

        Assertions.assertTrue(Logging.isFileEnabled());

        Logging.disableFile();

        Assertions.assertFalse(Logging.isFileEnabled());
    }


    @AfterEach
    void restore() {
        Logging.disableFile();
        Logging.setLevel(LogLevel.INFO);
    }
}
