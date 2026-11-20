package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.beaconlink.channel.ChannelOffer;
import org.fogofwarlabs.sc4j.beaconlink.channel.EphemeralKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.ModFingerprint;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.NatType;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests joining a Beacon.Link room through emulated NATs on 127.0.0.1: the host's check of the engine version and the mod identity with
 * the refusal reasons, a private room's password, a room the host does not hold, the accepted joiner's channel at the host (at the host's
 * connection that holds the room, while another connection of the host's account is online), a join signed before the standing join's,
 * which leaves its channel, and the two-person room whose pair cannot connect.
 */
final class RoomJoinTest {
    private static final @NotNull ModIdentity IDENTITY = ClientScenario.createIdentity("1.0.0");
    private static final @NotNull RoomSettings SETTINGS = new RoomSettings("Evening game", "Ashen Ford", 1, 4);
    private static final @NotNull String OTHER_ENGINE = "0.2";
    private static final long CHANNEL_STEP_MILLIS = 100L;
    private static final long CONNECT_MILLIS = 20_000L;
    private static final long MISSING_ROOM_NUMBER = 7L;
    /**
     * The participants whose join offers never connect: more than the channels a session holds.
     */
    private static final int STRANGERS = 70;
    private static final int RELAXED_LIMIT = 65_536;
    private static final int SETTLE_POLLS = 20;
    /**
     * How long before the scenario's clock the standing join and the late copy of an older join are signed.
     */
    private static final long STANDING_JOIN_AGE_MILLIS = 1_000L;
    private static final long LATE_JOIN_AGE_MILLIS = 2_000L;

    /**
     * Creates a raw participant's offer to join a room, with a fresh ephemeral key and no address candidates.
     */
    private static byte @NotNull [] createJoinOffer(@NotNull FoundRoom target, @NotNull ModFingerprint fingerprint, long receiveNumber,
                                                    @NotNull SecureRandom random) {
        return new ChannelOffer(RawClientScenario.CONNECTION, receiveNumber, EphemeralKeyPair.generate(random).getPublicKey(), target.getNumber(),
                                fingerprint, List.of()).write();
    }


    private final @NotNull ClientScenario scenario;

    RoomJoinTest() {
        this.scenario = new ClientScenario();
    }

    @AfterEach
    void closeScenario() {
        this.scenario.close();
    }

    private @NotNull BeaconLinkClient openClient(@NotNull PeerSocket node, @NotNull NatType type, @NotNull ModIdentity identity) throws IOException {
        BeaconLinkClient client = this.scenario.openClient(identity, List.of(ClientScenario.listed(node)),
                                                           this.scenario.openNat(type).openEndpoint());

        this.scenario.await(() -> client.getObservedAddress() != null, "the client registers");

        return client;
    }

    private @NotNull HostedRoom publish(@NotNull BeaconLinkClient host) throws IOException {
        HostedRoom room = host.publishRoom(SETTINGS);

        this.scenario.await(() -> room.getState() == HostedRoomState.PUBLISHED, "the room is published");

        return room;
    }

    /**
     * Finds the room a client hosts with a search of its own, which lists the rooms of its mod identity.
     */
    private @NotNull FoundRoom findOwnRoom(@NotNull BeaconLinkClient host) throws IOException {
        RoomSearch search = host.searchRooms();

        this.scenario.await(search::isComplete, "the search completes");

        return search.getRooms().getFirst();
    }

    /**
     * Waits until the host's room hands out the channel of a joined participant.
     */
    private @NotNull ParticipantChannel awaitAccepted(@NotNull HostedRoom room) throws IOException {
        ParticipantChannel[] accepted = new ParticipantChannel[1];

        this.scenario.advanceUntil(() -> (accepted[0] = room.acceptChannel()) != null, CONNECT_MILLIS, CHANNEL_STEP_MILLIS,
                                   "the host accepts the joiner");

        return accepted[0];
    }

    private void awaitSettled(@NotNull ParticipantChannel channel) throws IOException {
        this.scenario.advanceUntil(() -> channel.getState() != ChannelState.CONNECTING, CONNECT_MILLIS, CHANNEL_STEP_MILLIS, "the join settles");
    }

    @Test
    void join_reaches_the_connection_that_holds_the_room_while_another_connection_of_the_host_is_online() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient host = this.openClient(node, NatType.ADDRESS_RESTRICTED, IDENTITY);
        BeaconLinkClient hostElsewhere = this.scenario.openConnection(host, IDENTITY, List.of(ClientScenario.listed(node)));
        BeaconLinkClient joiner = this.openClient(node, NatType.PORT_RESTRICTED, IDENTITY);

        this.scenario.await(() -> hostElsewhere.getObservedAddress() != null, "the host's other connection registers");

        HostedRoom room = this.publish(host);
        HostedRoom otherRoom = this.publish(hostElsewhere);
        RoomSearch search = joiner.searchRooms();

        this.scenario.await(() -> search.isComplete() && search.getRooms().size() == 2, "the search finds both rooms of the account");

        FoundRoom found = search.getRooms().stream().filter(candidate -> candidate.getHost().equals(host.getParticipant())).findFirst()
                                .orElseThrow();

        Assertions.assertEquals(hostElsewhere.getParticipant().getPlayer(), found.getHost().getPlayer(), "both rooms are of one account");

        ParticipantChannel channel = joiner.joinRoom(found);

        this.awaitSettled(channel);

        Assertions.assertEquals(ChannelState.OPEN, channel.getState());
        Assertions.assertEquals(host.getParticipant(), channel.getPeer());
        Assertions.assertEquals(joiner.getParticipant(), this.awaitAccepted(room).getPeer());
        Assertions.assertNull(otherRoom.acceptChannel(), "the account's other connection gets no join");
    }

    @Test
    void joiner_with_the_same_identity_is_accepted_and_the_host_receives_its_channel() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient host = this.openClient(node, NatType.ADDRESS_RESTRICTED, IDENTITY);
        BeaconLinkClient joiner = this.openClient(node, NatType.SYMMETRIC, IDENTITY);
        HostedRoom room = this.publish(host);
        RoomSearch search = joiner.searchRooms();

        this.scenario.await(search::isComplete, "the search completes");

        FoundRoom found = search.getRooms().getFirst();
        ParticipantChannel channel = joiner.joinRoom(found);

        Assertions.assertNull(room.acceptChannel());
        Assertions.assertThrows(IllegalStateException.class, () -> joiner.joinRoom(found));

        this.awaitSettled(channel);

        Assertions.assertEquals(ChannelState.OPEN, channel.getState());

        ParticipantChannel accepted = this.awaitAccepted(room);

        Assertions.assertEquals(joiner.getParticipant(), accepted.getPeer());
        Assertions.assertNull(room.acceptChannel());
        Assertions.assertTrue(channel.send(ByteBuffer.wrap(new byte[]{1, 2, 3})));

        this.scenario.await(() -> accepted.receive(ByteBuffer.allocate(ParticipantChannel.MAXIMUM_PAYLOAD_LENGTH)) == 3, "the host receives");
    }

    @Test
    void private_room_lists_its_host_mod_and_takes_only_a_joiner_with_its_password() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient host = this.openClient(node, NatType.FULL_CONE, IDENTITY);
        BeaconLinkClient guesser = this.openClient(node, NatType.FULL_CONE, IDENTITY);
        BeaconLinkClient friend = this.openClient(node, NatType.FULL_CONE, IDENTITY);
        HostedRoom room = host.publishRoom(SETTINGS, "open sesame");

        this.scenario.await(() -> room.getState() == HostedRoomState.PUBLISHED, "the room is published");

        RoomSearch search = guesser.searchRooms();

        this.scenario.await(search::isComplete, "the search completes");

        FoundRoom found = search.getRooms().getFirst();

        Assertions.assertTrue(found.isPrivate());
        Assertions.assertTrue(found.isJoinable());
        Assertions.assertEquals(IDENTITY.getModId(), found.getModId());
        Assertions.assertEquals(IDENTITY.getModVersion().toString(), found.getModVersion());

        ParticipantChannel guessed = guesser.joinRoom(found, "open barley");
        ParticipantChannel withoutPassword = friend.joinRoom(found);

        this.awaitSettled(guessed);
        this.awaitSettled(withoutPassword);

        Assertions.assertEquals(ChannelFailure.JOIN_REFUSED, guessed.getFailure());
        Assertions.assertEquals(JoinRefusal.WRONG_PASSWORD, guessed.getJoinRefusal());
        Assertions.assertEquals(JoinRefusal.WRONG_PASSWORD, withoutPassword.getJoinRefusal());
        Assertions.assertNull(room.acceptChannel());

        BeaconLinkClient joiner = this.openClient(node, NatType.ADDRESS_RESTRICTED, IDENTITY);
        ParticipantChannel channel = joiner.joinRoom(found, "open sesame");

        this.awaitSettled(channel);

        Assertions.assertEquals(ChannelState.OPEN, channel.getState(), () -> "the joiner is refused: " + channel.getJoinRefusal());
        Assertions.assertEquals(joiner.getParticipant(), this.awaitAccepted(room).getPeer());

        room.update(new RoomSettings(SETTINGS.getRoomName(), SETTINGS.getMapName(), 2, SETTINGS.getCapacity(), true, new byte[0]));

        RoomSearch started = guesser.searchRooms();

        this.scenario.await(() -> started.isComplete() && started.getRooms().getFirst().getSettings().isStarted(), "the started room is listed");

        Assertions.assertTrue(started.getRooms().getFirst().isPrivate());
        Assertions.assertFalse(started.getRooms().getFirst().isJoinable());
    }

    @Test
    void host_refuses_another_engine_version_and_another_mod_identity_with_the_reason() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient host = this.openClient(node, NatType.FULL_CONE, IDENTITY);
        HostedRoom room = this.publish(host);
        FoundRoom target = this.findOwnRoom(host);
        BeaconLinkClient otherEngine = this.openClient(node, NatType.FULL_CONE,
                                                       ClientScenario.createIdentity(EngineVersion.parse(OTHER_ENGINE), "1.0.0"));
        BeaconLinkClient otherMod = this.openClient(node, NatType.FULL_CONE, ClientScenario.createIdentity("1.0.1"));
        ParticipantChannel engineChannel = otherEngine.joinRoom(target);
        ParticipantChannel modChannel = otherMod.joinRoom(target);

        this.awaitSettled(engineChannel);
        this.awaitSettled(modChannel);

        Assertions.assertEquals(ChannelFailure.JOIN_REFUSED, engineChannel.getFailure());
        Assertions.assertEquals(JoinRefusal.ENGINE_VERSION, engineChannel.getJoinRefusal());
        Assertions.assertEquals(ChannelFailure.JOIN_REFUSED, modChannel.getFailure());
        Assertions.assertEquals(JoinRefusal.MOD_VERSION, modChannel.getJoinRefusal());
        Assertions.assertNull(room.acceptChannel());
    }

    @Test
    void join_of_a_room_the_host_does_not_hold_fails_as_not_found() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient host = this.openClient(node, NatType.FULL_CONE, IDENTITY);
        BeaconLinkClient joiner = this.openClient(node, NatType.FULL_CONE, IDENTITY);
        this.publish(host);

        long published = this.findOwnRoom(host).getNumber();
        long number = published == MISSING_ROOM_NUMBER ? MISSING_ROOM_NUMBER + 1L : MISSING_ROOM_NUMBER;
        ParticipantChannel channel = joiner.joinRoom(new FoundRoom(host.getParticipant(), number, SETTINGS));

        this.awaitSettled(channel);

        Assertions.assertEquals(ChannelFailure.ROOM_NOT_FOUND, channel.getFailure());
        Assertions.assertNull(channel.getJoinRefusal());
    }

    @Test
    void join_offers_that_never_connect_do_not_keep_a_joiner_out() throws IOException {
        PeerConfiguration relaxed = ClientScenario.createNodeBuilder().setAddressBurst(RELAXED_LIMIT).setAddressRate(RELAXED_LIMIT).build();
        PeerSocket node = this.scenario.openNode(relaxed);
        BeaconLinkClient host = this.openClient(node, NatType.FULL_CONE, IDENTITY);
        HostedRoom room = this.publish(host);
        FoundRoom target = this.findOwnRoom(host);
        ModFingerprint fingerprint = BeaconLinkClient.createFingerprint(IDENTITY);
        SecureRandom random = new SecureRandom();
        List<RawClientScenario> strangers = new ArrayList<>();

        try {
            // Every stranger offers to join with the room's public number and identity, then never answers the host's probes.
            for (int count = 0; count < STRANGERS; count++) {
                RawClientScenario stranger = new RawClientScenario(this.scenario);
                ChannelOffer offer = new ChannelOffer(RawClientScenario.CONNECTION, count + 1L, EphemeralKeyPair.generate(random).getPublicKey(),
                                                      target.getNumber(), fingerprint, List.of());

                strangers.add(stranger);
                stranger.register(node, fingerprint);
                stranger.sendSignal(node, target.getHost(), offer.write());
            }

            for (int poll = 0; poll < SETTLE_POLLS; poll++) {
                this.scenario.pollAll();
            }

            BeaconLinkClient joiner = this.openClient(node, NatType.FULL_CONE, IDENTITY);
            ParticipantChannel channel = joiner.joinRoom(target);

            this.awaitSettled(channel);

            Assertions.assertEquals(ChannelState.OPEN, channel.getState(), () -> "the joiner is refused: " + channel.getJoinRefusal());
            Assertions.assertEquals(joiner.getParticipant(), this.awaitAccepted(room).getPeer());
        } finally {
            for (RawClientScenario stranger : strangers) {
                stranger.close();
            }
        }
    }

    @Test
    void join_signed_before_the_standing_join_does_not_replace_its_channel() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient host = this.openClient(node, NatType.FULL_CONE, IDENTITY);
        this.publish(host);

        FoundRoom target = this.findOwnRoom(host);
        ModFingerprint fingerprint = BeaconLinkClient.createFingerprint(IDENTITY);
        SecureRandom random = new SecureRandom();

        try (RawClientScenario joiner = new RawClientScenario(this.scenario)) {
            joiner.register(node, fingerprint);
            // The joiner never answers the host's probes, so that each accepted join's channel keeps connecting.
            joiner.sendSignal(node, target.getHost(), createJoinOffer(target, fingerprint, 1L, random), STANDING_JOIN_AGE_MILLIS);

            for (int poll = 0; poll < SETTLE_POLLS; poll++) {
                this.scenario.pollAll();
            }

            ParticipantChannel standing = host.openChannel(joiner.getParticipant());
            int dropped = host.getDroppedCount();

            Assertions.assertEquals(ChannelState.CONNECTING, standing.getState());

            joiner.sendSignal(node, target.getHost(), createJoinOffer(target, fingerprint, 2L, random), LATE_JOIN_AGE_MILLIS);
            this.scenario.await(() -> host.getDroppedCount() > dropped, "the host drops the late copy of an older join");

            Assertions.assertEquals(ChannelState.CONNECTING, standing.getState());
            Assertions.assertSame(standing, host.openChannel(joiner.getParticipant()));

            joiner.sendSignal(node, target.getHost(), createJoinOffer(target, fingerprint, 3L, random));
            this.scenario.await(() -> standing.getState() == ChannelState.CLOSED, "a newer join replaces the channel");

            Assertions.assertEquals(ChannelFailure.CLOSED_BY_PEER, standing.getFailure());
            Assertions.assertNotSame(standing, host.openChannel(joiner.getParticipant()));
        }
    }

    @Test
    void two_person_room_whose_pair_cannot_connect_fails_with_the_reason() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient host = this.openClient(node, NatType.SYMMETRIC, IDENTITY);
        BeaconLinkClient joiner = this.openClient(node, NatType.SYMMETRIC, IDENTITY);
        HostedRoom room = this.publish(host);
        ParticipantChannel channel = joiner.joinRoom(this.findOwnRoom(host));

        this.awaitSettled(channel);

        Assertions.assertEquals(ChannelState.CLOSED, channel.getState());
        Assertions.assertEquals(ChannelFailure.PAIR_CANNOT_CONNECT, channel.getFailure());
        Assertions.assertNull(room.acceptChannel());
    }
}
