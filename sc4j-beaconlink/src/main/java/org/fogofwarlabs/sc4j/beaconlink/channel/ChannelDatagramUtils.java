package org.fogofwarlabs.sc4j.beaconlink.channel;

import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Writes and reads the header of channel datagrams. A channel datagram shares the participant's one UDP socket with the node messages; its
 * own magic {@code BLCH} tells it from a node envelope ({@code BLNK}), and the receive number the receiver chose for the channel tells the
 * channels apart. {@link ChannelCipher} seals the rest.
 * <p>
 * Header (big-endian): the magic and the receiver's receive number (eight bytes). A datagram is at most {@value #MAXIMUM_DATAGRAM_LENGTH}
 * bytes.
 */
public final class ChannelDatagramUtils {
    /**
     * The largest channel datagram: below the IPv6 minimum MTU with its headers, like a node datagram.
     */
    public static final int MAXIMUM_DATAGRAM_LENGTH = 1200;
    /**
     * The length of the header.
     */
    public static final int HEADER_LENGTH = 4 + Long.BYTES;

    private static final byte @NotNull [] MAGIC = {'B', 'L', 'C', 'H'};

    /**
     * Checks whether received bytes start like a channel datagram.
     *
     * @param data   the bytes
     * @param offset the first byte
     * @param length the datagram length
     * @return {@code true} if the bytes have the channel magic, a whole header and at most {@value #MAXIMUM_DATAGRAM_LENGTH} bytes, {@code false} otherwise
     */
    public static boolean isChannelDatagram(byte @NotNull [] data, int offset, int length) {
        return length >= HEADER_LENGTH && length <= MAXIMUM_DATAGRAM_LENGTH && offset + length <= data.length &&
               Arrays.equals(data, offset, offset + MAGIC.length, MAGIC, 0, MAGIC.length);
    }

    /**
     * Reads the receiver's receive number of a channel datagram.
     *
     * @param data   the bytes, checked by {@link #isChannelDatagram}
     * @param offset the first byte of the datagram
     * @return the receive number
     */
    public static long readReceiveNumber(byte @NotNull [] data, int offset) {
        return new WireReader(data, offset + MAGIC.length, Long.BYTES).readInt64();
    }

    /**
     * Writes the header of a channel datagram.
     *
     * @param writer        the writer
     * @param receiveNumber the receiver's receive number
     */
    public static void writeHeader(@NotNull WireWriter writer, long receiveNumber) {
        writer.writeBytes(MAGIC);
        writer.writeInt64(receiveNumber);
    }

    private ChannelDatagramUtils() {
    }
}
