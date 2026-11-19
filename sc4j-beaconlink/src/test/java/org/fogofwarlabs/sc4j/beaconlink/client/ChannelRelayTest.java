package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.LoopbackNat;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.NatType;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.ChannelFailure;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.ChannelState;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.ParticipantChannel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Tests the relay through a participant for pairs of emulated NATs on 127.0.0.1 that cannot connect directly: the channel goes through a
 * participant with direct channels to both, the relay with the lowest measured delay is chosen, the relay passes datagrams it cannot open,
 * and a channel whose relay leaves moves to another relay or fails with the reason.
 */
final class ChannelRelayTest {
    private static final @NotNull ModIdentity IDENTITY = ClientScenario.createIdentity("1.0.0");
    private static final long CHANNEL_STEP_MILLIS = 100L;
    private static final long CONNECT_MILLIS = 20_000L;
    private static final long RELAY_DELAY_MILLIS = 400L;

    private static @NotNull String receiveText(@NotNull ParticipantChannel channel) {
        ByteBuffer buffer = ByteBuffer.allocate(ParticipantChannel.MAXIMUM_PAYLOAD_LENGTH);
        int length = channel.receive(buffer);

        return length == ParticipantChannel.NO_DATAGRAM ? "" : new String(buffer.array(), 0, length, StandardCharsets.UTF_8);
    }

