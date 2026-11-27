package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * Signals that a model could not be loaded or failed during inference.
 */
public final class ModelException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@link ModelException} instance.
     *
     * @param message the description of the failure
     */
    public ModelException(@NotNull String message) {
        super(message);
    }

    /**
     * Creates a {@link ModelException} instance.
     *
     * @param message the description of the failure
     * @param cause   the failure that caused it
     */
    public ModelException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
