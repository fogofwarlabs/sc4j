package org.fogofwarlabs.sc4j.catalog.github;

/**
 * Lists why reading a release or one of its files failed, for a short message.
 */
public enum ReleaseProblem {
    /**
     * The text names no GitHub repository.
     */
    INVALID_REPOSITORY,
    /**
     * The repository does not exist or has no release.
     */
    NOT_FOUND,
    /**
     * GitHub cannot be reached or answered with an error.
     */
    UNAVAILABLE,
    /**
     * GitHub's answer is not what a release or a file looks like.
     */
    INVALID_RESPONSE,
    /**
     * The release lacks the files the reader needs.
     */
    ASSET_MISSING,
    /**
     * A file is larger than the reader reads.
     */
    TOO_LARGE,
    /**
     * A downloaded file does not match its checksum file.
     */
    CHECKSUM_MISMATCH
}
