package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Identifies the contents of a payload: its size and its SHA-256 digest. A download checks the bytes it received against the payload the
 * offering side announced.
 */
public final class TransferPayload {
    /**
     * The length of a payload digest in bytes.
     */
    public static final int DIGEST_LENGTH = 32;

    /**
     * The size of the blocks a payload's bytes are read in to compute their digest.
     */
    static final int READ_BLOCK_SIZE = 0x10000;

    private static final @NotNull String DIGEST_ALGORITHM = "SHA-256";
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Creates a {@link TransferPayload} instance of a size and a digest.
     *
     * @param size   the size in bytes
     * @param digest the SHA-256 digest of the bytes, copied
     * @return the created {@link TransferPayload} instance
     * @throws IllegalArgumentException if the size is negative or the digest does not have {@value #DIGEST_LENGTH} bytes
     */
    public static @NotNull TransferPayload of(long size, byte @NotNull [] digest) {
        if (size < 0 || digest.length != DIGEST_LENGTH) {
            throw Exceptions.newIllegalArgumentException("A payload has a size of at least 0 and a digest of {0} bytes, not {1} and {2}.",
                                                         DIGEST_LENGTH, size, digest.length);
        }

        return new TransferPayload(size, digest.clone());
    }

    /**
     * Creates a {@link TransferPayload} instance of a source's bytes, read once.
     *
     * @param source the source
     * @return the created {@link TransferPayload} instance
     * @throws IOException if the source cannot be read
     */
    public static @NotNull TransferPayload of(@NotNull TransferSource source) throws IOException {
        MessageDigest digest = createDigest();
        ByteBuffer block = ByteBuffer.allocate(READ_BLOCK_SIZE);
        long size = source.getSize();

        for (long offset = 0; offset < size; offset += block.limit()) {
            block.clear().limit((int) Math.min(READ_BLOCK_SIZE, size - offset));
            source.read(offset, block);
            digest.update(block.array(), 0, block.limit());
        }

        return new TransferPayload(size, digest.digest());
    }

    /**
     * Creates a digest of the payloads' algorithm.
     *
     * @return the created {@link MessageDigest} instance
     */
    static @NotNull MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The digest {0} is not available.", DIGEST_ALGORITHM);
        }
    }


    private final long size;
    private final byte @NotNull [] digest;

    private TransferPayload(long size, byte @NotNull [] digest) {
        this.size   = size;
        this.digest = digest;
    }

    /**
     * @return the size in bytes
     */
    public long getSize() {
        return this.size;
    }

    /**
     * @return a copy of the SHA-256 digest of the bytes
     */
    public byte @NotNull [] getDigest() {
        return this.digest.clone();
    }

    /**
     * @param digest a SHA-256 digest
     * @return {@code true} if the digest is the payload's, {@code false} otherwise
     */
    boolean hasDigest(byte @NotNull [] digest) {
        return MessageDigest.isEqual(this.digest, digest);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TransferPayload payload && this.size == payload.size && Arrays.equals(this.digest, payload.digest);
    }

    @Override
    public int hashCode() {
        return HASH_MULTIPLIER * Long.hashCode(this.size) + Arrays.hashCode(this.digest);
    }

    @Override
    public @NotNull String toString() {
        return this.size + " bytes, " + HexFormat.of().formatHex(this.digest);
    }
}
