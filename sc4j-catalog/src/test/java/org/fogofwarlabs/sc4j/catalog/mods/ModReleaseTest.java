package org.fogofwarlabs.sc4j.catalog.mods;

import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests finding the mod jar of the latest release and of every release of a repository against a local stand-in for GitHub.
 */
final class ModReleaseTest {
    private static final @NotNull ReleaseRepository REPOSITORY = ReleaseRepository.of("owner", "my-mod");
    private static final @NotNull String JAR = "my-mod.jar";
    private static final @NotNull String OTHER_JAR = "other.jar";
    private static final byte @NotNull [] JAR_CONTENTS = "the jar".getBytes(StandardCharsets.UTF_8);

    @Test
    void find_all_should_list_the_releases_with_a_jar_newest_first() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            Map<String, byte[]> twoJars = new LinkedHashMap<>();

            twoJars.put(OTHER_JAR, JAR_CONTENTS);
            twoJars.put(JAR, JAR_CONTENTS);
            server.addCheckedRelease(REPOSITORY.toString(), "v1.0.0", twoJars);
            server.addRelease(REPOSITORY.toString(), "v1.1.0", Map.of(JAR, JAR_CONTENTS));
            server.addCheckedRelease(REPOSITORY.toString(), "v1.2.0", Map.of(JAR, JAR_CONTENTS));

            ReleaseClient client = new ReleaseClient(server.getApi());
            List<ModRelease> releases = ModRelease.findAll(client, REPOSITORY, JAR);

            Assertions.assertEquals(List.of("v1.2.0", "v1.0.0"), releases.stream().map(ModRelease::getTag).toList());
            Assertions.assertEquals(List.of(JAR, JAR), releases.stream().map(release -> release.getJarFile().getName()).toList());
            Assertions.assertEquals(OTHER_JAR, ModRelease.findAll(client, REPOSITORY, null).getLast().getJarFile().getName());
            Assertions.assertEquals(ModRelease.find(client, REPOSITORY, JAR).getTag(), releases.getFirst().getTag());
        }
    }

    @Test
    void find_all_should_tell_a_repository_without_a_mod_jar() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            server.addRelease(REPOSITORY.toString(), "v1.0.0", Map.of(JAR, JAR_CONTENTS));

            ReleaseClient client = new ReleaseClient(server.getApi());
            ReleaseException exception = Assertions.assertThrows(ReleaseException.class, () -> ModRelease.findAll(client, REPOSITORY, null));

            Assertions.assertEquals(ReleaseProblem.ASSET_MISSING, exception.getProblem());
        }
    }
}
