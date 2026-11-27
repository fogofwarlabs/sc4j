package org.fogofwarlabs.sc4j.launcher.link;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serial;

/**
 * Signals that a launcher link failed: the connection was refused or closed, a service answered a request with a failure, or a frame broke
 * the link's rules.
 */
public final class LauncherLinkException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@link LauncherLinkException} instance with a formatted message.
     *
     * @param message   the message format with {@code {index}} placeholders
     * @param arguments the placeholder arguments
     * @return the created {@link LauncherLinkException} instance
     */
    public static @NotNull LauncherLinkException create(@NotNull String message, @Nullable Object @NotNull ... arguments) {
        return new LauncherLinkException(Exceptions.format(message, arguments), null);
    }

    /**
     * Creates a {@link LauncherLinkException} instance with a formatted message and the failure that caused it.
     *
     * @param cause     the failure that caused it
     * @param message   the message format with {@code {index}} placeholders
     * @param arguments the placeholder arguments
     * @return the created {@link LauncherLinkException} instance
     */
    public static @NotNull LauncherLinkException create(@NotNull Throwable cause, @NotNull String message, @Nullable Object @NotNull ... arguments) {
        return new LauncherLinkException(Exceptions.format(message, arguments), cause);
    }


    /**
     * Creates a {@link LauncherLinkException} instance.
     *
     * @param message the formatted message
     * @param cause   the failure that caused it, or {@code null}
     */
    private LauncherLinkException(@NotNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
