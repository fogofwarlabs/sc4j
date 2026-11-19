package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Names a payload a peer offers ({@link TransferService#offer}) and another peer requests ({@link TransferService#request}): {@code 1} to
 * {@value #MAXIMUM_LENGTH} bytes that the offering side chooses. Two keys are equal when their bytes are.
 */
public final class TransferKey {
    /**
     * The longest key in bytes.
     */
    public static final int MAXIMUM_LENGTH = 64;

    /**
     * Creates a {@link TransferKey} instance of a text's UTF-8 bytes.
     *
     * @param text the text
     * @return the created {@link TransferKey} instance
     * @throws IllegalArgumentException if the text is empty or longer than {@value #MAXIMUM_LENGTH} bytes
     */
    public static @NotNull TransferKey of(@NotNull String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a {@link TransferKey} instance of bytes.
     *
     * @param bytes the bytes, copied
     * @return the created {@link TransferKey} instance
     * @throws IllegalArgumentException if there are no bytes or more than {@value #MAXIMUM_LENGTH}
     */
    public static @NotNull TransferKey of(byte @NotNull [] bytes) {
        if (bytes.length == 0 || bytes.length > MAXIMUM_LENGTH) {
            throw Exceptions.newIllegalArgumentException("A transfer key has 1 to {0} bytes, not {1}.", MAXIMUM_LENGTH, bytes.length);
        }

        return new TransferKey(bytes.clone());
    }


    private final byte @NotNull [] bytes;

    private TransferKey(byte @NotNull [] bytes) {
        this.bytes = bytes;
    }

    /**
     * @return a copy of the key's bytes
     */
    public byte @NotNull [] getBytes() {
        return this.bytes.clone();
    }

    /**
     * @return the number of the key's bytes
     */
    public int getLength() {
        return this.bytes.length;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TransferKey key && Arrays.equals(this.bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.bytes);
    }

    @Override
    public @NotNull String toString() {
        return HexFormat.of().formatHex(this.bytes);
    }
}
