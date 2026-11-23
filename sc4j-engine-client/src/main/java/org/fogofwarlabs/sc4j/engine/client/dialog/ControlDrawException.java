package org.fogofwarlabs.sc4j.engine.client.dialog;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;

/**
 * Reports that a {@link Control} could not draw because a resource its look reads (an image, a sheet frame, a font) failed to
 * load. The draw methods of {@link Control} and {@link Dialog} throw it unchecked, so a mod's control can read resources lazily;
 * the frame that draws the dialog decides whether it skips the dialog or ends.
 */
public final class ControlDrawException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a {@link ControlDrawException} instance for a failed resource read.
     *
     * @param cause     the failure of the read
     * @param message   the message with {@code {0}} placeholders
     * @param arguments the placeholder values
     * @return the created {@link ControlDrawException} instance
     */
    public static @NotNull ControlDrawException create(@NotNull Throwable cause, @NotNull String message, @Nullable Object @NotNull ... arguments) {
        return new ControlDrawException(Exceptions.format(message, arguments), cause);
    }


    private ControlDrawException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
