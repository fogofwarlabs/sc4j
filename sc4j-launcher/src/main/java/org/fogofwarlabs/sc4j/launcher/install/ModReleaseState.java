package org.fogofwarlabs.sc4j.launcher.install;

import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.manifest.version.ModVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tells how an installed mod jar stands to the releases of its repository, as far as the launcher knows: players whose jars differ may not
 * play together.
 */
public enum ModReleaseState {
    /**
     * The jar is the release it was installed from and no newer release is known, or the install ships the jar.
     */
    LATEST,
    /**
     * The jar is the release it was installed from, and a newer release is known.
     */
    OLDER,
    /**
     * The jar was not installed from a release, such as a jar copied into {@code mods/} or built from source, or its version differs from the
     * release it was installed from.
     */
    UNRELEASED;

    /**
     * Decides the state of an installed mod jar: {@link #LATEST} for a shipped jar; {@link #UNRELEASED} without a record, or when the recorded
     * tag is a version other than the jar's; else {@link #OLDER} when a newer release is known and {@link #LATEST} otherwise.
     *
     * @param version         the version of the jar's manifest
     * @param recordedRelease the tag of the release the jar was installed from ({@link ModSource#getRelease()}), or {@code null} when the jar has no record
     * @param newerKnown      whether a newer release of the repository is known ({@link ModInstaller#findUpdates})
     * @param shipped         whether the install ships the jar, so that a launcher update updates it
     * @return the state of the jar
     */
    public static @NotNull ModReleaseState decide(@NotNull ModVersion version, @Nullable String recordedRelease, boolean newerKnown, boolean shipped) {
        if (shipped) {
            return LATEST;
        }

        if (recordedRelease == null) {
            return UNRELEASED;
        }

        ReleaseVersion recorded = ReleaseVersion.parse(recordedRelease);

        if (recorded != null && !recorded.toString().equals(version.toString())) {
            return UNRELEASED;
        }

        return newerKnown ? OLDER : LATEST;
    }
}
