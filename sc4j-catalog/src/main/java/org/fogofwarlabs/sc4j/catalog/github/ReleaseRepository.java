package org.fogofwarlabs.sc4j.catalog.github;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names a GitHub repository, {@code owner/name}, as typed: {@code owner/name} or the repository's web address {@code https://github.com/owner/name},
 * with or without {@code .git} and further path segments.
 */
public final class ReleaseRepository {
    private static final @NotNull Pattern REPOSITORY_PATTERN = Pattern.compile("([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})");
    private static final @NotNull Pattern ADDRESS_PREFIX_PATTERN = Pattern.compile("(?i)(?:https?://)?(?:www\\.)?github\\.com/");
    private static final @NotNull String PATH_SEPARATOR = "/";
    private static final @NotNull String GIT_SUFFIX = ".git";
    private static final @NotNull String CURRENT_FOLDER = ".";
    private static final @NotNull String PARENT_FOLDER = "..";
    private static final int OWNER_GROUP = 1;
    private static final int NAME_GROUP = 2;
    private static final int ADDRESS_SEGMENTS = 3;

    /**
     * Creates a {@link ReleaseRepository} instance from its owner and name.
     *
     * @param owner the owner, a user or an organization
     * @param name  the repository name
     * @return the created {@link ReleaseRepository} instance
     * @throws IllegalArgumentException if the owner and name name no repository
     */
    public static @NotNull ReleaseRepository of(@NotNull String owner, @NotNull String name) {
        ReleaseRepository repository = parse(owner + PATH_SEPARATOR + name);

        if (repository == null || !repository.owner.equals(owner) || !repository.name.equals(name)) {
            throw Exceptions.newIllegalArgumentException("Invalid repository: {0}/{1}", owner, name);
        }

        return repository;
    }

    /**
     * Creates a {@link ReleaseRepository} instance from a typed text.
     *
     * @param text {@code owner/name} or the repository's web address
     * @return the created {@link ReleaseRepository} instance, or {@code null} when the text names no repository
     */
    public static @Nullable ReleaseRepository parse(@NotNull String text) {
        String path = text.strip();
        Matcher prefix = ADDRESS_PREFIX_PATTERN.matcher(path);

        if (prefix.lookingAt()) {
            String[] segments = path.substring(prefix.end()).split(PATH_SEPARATOR, ADDRESS_SEGMENTS);

            path = segments.length < 2 ? "" : segments[0] + PATH_SEPARATOR + segments[1];
        }

        if (path.endsWith(PATH_SEPARATOR)) {
            path = path.substring(0, path.length() - PATH_SEPARATOR.length());
        }

        if (path.toLowerCase(Locale.ROOT).endsWith(GIT_SUFFIX)) {
            path = path.substring(0, path.length() - GIT_SUFFIX.length());
        }

        Matcher matcher = REPOSITORY_PATTERN.matcher(path);

        if (!matcher.matches() || matcher.group(NAME_GROUP).equals(CURRENT_FOLDER) || matcher.group(NAME_GROUP).equals(PARENT_FOLDER)) {
            return null;
        }

        return new ReleaseRepository(matcher.group(OWNER_GROUP), matcher.group(NAME_GROUP));
    }


    private final @NotNull String owner;
    private final @NotNull String name;

    /**
     * Creates a {@link ReleaseRepository} instance.
     *
     * @param owner the owner, a user or an organization
     * @param name  the repository name
     */
    private ReleaseRepository(@NotNull String owner, @NotNull String name) {
        this.owner = owner;
        this.name  = name;
    }

    /**
     * @return the owner, a user or an organization
     */
    public @NotNull String getOwner() {
        return this.owner;
    }

    /**
     * @return the repository name
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * {@return a string representation of the object}
     *
     * @implSpec Returns {@code owner/name}.
     */
    @Override
    public @NotNull String toString() {
        return this.owner + PATH_SEPARATOR + this.name;
    }
}
