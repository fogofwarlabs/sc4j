package org.fogofwarlabs.sc4j.beaconlink.protocol.message;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;

/**
 * Carries one step of a peer's reachability self-test. A peer that wants to know whether strangers reach it asks a known peer (the helper)
 * with a request without an address; the helper forwards the request with the requester's observed address to a third known peer, which
 * sends a {@link ProbeMessage} with the same nonce to that address. The requester never sent anything to the third peer, so the probe
 * passes the requester's NAT only if the NAT lets strangers through.
 * <p>
 * The helper answers the requester with a reply: with the requester's observed address when a probe is on its way, without an address when
 * it knows no third peer.
 * <p>
 * Every step carries the sender's {@link PeerCard}: a helper serves only a requester with a valid proof, so that the probes it causes cost
 * the requester a peer ID.
 * <p>
 * Body: the sender's card, the nonce (8), the address flag (1: {@code 0} none, {@code 1} an address follows) and the address.
 */
public final class DialBackMessage extends Message {
    private static final int NO_ADDRESS = 0;
    private static final int WITH_ADDRESS = 1;

    /**
     * Reads the body.
     *
     * @param reader the body reader
     * @return the body, or {@code null} if the reader failed
     */
    public static @Nullable DialBackMessage read(@NotNull WireReader reader) {
        PeerCard card = PeerCard.read(reader);
        long nonce = reader.readInt64();
        int flag = reader.readUnsigned8();
        InetSocketAddress address = null;

        if (flag == WITH_ADDRESS) {
            address = reader.readAddress();

            if (address == null) {
                return null;
            }
        } else if (flag != NO_ADDRESS) {
            reader.fail();
        }

        return card == null || reader.isFailed() ? null : new DialBackMessage(card, nonce, address);
    }


    private final @NotNull PeerCard card;
    private final long nonce;
    private final @Nullable InetSocketAddress address;

    /**
     * Creates a {@link DialBackMessage} instance.
     *
     * @param card    the sender's card
     * @param nonce   the requester's nonce, repeated by the probe
     * @param address the address to probe (a forwarded request), the requester's observed address (an accepting reply), or {@code null}
     * @throws IllegalArgumentException if the address is not resolved
     */
    public DialBackMessage(@NotNull PeerCard card, long nonce, @Nullable InetSocketAddress address) {
        if (address != null && address.isUnresolved()) {
            throw Exceptions.newIllegalArgumentException("The dial-back address {0} is not resolved.", address);
        }

        this.card    = card;
        this.nonce   = nonce;
        this.address = address;
    }

    /**
     * @return {@link MessageKind#DIAL_BACK}
     */
    @Override
    public @NotNull MessageKind getKind() {
        return MessageKind.DIAL_BACK;
    }

    /**
     * @return the sender's card
     */
    public @NotNull PeerCard getCard() {
        return this.card;
    }

    /**
     * @return the requester's nonce, repeated by the probe
     */
    public long getNonce() {
        return this.nonce;
    }

    /**
     * @return the address to probe or the requester's observed address, or {@code null} in the requester's request and in a refusing reply
     */
    public @Nullable InetSocketAddress getAddress() {
        return this.address;
    }


    /**
     * Writes the body fields.
     *
     * @param writer the writer
     */
    @Override
    public void writeBody(@NotNull WireWriter writer) {
        this.card.write(writer);
        writer.writeInt64(this.nonce);

        if (this.address == null) {
            writer.writeUnsigned8(NO_ADDRESS);
        } else {
            writer.writeUnsigned8(WITH_ADDRESS);
            writer.writeAddress(this.address);
        }
    }
}
