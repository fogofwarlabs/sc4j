package org.fogofwarlabs.sc4j.manifest.version;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents an engine API version {@code major.minor}: the version a mod is built against (the manifest field
 * {@code engine}) or the version a runtime provides ({@link #CURRENT}).
 * <p>
 * A runtime supports a mod when both have the same major version and the mod requires no higher minor version than
 * the runtime provides ({@link #supports(EngineVersion)}).
 */
public final class EngineVersion {
    /**
     * The engine API version of this SC4J release: {@code major.minor} of the SC4J version that the build writes into this module. The
     * launcher and the runtime check mods against it.
     */
    public static final @NotNull EngineVersion CURRENT;
    /**
     * The SC4J version of this release, such as {@code 0.1.0-SNAPSHOT}: the version that the build writes into this module.
     */
    public static final @NotNull String SC4J_VERSION;

    private static final @NotNull Pattern VERSION_PATTERN = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private static final @NotNull Pattern SC4J_VERSION_PATTERN = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})" +
                                                                                 "(?:-[0-9A-Za-z]+(?:\\.[0-9A-Za-z]+)*)?");
    private static final int MAJOR_GROUP = 1;
    private static final int MINOR_GROUP = 2;
    private static final int HASH_MULTIPLIER = 31;
    private static final @NotNull String SC4J_VERSION_RESOURCE = "sc4j-version.properties";
    private static final @NotNull String SC4J_VERSION_KEY = "version";

    static {
        SC4J_VERSION = readSc4jVersion();
        CURRENT      = fromSc4jVersion(SC4J_VERSION);
    }

    /**
     * Creates a {@link EngineVersion} instance from its text form.
     *
     * @param text the version text, such as {@code 1.0}
     * @return the created {@link EngineVersion} instance
     * @throws IllegalArgumentException if the text is not {@code major.minor} with decimal numbers
     */
    public static @NotNull EngineVersion parse(@NotNull String text) {
        Matcher matcher = VERSION_PATTERN.matcher(text);

        if (!matcher.matches()) {
            throw Exceptions.newIllegalArgumentException("Invalid engine version (expected major.minor): {0}", text);
        }

        return new EngineVersion(Integer.parseInt(matcher.group(MAJOR_GROUP)), Integer.parseInt(matcher.group(MINOR_GROUP)));
    }

    /**
     * Creates the engine API version of an SC4J version: its major and minor version.
     *
     * @param sc4jVersion the SC4J version {@code major.minor.patch} with an optional pre-release label, such as {@code 0.1.0-SNAPSHOT}
     * @return the created {@link EngineVersion} instance, such as {@code 0.1}
     * @throws IllegalArgumentException if the text is not {@code major.minor.patch} with an optional pre-release label
     */
    static @NotNull EngineVersion fromSc4jVersion(@NotNull String sc4jVersion) {
        Matcher matcher = SC4J_VERSION_PATTERN.matcher(sc4jVersion);

        if (!matcher.matches()) {
            throw Exceptions.newIllegalArgumentException("Invalid SC4J version (expected major.minor.patch): {0}", sc4jVersion);
        }

        return new EngineVersion(Integer.parseInt(matcher.group(MAJOR_GROUP)), Integer.parseInt(matcher.group(MINOR_GROUP)));
    }

    /**
     * Reads the SC4J version that the build writes into this module.
     *
     * @return the SC4J version, such as {@code 0.1.0-SNAPSHOT}
     * @throws IllegalStateException if the resource is missing, unreadable or names no version
     */
    static @NotNull String readSc4jVersion() {
        Properties properties = new Properties();

        try (InputStream input = EngineVersion.class.getResourceAsStream(SC4J_VERSION_RESOURCE)) {
            if (input == null) {
                throw Exceptions.newIllegalStateException("Missing resource {0} of {1}", SC4J_VERSION_RESOURCE, EngineVersion.class.getName());
            }

            properties.load(input);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Unreadable resource {0} of {1}", SC4J_VERSION_RESOURCE,
                                                      EngineVersion.class.getName());
        }

        String version = properties.getProperty(SC4J_VERSION_KEY);

        if (version == null) {
            throw Exceptions.newIllegalStateException("The resource {0} names no {1}", SC4J_VERSION_RESOURCE, SC4J_VERSION_KEY);
        }

        return version;
    }


    private final int major;
    private final int minor;

    /**
     * Creates a {@link EngineVersion} instance.
     *
     * @param major the major version; a change breaks mods built for another major version
     * @param minor the minor version; a change only adds to the API
     */
    private EngineVersion(int major, int minor) {
        this.major = major;
        this.minor = minor;
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
     * Checks whether a runtime of this version can run a mod built for a version.
     *
     * @param required the engine version the mod is built for
     * @return {@code true} if the major versions are equal and the required minor version is not higher than this one, {@code false} otherwise
     */
    public boolean supports(@NotNull EngineVersion required) {
        return this.major == required.major && required.minor <= this.minor;
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     * <p>
     * The {@code equals} method implements an equivalence relation on non-null object references.
     *
     * @param other the reference object with which to compare
     * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
     * @implSpec Two engine versions are equal when their major and minor versions are equal.
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof EngineVersion version && this.major == version.major && this.minor == version.minor;
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     * @implSpec Combines the major and minor versions, consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return this.major * HASH_MULTIPLIER + this.minor;
    }

    /**
     * {@return a string representation of the object}
     *
     * @implSpec Returns the manifest text form {@code major.minor}.
     */
    @Override
    public @NotNull String toString() {
        return this.major + "." + this.minor;
    }
}