    private static void sendText(@NotNull ParticipantChannel channel, @NotNull String text) {
        Assertions.assertTrue(channel.send(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))));
    }

    private final @NotNull ClientScenario scenario;

    private @Nullable PeerSocket node;

    ChannelRelayTest() {
        this.scenario = new ClientScenario();

        this.node = null;
    }

    @AfterEach
    void closeScenario() {
        this.scenario.close();
    }

    private @NotNull BeaconLinkClient openClient(@NotNull LoopbackNat nat) throws IOException {
        PeerSocket shared = this.node;

        if (shared == null) {
            shared    = this.scenario.openNode(ClientScenario.createNodeConfiguration());
            this.node = shared;
        }

        BeaconLinkClient client = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(shared)), nat.openEndpoint());

        this.scenario.await(() -> client.getObservedAddress() != null, "the client registers");

        return client;
    }

    /**
     * Opens the channel of two clients in both directions and waits until both sides settle: index 0 is the first client's channel.
     */
    private @NotNull ParticipantChannel @NotNull [] connect(@NotNull BeaconLinkClient first, @NotNull BeaconLinkClient second) throws IOException {
        ParticipantChannel firstChannel = first.openChannel(second.getParticipant());
        ParticipantChannel secondChannel = second.openChannel(first.getParticipant());

        this.scenario.advanceUntil(() -> firstChannel.getState() != ChannelState.CONNECTING && secondChannel.getState() != ChannelState.CONNECTING,
                                   CONNECT_MILLIS, CHANNEL_STEP_MILLIS, "both channels settle");

        return new ParticipantChannel[]{firstChannel, secondChannel};
    }

    private void assertOpen(@NotNull ParticipantChannel @NotNull [] channels) {
        Assertions.assertEquals(ChannelState.OPEN, channels[0].getState(), "first side");
        Assertions.assertEquals(ChannelState.OPEN, channels[1].getState(), "second side");
    }

    /**
     * Sends a datagram each way; the clock moves meanwhile, so that datagrams through a delaying NAT arrive.
     */
    private void assertCarries(@NotNull ParticipantChannel @NotNull [] channels, @NotNull String text) throws IOException {
        sendText(channels[0], text);
        this.scenario.advanceUntil(() -> receiveText(channels[1]).equals(text), CONNECT_MILLIS, CHANNEL_STEP_MILLIS, "the datagram arrives");
        sendText(channels[1], text + " back");
        this.scenario.advanceUntil(() -> receiveText(channels[0]).equals(text + " back"), CONNECT_MILLIS, CHANNEL_STEP_MILLIS,
                                   "the answer arrives");
    }

    @ParameterizedTest
    @CsvSource({"SYMMETRIC, SYMMETRIC", "SYMMETRIC, PORT_RESTRICTED"})
    void pair_without_a_direct_path_connects_through_a_participant_connected_to_both(@NotNull NatType firstType, @NotNull NatType secondType) throws IOException {
        BeaconLinkClient first = this.openClient(this.scenario.openNat(firstType));
        BeaconLinkClient second = this.openClient(this.scenario.openNat(secondType));
        BeaconLinkClient relay = this.openClient(this.scenario.openNat(NatType.FULL_CONE));
        ParticipantChannel[] direct = this.connect(first, relay);

        this.assertOpen(direct);
        this.assertOpen(this.connect(second, relay));

        ParticipantChannel[] channels = this.connect(first, second);

        this.assertOpen(channels);
        this.assertCarries(channels, "through the relay");

        Assertions.assertTrue(relay.getRelayedCount() > 0);
        Assertions.assertTrue(channels[0].isRelayed() && channels[1].isRelayed());
        Assertions.assertFalse(direct[0].isRelayed());
        Assertions.assertNotEquals(ParticipantChannel.UNKNOWN_DELAY, channels[0].getDelayMillis());
    }

    @Test
    void relay_with_the_lowest_delay_is_chosen() throws IOException {
        BeaconLinkClient first = this.openClient(this.scenario.openNat(NatType.SYMMETRIC));
        BeaconLinkClient second = this.openClient(this.scenario.openNat(NatType.SYMMETRIC));
        LoopbackNat slowNat = this.scenario.openNat(NatType.FULL_CONE);
        BeaconLinkClient slow = this.openClient(slowNat);
        BeaconLinkClient fast = this.openClient(this.scenario.openNat(NatType.FULL_CONE));

        slowNat.setDelayMillis(RELAY_DELAY_MILLIS);

        for (BeaconLinkClient relay : List.of(slow, fast)) {
            this.assertOpen(this.connect(first, relay));
            this.assertOpen(this.connect(second, relay));
        }

        ParticipantChannel[] channels = this.connect(first, second);

        this.assertOpen(channels);
        this.assertCarries(channels, "the fast way");

        Assertions.assertTrue(fast.getRelayedCount() > 0);
        Assertions.assertEquals(0, slow.getRelayedCount());
    }

    @Test
    void channel_moves_to_another_relay_when_its_relay_leaves() throws IOException {
        BeaconLinkClient first = this.openClient(this.scenario.openNat(NatType.SYMMETRIC));
        BeaconLinkClient second = this.openClient(this.scenario.openNat(NatType.SYMMETRIC));
        LoopbackNat slowNat = this.scenario.openNat(NatType.FULL_CONE);
        BeaconLinkClient spare = this.openClient(slowNat);
        BeaconLinkClient leaving = this.openClient(this.scenario.openNat(NatType.FULL_CONE));

        slowNat.setDelayMillis(RELAY_DELAY_MILLIS);

        for (BeaconLinkClient relay : List.of(spare, leaving)) {
            this.assertOpen(this.connect(first, relay));
            this.assertOpen(this.connect(second, relay));
        }

        ParticipantChannel[] channels = this.connect(first, second);

        this.assertCarries(channels, "before");
        Assertions.assertTrue(leaving.getRelayedCount() > 0);
        Assertions.assertEquals(0, spare.getRelayedCount());

        leaving.close();

        this.scenario.advanceUntil(() -> spare.getRelayedCount() > 0, CONNECT_MILLIS, CHANNEL_STEP_MILLIS, "the spare relay takes over");

        this.assertOpen(channels);
        this.assertCarries(channels, "after");
    }

    @Test
    void channel_fails_with_the_reason_when_its_only_relay_leaves() throws IOException {
        BeaconLinkClient first = this.openClient(this.scenario.openNat(NatType.SYMMETRIC));
        BeaconLinkClient second = this.openClient(this.scenario.openNat(NatType.SYMMETRIC));
        BeaconLinkClient relay = this.openClient(this.scenario.openNat(NatType.FULL_CONE));

        this.assertOpen(this.connect(first, relay));
        this.assertOpen(this.connect(second, relay));

        ParticipantChannel[] channels = this.connect(first, second);

        this.assertCarries(channels, "while the relay stays");

        relay.close();

        this.scenario.advanceUntil(() -> channels[0].getState() == ChannelState.CLOSED && channels[1].getState() == ChannelState.CLOSED,
                                   CONNECT_MILLIS, CHANNEL_STEP_MILLIS, "both sides fail");

        Assertions.assertEquals(ChannelFailure.PAIR_CANNOT_CONNECT, channels[0].getFailure());
        Assertions.assertEquals(ChannelFailure.PAIR_CANNOT_CONNECT, channels[1].getFailure());
    }
}
