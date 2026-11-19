package org.fogofwarlabs.sc4j.manifest.version;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a mod version {@code major.minor.patch}, optionally followed by a pre-release label after a hyphen
 * ({@code 1.0.0-beta.2}), as the manifest field {@code version} gives it.
 */
public final class ModVersion {
    private static final @NotNull Pattern VERSION_PATTERN = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})" +
                                                                            "(?:-([0-9A-Za-z]+(?:\\.[0-9A-Za-z]+)*))?");
    private static final int MAJOR_GROUP = 1;
    private static final int MINOR_GROUP = 2;
    private static final int PATCH_GROUP = 3;
    private static final int PRE_RELEASE_GROUP = 4;

    /**
     * Creates a {@link ModVersion} instance from its text form.
     *
     * @param text the version text, such as {@code 1.0.0} or {@code 1.2.0-beta.1}
     * @return the created {@link ModVersion} instance
     * @throws IllegalArgumentException if the text is not {@code major.minor.patch} with an optional pre-release label
     */
    public static @NotNull ModVersion parse(@NotNull String text) {
        Matcher matcher = VERSION_PATTERN.matcher(text);

        if (!matcher.matches()) {
            throw Exceptions.newIllegalArgumentException("Invalid mod version (expected major.minor.patch): {0}", text);
        }

        String preRelease = matcher.group(PRE_RELEASE_GROUP);

        return new ModVersion(Integer.parseInt(matcher.group(MAJOR_GROUP)), Integer.parseInt(matcher.group(MINOR_GROUP)),
                              Integer.parseInt(matcher.group(PATCH_GROUP)), preRelease == null ? "" : preRelease);
    }


    private final int major;
    private final int minor;
    private final int patch;
    private final @NotNull String preRelease;

    /**
     * Creates a {@link ModVersion} instance.
     *
     * @param major      the major version
     * @param minor      the minor version
     * @param patch      the patch version
     * @param preRelease the pre-release label, or an empty string for a release
     */
    private ModVersion(int major, int minor, int patch, @NotNull String preRelease) {
        this.major      = major;
        this.minor      = minor;
        this.patch      = patch;
        this.preRelease = preRelease;
    }

    /**
     * @return the major version
     */
    public int getMajor() {
        return this.major;
    }

    /**
     * @return the minor version
     */
    public int getMinor() {
        return this.minor;
    }

    /**
     * @return the patch version
     */
    public int getPatch() {
        return this.patch;
    }

    /**
     * @return the pre-release label, or an empty string for a release
     */
    public @NotNull String getPreRelease() {
        return this.preRelease;
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     * <p>
     * The {@code equals} method implements an equivalence relation on non-null object references.
     *
     * @param other the reference object with which to compare
     * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
     * @implSpec Two mod versions are equal when their text forms are equal.
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ModVersion version && this.toString().equals(version.toString());
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     * @implSpec Returns the hash code of the text form, consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return this.toString().hashCode();
    }

    /**
     * {@return a string representation of the object}
     *
     * @implSpec Returns the manifest text form {@code major.minor.patch}, followed by {@code -} and the pre-release label if
     * there is one.
     */
    @Override
    public @NotNull String toString() {
        String release = this.major + "." + this.minor + "." + this.patch;

        return this.preRelease.isEmpty() ? release : release + "-" + this.preRelease;
    }
}
