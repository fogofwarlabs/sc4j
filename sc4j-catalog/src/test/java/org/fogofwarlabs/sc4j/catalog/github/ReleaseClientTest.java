package org.fogofwarlabs.sc4j.catalog.github;

import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tests the release client against a local stand-in for GitHub: the latest release with its publication time, notes and files, missing
 * repositories, small files, and downloads that are kept only when they match their checksum file.
 */
final class ReleaseClientTest {
    private static final @NotNull String REPOSITORY = "owner/my-mod";
    private static final @NotNull String TAG = "v1.2.0";
    private static final @NotNull String JAR = "my-mod.jar";
    private static final byte @NotNull [] JAR_CONTENTS = "the jar".getBytes(StandardCharsets.UTF_8);
    private static final int SMALL_LIMIT = 4;

    @Test
    void find_latest_release_should_read_the_tag_and_the_files() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            server.addCheckedRelease(REPOSITORY, TAG, Map.of(JAR, JAR_CONTENTS));

            Release release = new ReleaseClient(server.getApi()).findLatestRelease(requireRepository(REPOSITORY));
            ReleaseAsset jar = release.findAsset(JAR);

            Assertions.assertEquals(TAG, release.getTag());
            Assertions.assertEquals(REPOSITORY, release.getRepository().toString());
            Assertions.assertEquals(List.of(JAR, JAR + ReleaseServerScenario.CHECKSUM_EXTENSION), getNames(release));
            Assertions.assertNotNull(jar);
            Assertions.assertEquals(JAR_CONTENTS.length, jar.getSize());
            Assertions.assertNull(release.findAsset("other.jar"));
            Assertions.assertNull(release.getPublishedAt());
            Assertions.assertEquals("", release.getNotes());
        }
    }

    @Test
    void find_latest_release_should_read_the_publication_time_and_the_notes() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            Instant publishedAt = Instant.parse("2026-11-16T12:30:00Z");
            String notes = "## Changes\n* Faster start";
            Release release;

            server.addCheckedRelease(REPOSITORY, TAG, publishedAt, notes, Map.of(JAR, JAR_CONTENTS));
            release = new ReleaseClient(server.getApi()).findLatestRelease(requireRepository(REPOSITORY));

            Assertions.assertEquals(publishedAt, release.getPublishedAt());
            Assertions.assertEquals(notes, release.getNotes());
        }
    }

    @Test
    void find_latest_release_should_tell_a_repository_without_release() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ReleaseClient client = new ReleaseClient(server.getApi());
            ReleaseException exception = Assertions.assertThrows(ReleaseException.class,
                                                                 () -> client.findLatestRelease(requireRepository("owner/none")));

            Assertions.assertEquals(ReleaseProblem.NOT_FOUND, exception.getProblem());
        }
    }

    @Test
    void find_latest_release_should_refuse_an_answer_that_is_no_release() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ReleaseClient client = new ReleaseClient(server.getApi());

            server.setLatestRelease("owner/text", "not json".getBytes(StandardCharsets.UTF_8));
            server.setLatestRelease("owner/message", "{\"message\": \"Moved\"}".getBytes(StandardCharsets.UTF_8));

            for (String repository : List.of("owner/text", "owner/message")) {
                ReleaseException exception = Assertions.assertThrows(ReleaseException.class,
                                                                     () -> client.findLatestRelease(requireRepository(repository)));

                Assertions.assertEquals(ReleaseProblem.INVALID_RESPONSE, exception.getProblem(), repository);
            }
        }
    }

    @Test
    void find_releases_should_list_the_releases_newest_first() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            server.addCheckedRelease(REPOSITORY, "v1.0.0", Map.of(JAR, JAR_CONTENTS));
            server.addCheckedRelease(REPOSITORY, "v1.1.0", Map.of(JAR, JAR_CONTENTS));
            server.addCheckedRelease(REPOSITORY, TAG, Map.of(JAR, JAR_CONTENTS));

            List<Release> releases = new ReleaseClient(server.getApi()).findReleases(requireRepository(REPOSITORY));

            Assertions.assertEquals(List.of(TAG, "v1.1.0", "v1.0.0"), releases.stream().map(Release::getTag).toList());
            Assertions.assertEquals(List.of(JAR, JAR + ReleaseServerScenario.CHECKSUM_EXTENSION), getNames(releases.getLast()));
        }
    }

    @Test
    void find_releases_should_leave_out_drafts_and_pre_releases() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            server.setReleases(REPOSITORY, """
                                           [{"tag_name": "v2.0.0", "draft": true, "assets": []},
                                            {"tag_name": "v1.3.0-beta.1", "prerelease": true, "assets": []},
                                            {"tag_name": "v1.2.0", "draft": false, "prerelease": false, "assets": []}]
                                           """.getBytes(StandardCharsets.UTF_8));
            server.setReleases("owner/message", "{\"message\": \"Moved\"}".getBytes(StandardCharsets.UTF_8));

            ReleaseClient client = new ReleaseClient(server.getApi());
            ReleaseException missing = Assertions.assertThrows(ReleaseException.class, () -> client.findReleases(requireRepository("owner/none")));
            ReleaseException invalid = Assertions.assertThrows(ReleaseException.class, () -> client.findReleases(requireRepository("owner/message")));

            Assertions.assertEquals(List.of(TAG), client.findReleases(requireRepository(REPOSITORY)).stream().map(Release::getTag).toList());
            Assertions.assertEquals(ReleaseProblem.NOT_FOUND, missing.getProblem());
            Assertions.assertEquals(ReleaseProblem.INVALID_RESPONSE, invalid.getProblem());
        }
    }

    @Test
    void read_should_refuse_a_file_larger_than_the_limit() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ReleaseClient client = new ReleaseClient(server.getApi());
            ReleaseException exception = Assertions.assertThrows(ReleaseException.class,
                                                                 () -> client.read(server.addFile("large.txt", JAR_CONTENTS), SMALL_LIMIT));

            Assertions.assertEquals(ReleaseProblem.TOO_LARGE, exception.getProblem());
            Assertions.assertArrayEquals(JAR_CONTENTS, client.read(server.addFile("small.txt", JAR_CONTENTS), JAR_CONTENTS.length));
        }
    }

    @Test
    void download_should_keep_a_file_that_matches_its_checksum(@TempDir @NotNull Path directory) throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            server.addCheckedRelease(REPOSITORY, TAG, Map.of(JAR, JAR_CONTENTS));

            ReleaseClient client = new ReleaseClient(server.getApi());
            Release release = client.findLatestRelease(requireRepository(REPOSITORY));
            Path target = directory.resolve("downloads").resolve(JAR);
            List<Double> shares = new ArrayList<>();

            client.download(requireAsset(release, JAR), requireAsset(release, JAR + ReleaseServerScenario.CHECKSUM_EXTENSION), target,
                            JAR_CONTENTS.length, shares::add);

            Assertions.assertArrayEquals(JAR_CONTENTS, Files.readAllBytes(target));
            Assertions.assertEquals(1.0, shares.getLast());
            Assertions.assertEquals(List.of(target), listFiles(target.getParent()));
        }
    }

    @Test
    void download_should_drop_a_file_that_does_not_match_its_checksum(@TempDir @NotNull Path directory) throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            Map<String, byte[]> assets = new LinkedHashMap<>();
            byte[] otherContents = "other".getBytes(StandardCharsets.UTF_8);

            assets.put(JAR, JAR_CONTENTS);
            assets.put(JAR + ReleaseServerScenario.CHECKSUM_EXTENSION, ReleaseServerScenario.createChecksum(otherContents, JAR));
            server.addRelease(REPOSITORY, TAG, assets);

            ReleaseClient client = new ReleaseClient(server.getApi());
            Release release = client.findLatestRelease(requireRepository(REPOSITORY));
            Path target = directory.resolve(JAR);
            ReleaseException exception = Assertions.assertThrows(ReleaseException.class, () -> {
                client.download(requireAsset(release, JAR), requireAsset(release, JAR + ReleaseServerScenario.CHECKSUM_EXTENSION), target,
                                JAR_CONTENTS.length, share -> {
                        });
            });

            Assertions.assertEquals(ReleaseProblem.CHECKSUM_MISMATCH, exception.getProblem());
            Assertions.assertEquals(List.of(), listFiles(directory));
        }
    }

    @Test
    void download_should_drop_a_file_larger_than_the_limit(@TempDir @NotNull Path directory) throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            server.addCheckedRelease(REPOSITORY, TAG, Map.of(JAR, JAR_CONTENTS));

            ReleaseClient client = new ReleaseClient(server.getApi());
            Release release = client.findLatestRelease(requireRepository(REPOSITORY));
            ReleaseException exception = Assertions.assertThrows(ReleaseException.class, () -> {
                client.download(requireAsset(release, JAR), requireAsset(release, JAR + ReleaseServerScenario.CHECKSUM_EXTENSION),
                                directory.resolve(JAR), SMALL_LIMIT, share -> {
                        });
            });

            Assertions.assertEquals(ReleaseProblem.TOO_LARGE, exception.getProblem());
            Assertions.assertEquals(List.of(), listFiles(directory));
        }
    }

    private static @NotNull ReleaseRepository requireRepository(@NotNull String text) {
        ReleaseRepository repository = ReleaseRepository.parse(text);

        Assertions.assertNotNull(repository, text);

        return repository;
    }

    private static @NotNull ReleaseAsset requireAsset(@NotNull Release release, @NotNull String name) {
        ReleaseAsset asset = release.findAsset(name);

        Assertions.assertNotNull(asset, name);

        return asset;
    }

    private static @NotNull List<@NotNull String> getNames(@NotNull Release release) {
        List<String> names = new ArrayList<>();

        for (ReleaseAsset asset : release.getAssets()) {
            names.add(asset.getName());
        }

        return names;
    }

    private static @NotNull List<@NotNull Path> listFiles(@NotNull Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        }
    }
}
