package org.fogofwarlabs.sc4j.beaconlink.channel;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * Seals and opens the datagrams of one side of a channel with ChaCha20-Poly1305: the send key seals what this side sends, under the other
 * side's receive number; the receive key opens what the other side sent.
 * <p>
 * Each direction counts its datagrams from {@code 0}; the counter is the nonce (four zero bytes and the eight counter bytes), so a key never
 * seals two datagrams with one nonce, and a direction that reaches {@value #MAXIMUM_COUNTER} datagrams seals nothing more. The header (magic,
 * receive number and counter) is the additional authenticated data. Opening checks the length and the {@link ReplayWindow} before the
 * authentication and records the counter only after it, so tampered, truncated, replayed, too old and foreign-key datagrams are dropped
 * without changing the channel.
 * <p>
 * Layout (big-endian): the header of {@link ChannelDatagramUtils}, the counter (eight bytes), the sealed frame and the
 * {@value #TAG_LENGTH}-byte tag. A sealed datagram is {@value #OVERHEAD} bytes longer than its frame.
 */
public final class ChannelCipher {
    /**
     * The byte length of the Poly1305 tag.
     */
    public static final int TAG_LENGTH = 16;
    /**
     * The bytes a sealed datagram adds to its frame: the header, the counter and the tag.
     */
    public static final int OVERHEAD = ChannelDatagramUtils.HEADER_LENGTH + Long.BYTES + TAG_LENGTH;
    /**
     * The number of datagrams one direction seals at most.
     */
    public static final long MAXIMUM_COUNTER = 1L << 60;

    private static final @NotNull String ALGORITHM = "ChaCha20-Poly1305";
    private static final @NotNull String KEY_ALGORITHM = "ChaCha20";
    private static final int NONCE_LENGTH = 12;
    private static final int COUNTER_OFFSET = NONCE_LENGTH - Long.BYTES;
    private static final int AUTHENTICATED_LENGTH = ChannelDatagramUtils.HEADER_LENGTH + Long.BYTES;

    private static @NotNull IvParameterSpec createNonce(long counter) {
        byte[] nonce = new byte[NONCE_LENGTH];

        for (int index = 0; index < Long.BYTES; index++) {
            nonce[COUNTER_OFFSET + index] = (byte) (counter >>> (Long.BYTES - 1 - index) * Byte.SIZE);
        }

        return new IvParameterSpec(nonce);
    }


    private final long remoteReceiveNumber;
    private final @NotNull SecretKeySpec sendKey;
    private final @NotNull SecretKeySpec receiveKey;
    private final @NotNull Cipher sealer;
    private final @NotNull Cipher opener;
    private final @NotNull ReplayWindow window;

    private long sendCounter;
    private boolean lastOpenedNewest;

    /**
     * Creates a {@link ChannelCipher} instance.
     *
     * @param keys                the channel's keys of this side
     * @param remoteReceiveNumber the other side's receive number, which every sealed datagram carries
     */
    public ChannelCipher(@NotNull ChannelKeys keys, long remoteReceiveNumber) {
        this.remoteReceiveNumber = remoteReceiveNumber;
        this.sendKey             = new SecretKeySpec(keys.getSendKey(), KEY_ALGORITHM);
        this.receiveKey          = new SecretKeySpec(keys.getReceiveKey(), KEY_ALGORITHM);

        try {
            this.sealer = Cipher.getInstance(ALGORITHM);
            this.opener = Cipher.getInstance(ALGORITHM);
        } catch (GeneralSecurityException exception) {
            throw Exceptions.newIllegalStateException(exception, "The JDK has no {0} cipher.", ALGORITHM);
        }

        this.window = new ReplayWindow();

        this.sendCounter      = 0L;
        this.lastOpenedNewest = false;
    }

    /**
     * @return {@code true} if the last opened datagram had the highest counter so far, {@code false} otherwise (it came late)
     */
    public boolean isLastOpenedNewest() {
        return this.lastOpenedNewest;
    }


    /**
     * Seals a frame into a datagram for the other side.
     *
     * @param frame the frame
     * @return the datagram, or {@code null} if this direction sealed {@value #MAXIMUM_COUNTER} datagrams
     * @throws IllegalArgumentException if the datagram would be longer than {@value ChannelDatagramUtils#MAXIMUM_DATAGRAM_LENGTH} bytes
     */
    public byte @Nullable [] seal(@NotNull ChannelFrame frame) {
        if (this.sendCounter >= MAXIMUM_COUNTER) {
            return null;
        }

        byte[] plain = frame.write();

        if (plain.length + OVERHEAD > ChannelDatagramUtils.MAXIMUM_DATAGRAM_LENGTH) {
            throw Exceptions.newIllegalArgumentException("A frame of {0} bytes does not fit a channel datagram of {1} bytes.",
                                                         plain.length, ChannelDatagramUtils.MAXIMUM_DATAGRAM_LENGTH);
        }

        long counter = this.sendCounter++;
        WireWriter header = new WireWriter(AUTHENTICATED_LENGTH);

        ChannelDatagramUtils.writeHeader(header, this.remoteReceiveNumber);
        header.writeInt64(counter);

        byte[] datagram = new byte[plain.length + OVERHEAD];

        System.arraycopy(header.toByteArray(), 0, datagram, 0, AUTHENTICATED_LENGTH);

        try {
            this.sealer.init(Cipher.ENCRYPT_MODE, this.sendKey, createNonce(counter));
            this.sealer.updateAAD(datagram, 0, AUTHENTICATED_LENGTH);
            this.sealer.doFinal(plain, 0, plain.length, datagram, AUTHENTICATED_LENGTH);
        } catch (GeneralSecurityException exception) {
            throw Exceptions.newIllegalStateException(exception, "The {0} cipher cannot seal a channel datagram.", ALGORITHM);
        }

        return datagram;
    }

    /**
     * Opens a datagram of the other side. Never throws for malformed input.
     *
     * @param data   the bytes
     * @param offset the first byte of the datagram, whose header {@link ChannelDatagramUtils#isChannelDatagram} checked
     * @param length the datagram length
     * @return the frame, or {@code null} if the datagram is too short, repeated, too old, does not authenticate under the receive key or holds no valid frame
     */
    public @Nullable ChannelFrame open(byte @NotNull [] data, int offset, int length) {
        if (length < OVERHEAD + 1 || length > ChannelDatagramUtils.MAXIMUM_DATAGRAM_LENGTH || offset + length > data.length) {
            return null;
        }

        long counter = new WireReader(data, offset + ChannelDatagramUtils.HEADER_LENGTH, Long.BYTES).readInt64();

        if (!this.window.isFresh(counter)) {
            return null;
        }

        byte[] plain;

        try {
            this.opener.init(Cipher.DECRYPT_MODE, this.receiveKey, createNonce(counter));
            this.opener.updateAAD(data, offset, AUTHENTICATED_LENGTH);
            plain = this.opener.doFinal(data, offset + AUTHENTICATED_LENGTH, length - AUTHENTICATED_LENGTH);
        } catch (GeneralSecurityException exception) {
            return null;
        }

        ChannelFrame frame = ChannelFrame.read(plain, 0, plain.length);

        if (frame == null) {
            return null;
        }

        this.lastOpenedNewest = this.window.accept(counter);

        return frame;
    }
}
