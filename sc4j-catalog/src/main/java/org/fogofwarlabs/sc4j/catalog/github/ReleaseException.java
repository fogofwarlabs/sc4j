package org.fogofwarlabs.sc4j.catalog.github;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serial;

/**
 * Signals that reading a release or one of its files failed, with the reason ({@link ReleaseProblem}) for a short message; failures of local
 * files are plain {@link IOException}s.
 */
public final class ReleaseException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final @NotNull ReleaseProblem problem;

    /**
     * Creates a {@link ReleaseException} instance.
     *
     * @param problem the reason
     * @param message the message for logs
     */
    public ReleaseException(@NotNull ReleaseProblem problem, @NotNull String message) {
        this(problem, message, null);
    }

    /**
     * Creates a {@link ReleaseException} instance with the failure that caused it.
     *
     * @param problem the reason
     * @param message the message for logs
     * @param cause   the failure that caused it, or {@code null}
     */
    public ReleaseException(@NotNull ReleaseProblem problem, @NotNull String message, @Nullable Throwable cause) {
        super(message, cause);

        this.problem = problem;
    }

    /**
     * @return the reason
     */
    public @NotNull ReleaseProblem getProblem() {
        return this.problem;
    }
}
