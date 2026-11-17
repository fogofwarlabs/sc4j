package org.fogofwarlabs.sc4j.catalog;

import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests release versions: release tags and program versions, the semantic versioning order and development builds.
 */
final class ReleaseVersionTest {
    @ParameterizedTest
    @CsvSource({"v1.2.0, 1.2.0", "1.2.0, 1.2.0", "V0.10.3, 0.10.3", "0.1.0-SNAPSHOT, 0.1.0-SNAPSHOT", "1.0.0-rc.1+build.5, 1.0.0-rc.1"})
    void parse_should_read_tags_and_program_versions(@NotNull String text, @NotNull String expected) {
        ReleaseVersion version = ReleaseVersion.parse(text);

        Assertions.assertNotNull(version, text);
        Assertions.assertEquals(expected, version.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "development", "1.2", "1.2.3.4", "01.2.3", "v1.2.x", "1.2.3-", "release-1.2.3"})
    void parse_should_refuse_text_that_is_no_semantic_version(@NotNull String text) {
        Assertions.assertNull(ReleaseVersion.parse(text), text);
    }

    @ParameterizedTest
    @CsvSource({"1.2.1, 1.2.0", "1.10.0, 1.9.9", "2.0.0, 1.99.99", "1.0.0, 1.0.0-SNAPSHOT", "1.0.0, 1.0.0-rc.1", "1.0.0-rc.2, 1.0.0-rc.1",
                "1.0.0-rc.10, 1.0.0-rc.9", "1.0.0-rc.1, 1.0.0-rc", "1.0.0-beta, 1.0.0-alpha", "1.0.0-alpha, 1.0.0-1"})
    void is_newer_than_should_follow_the_semantic_versioning_order(@NotNull String newer, @NotNull String older) {
        ReleaseVersion newerVersion = ReleaseVersion.parse(newer);
        ReleaseVersion olderVersion = ReleaseVersion.parse(older);

        Assertions.assertNotNull(newerVersion);
        Assertions.assertNotNull(olderVersion);
        Assertions.assertTrue(newerVersion.isNewerThan(olderVersion), newer + " > " + older);
        Assertions.assertFalse(olderVersion.isNewerThan(newerVersion), older + " < " + newer);
    }

    @Test
    void is_newer_than_should_be_false_for_the_same_version() {
        ReleaseVersion version = ReleaseVersion.parse("v1.2.3");
        ReleaseVersion same = ReleaseVersion.parse("1.2.3");

        Assertions.assertNotNull(version);
        Assertions.assertNotNull(same);
        Assertions.assertFalse(version.isNewerThan(same));
    }

    @Test
    void is_snapshot_should_tell_development_builds() {
        ReleaseVersion snapshot = ReleaseVersion.parse("0.1.0-SNAPSHOT");
        ReleaseVersion release = ReleaseVersion.parse("0.1.0");

        Assertions.assertNotNull(snapshot);
        Assertions.assertNotNull(release);
        Assertions.assertTrue(snapshot.isSnapshot());
        Assertions.assertFalse(release.isSnapshot());
    }

    @Test
    void current_should_be_the_sc4j_version() {
        Assertions.assertEquals(EngineVersion.SC4J_VERSION, ReleaseVersion.CURRENT.toString());
    }
}
