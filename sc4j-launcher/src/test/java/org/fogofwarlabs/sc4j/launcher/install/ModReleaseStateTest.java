package org.fogofwarlabs.sc4j.launcher.install;

import org.fogofwarlabs.sc4j.manifest.version.ModVersion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests how an installed mod jar stands to the releases of its repository: the latest release, an older one, and a jar that is not a release.
 */
final class ModReleaseStateTest {
    private static final @NotNull ModVersion VERSION = ModVersion.parse("1.2.0");

    @Test
    void decide_should_tell_the_latest_release_and_an_older_one() {
        Assertions.assertEquals(ModReleaseState.LATEST, ModReleaseState.decide(VERSION, "v1.2.0", false, false));
        Assertions.assertEquals(ModReleaseState.OLDER, ModReleaseState.decide(VERSION, "v1.2.0", true, false));
        Assertions.assertEquals(ModReleaseState.LATEST, ModReleaseState.decide(VERSION, "1.2.0", false, false), "a tag without v");
        Assertions.assertEquals(ModReleaseState.OLDER, ModReleaseState.decide(VERSION, "stable", true, false), "a tag that is not a version");
    }

    @Test
    void decide_should_tell_a_jar_that_is_not_its_recorded_release() {
        Assertions.assertEquals(ModReleaseState.UNRELEASED, ModReleaseState.decide(VERSION, null, false, false), "no record");
        Assertions.assertEquals(ModReleaseState.UNRELEASED, ModReleaseState.decide(VERSION, "v1.1.0", false, false), "another version");
        Assertions.assertEquals(ModReleaseState.UNRELEASED, ModReleaseState.decide(ModVersion.parse("1.2.0-SNAPSHOT"), "v1.2.0", true, false),
                                "a development build");
    }

    @Test
    void decide_should_take_a_jar_the_install_ships_as_the_latest() {
        Assertions.assertEquals(ModReleaseState.LATEST, ModReleaseState.decide(VERSION, null, false, true));
        Assertions.assertEquals(ModReleaseState.LATEST, ModReleaseState.decide(VERSION, "v1.0.0", true, true));
    }
}
