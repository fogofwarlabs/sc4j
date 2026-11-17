package org.fogofwarlabs.sc4j.launcher.update;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Serial;

/**
 * Signals that a downloaded user package is broken or not an SC4J package.
 */
public final class InvalidPackageException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@link InvalidPackageException} instance.
     *
     * @param message the message for logs
     * @param cause   the failure that caused it
     */
    public InvalidPackageException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
