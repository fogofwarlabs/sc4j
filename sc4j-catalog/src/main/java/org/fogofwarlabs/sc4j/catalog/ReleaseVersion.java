package org.fogofwarlabs.sc4j.catalog;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a semantic version {@code major.minor.patch}, optionally followed by a pre-release label after a hyphen, as a release tag
 * ({@code v1.2.0}) or a program version ({@code 0.1.0-SNAPSHOT}) gives it. Versions are ordered the semantic versioning way: by the three
 * numbers, then a version with a pre-release label before the same version without one, then by the label's dot-separated parts.
 */
public final class ReleaseVersion {
    /**
     * The SC4J version of this release ({@link EngineVersion#SC4J_VERSION}).
     */
    public static final @NotNull ReleaseVersion CURRENT;

    private static final @NotNull Pattern VERSION_PATTERN = Pattern.compile("[vV]?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})" +
                                                                            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?");
    private static final @NotNull Pattern NUMERIC_PATTERN = Pattern.compile("[0-9]+");
    private static final @NotNull Pattern PART_SEPARATOR_PATTERN = Pattern.compile("\\.");
    private static final @NotNull String SNAPSHOT_LABEL = "SNAPSHOT";
    private static final int MAJOR_GROUP = 1;
    private static final int MINOR_GROUP = 2;
    private static final int PATCH_GROUP = 3;
    private static final int PRE_RELEASE_GROUP = 4;

    static {
        ReleaseVersion current = parse(EngineVersion.SC4J_VERSION);

        if (current == null) {
            throw Exceptions.newIllegalStateException("The SC4J version {0} is not a semantic version", EngineVersion.SC4J_VERSION);
        }

        CURRENT = current;
    }

    /**
     * Creates a {@link ReleaseVersion} instance from a release tag or a version text.
     *
     * @param text the text, such as {@code v1.2.0}, {@code 1.2.0} or {@code 0.1.0-SNAPSHOT}
     * @return the created {@link ReleaseVersion} instance, or {@code null} when the text is not a semantic version
     */
    public static @Nullable ReleaseVersion parse(@NotNull String text) {
        Matcher matcher = VERSION_PATTERN.matcher(text.strip());

        if (!matcher.matches()) {
            return null;
        }

        String preRelease = matcher.group(PRE_RELEASE_GROUP);

        return new ReleaseVersion(Integer.parseInt(matcher.group(MAJOR_GROUP)), Integer.parseInt(matcher.group(MINOR_GROUP)),
                                  Integer.parseInt(matcher.group(PATCH_GROUP)), preRelease == null ? "" : preRelease);
    }

    private static int compareParts(@NotNull String first, @NotNull String second) {
        boolean firstNumeric = NUMERIC_PATTERN.matcher(first).matches();
        boolean secondNumeric = NUMERIC_PATTERN.matcher(second).matches();

        if (firstNumeric && secondNumeric) {
            String firstDigits = stripLeadingZeros(first);
            String secondDigits = stripLeadingZeros(second);

            return firstDigits.length() != secondDigits.length() ? Integer.compare(firstDigits.length(), secondDigits.length())
                                                                 : firstDigits.compareTo(secondDigits);
        }

        if (firstNumeric != secondNumeric) {
            return firstNumeric ? -1 : 1;
        }

        return first.compareTo(second);
    }

    private static @NotNull String stripLeadingZeros(@NotNull String digits) {
        int start = 0;

        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }

        return digits.substring(start);
    }


    private final int major;
    private final int minor;
    private final int patch;
    private final @NotNull String preRelease;

    /**
     * Creates a {@link ReleaseVersion} instance.
     *
     * @param major      the major version
     * @param minor      the minor version
     * @param patch      the patch version
     * @param preRelease the pre-release label, or an empty string for a release
     */
    private ReleaseVersion(int major, int minor, int patch, @NotNull String preRelease) {
        this.major      = major;
        this.minor      = minor;
        this.patch      = patch;
        this.preRelease = preRelease;
    }

    /**
     * @return the pre-release label, or an empty string for a release
     */
    public @NotNull String getPreRelease() {
        return this.preRelease;
    }

    /**
     * @return {@code true} if the pre-release label ends with {@code SNAPSHOT}, a development build, {@code false} otherwise
     */
    public boolean isSnapshot() {
        return this.preRelease.toUpperCase(Locale.ROOT).endsWith(SNAPSHOT_LABEL);
    }

    /**
     * Tells whether this version comes after another one.
     *
     * @param other the other version
     * @return {@code true} if this version is newer than the other one, {@code false} otherwise
     */
    public boolean isNewerThan(@NotNull ReleaseVersion other) {
        if (this.major != other.major) {
            return this.major > other.major;
        }

        if (this.minor != other.minor) {
            return this.minor > other.minor;
        }

        if (this.patch != other.patch) {
            return this.patch > other.patch;
        }

        if (this.preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return this.preRelease.isEmpty() && !other.preRelease.isEmpty();
        }

        String[] parts = PART_SEPARATOR_PATTERN.split(this.preRelease);
        String[] otherParts = PART_SEPARATOR_PATTERN.split(other.preRelease);

        for (int index = 0; index < Math.min(parts.length, otherParts.length); index++) {
            int comparison = compareParts(parts[index], otherParts[index]);

            if (comparison != 0) {
                return comparison > 0;
            }
        }

        return parts.length > otherParts.length;
    }

    /**
     * {@return a string representation of the object}
     *
     * @implSpec Returns {@code major.minor.patch}, followed by {@code -} and the pre-release label if there is one.
     */
    @Override
    public @NotNull String toString() {
        String release = this.major + "." + this.minor + "." + this.patch;

        return this.preRelease.isEmpty() ? release : release + "-" + this.preRelease;
    }
}
