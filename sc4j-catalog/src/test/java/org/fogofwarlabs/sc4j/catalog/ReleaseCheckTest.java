package org.fogofwarlabs.sc4j.catalog;

import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * Tests the check for a newer release against a local stand-in for GitHub: the latest release against release, development and unversioned
 * running versions, and a release whose tag is not a version.
 */
final class ReleaseCheckTest {
    private static final @NotNull String REPOSITORY = "owner/sc4j";
    private static final @NotNull String RUNNING = "1.1.0";

    private static @NotNull ReleaseRepository requireRepository() {
        ReleaseRepository repository = ReleaseRepository.parse(REPOSITORY);

        Assertions.assertNotNull(repository);

        return repository;
    }

    @Test
    void read_should_compare_the_latest_release_with_the_running_version() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ReleaseClient client = new ReleaseClient(server.getApi());
            ReleaseCheck check;

            server.addRelease(REPOSITORY, "v1.2.0", Map.of());
            check = ReleaseCheck.read(client, requireRepository(), ReleaseVersion.parse("1.1.0"));

            Assertions.assertEquals("1.2.0", check.getLatestVersion().toString());
            Assertions.assertEquals("v1.2.0", check.getRelease().getTag());
            Assertions.assertTrue(check.isNewer());
            Assertions.assertFalse(ReleaseCheck.read(client, requireRepository(), ReleaseVersion.parse("1.2.0")).isNewer());
            Assertions.assertFalse(ReleaseCheck.read(client, requireRepository(), ReleaseVersion.parse("1.3.0-SNAPSHOT")).isNewer());
            Assertions.assertTrue(ReleaseCheck.read(client, requireRepository(), ReleaseVersion.parse("1.2.0-SNAPSHOT")).isNewer());
            Assertions.assertTrue(ReleaseCheck.read(client, requireRepository(), ReleaseVersion.parse("development")).isNewer());
        }
    }

    @Test
    void read_should_refuse_a_tag_that_is_not_a_version() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ReleaseClient client = new ReleaseClient(server.getApi());
            ReleaseException exception;

            server.addRelease(REPOSITORY, "latest", Map.of());
            exception = Assertions.assertThrows(ReleaseException.class, () -> ReleaseCheck.read(client, requireRepository(), ReleaseVersion.parse(RUNNING)));

            Assertions.assertEquals(ReleaseProblem.INVALID_RESPONSE, exception.getProblem());
        }
    }

    @Test
    void read_should_tell_a_repository_without_release() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ReleaseClient client = new ReleaseClient(server.getApi());
            ReleaseVersion running = ReleaseVersion.parse(RUNNING);
            ReleaseException exception = Assertions.assertThrows(ReleaseException.class, () -> ReleaseCheck.read(client, requireRepository(), running));

            Assertions.assertEquals(ReleaseProblem.NOT_FOUND, exception.getProblem());
        }
    }
}
