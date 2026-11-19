package org.fogofwarlabs.sc4j.beaconlink.peer;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.AddressTokens;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.ChatLineRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.SignOutRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateReport;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PeerId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.ModFingerprint;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomAdvertisement;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomDescription;
import org.fogofwarlabs.sc4j.beaconlink.protocol.test.ManualProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKind;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Tests one node on 127.0.0.1: registration with the address token and the observed address (every connection of a key up to the limit per key, a NAT
 * move of one connection), pings, publishing, listing (by mod topic, every fingerprint of the topic) and withdrawing rooms with their expiry, the
 * address tokens of the topic requests and the records taken only from mesh neighbours, signal relay to one connection, the rejection of unsigned,
 * forged, replayed, stale and oversize datagrams, the rate limits and the bounded stores; driven by hand: copies of a datagram spend no key rate, a
 * guessed answer for a seed is refused, no answer to an unproved source is longer than its request, and changed signed datagrams of every kind never
 * fail a handler.
 */
final class MeshPeerTest {
    private static final @NotNull Logger LOGGER = Logger.of(MeshPeerTest.class);
    private static final @NotNull ModFingerprint FINGERPRINT = ModFingerprint.of(new byte[]{1, 1, 2, 3, 5, 8});
    private static final @NotNull ModFingerprint OTHER_FINGERPRINT = ModFingerprint.of(new byte[]{2, 7, 1, 8});
    private static final @NotNull TopicKey TOPIC = TopicKey.of(TopicKind.MOD, "fibonacci");
    private static final @NotNull TopicKey OTHER_TOPIC = TopicKey.of(TopicKind.MOD, "euler");
    private static final @NotNull TopicKey CHANNEL = TopicKey.ofChannel("General");
    private static final int CAPACITY = 4;
    private static final long ROOM_LIFETIME_MILLIS = 60_000L;
    private static final long SHORT_LIFETIME_MILLIS = 10_000L;
    private static final int REQUESTED_SECONDS = 1000;
    private static final long FIRST_CONNECTION = 1L;
    private static final long SECOND_CONNECTION = 2L;
    private static final long THIRD_CONNECTION = 3L;
    private static final long SIGN_OUT_DELAY_MILLIS = 1_000L;
    private static final long CLOCK_BEHIND_MILLIS = 60_000L;
    private static final int OVERSIZE_LENGTH = 1300;
    private static final int PROOF_DIFFICULTY = 8;
    private static final long ANNOUNCE_LIFETIME_SECONDS = 600L;
    private static final int COPIES = 10;
    private static final int GUESSES = 32;
    private static final int ROUNDS = 1;
    private static final long REQUEST_NUMBER_MASK = 0xffffffffL;
    private static final long FUZZ_SEED = 20261110L;
    private static final int FUZZ_ROUNDS = 1000;
    private static final int FUZZ_BURST = 1_000_000;
    private static final int FUZZ_SOURCES = 64;
    private static final int LEAF_ROUNDS = 3;
    private static final int MAXIMUM_CHANGES = 3;
    private static final int MAINTENANCE_ROUNDS = 200;
    private static final int BYTE_MASK = 0xff;
    private static final byte @NotNull [] BOUNDARY_BYTES = {0, 1, 0x7f, (byte) 0x80, (byte) 0xff};

    private static @NotNull ResultStatus getStatus(@NotNull Envelope reply) {
        return ((ResultMessage) reply.getBody()).getStatus();
    }

    private static @NotNull RoomListMessage list(@NotNull PeerScenario.Client client, @NotNull PeerSocket node, @NotNull TopicKey topic) throws IOException {
        return (RoomListMessage) client.request(new ListRoomsMessage(topic, 0), node, false).getBody();
    }

    private final @NotNull PeerScenario scenario;

    MeshPeerTest() {
        this.scenario = new PeerScenario();
    }

    @AfterEach
    void closeScenario() {
        this.scenario.close();
    }

    private @NotNull RoomAdvertisement createRoom(@NotNull IdentityKeyPair host, long number, long sequence, long lifetimeMillis,
                                                  @NotNull ModFingerprint fingerprint) {
        RoomDescription description = new RoomDescription(fingerprint, "Room " + number, "Ashen Ford", 1, CAPACITY);
        long expiry = this.scenario.getClock().getEpochMillis() + lifetimeMillis;

        return RoomAdvertisement.signPublication(host, PeerScenario.DEFAULT_CONNECTION, TOPIC, number, sequence, expiry,
                                                 description, List.of(), null);
    }

    @Test
    void register_should_require_the_address_token_then_register_at_the_observed_address() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client client = this.scenario.openClient();
        RegisterMessage first = client.createRegister(new byte[0], REQUESTED_SECONDS, FINGERPRINT, 0L);
        ObservedAddressMessage challenge = (ObservedAddressMessage) client.request(first, node, true).getBody();

        Assertions.assertEquals(ObservedAddressStatus.TOKEN_REQUIRED, challenge.getStatus());
        Assertions.assertEquals(client.getLocalAddress(), challenge.getAddress());
        Assertions.assertEquals(AddressTokens.TOKEN_LENGTH, challenge.getToken().length);

        RegisterMessage wrongToken = client.createRegister(new byte[AddressTokens.TOKEN_LENGTH], REQUESTED_SECONDS, FINGERPRINT, 0L);

        Assertions.assertEquals(ObservedAddressStatus.TOKEN_REQUIRED,
                                ((ObservedAddressMessage) client.request(wrongToken, node, true).getBody()).getStatus());

        RegisterMessage second = client.createRegister(challenge.getToken(), REQUESTED_SECONDS, FINGERPRINT, 0L);
        ObservedAddressMessage registered = (ObservedAddressMessage) client.request(second, node, true).getBody();

        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, registered.getStatus());
        Assertions.assertEquals(client.getLocalAddress(), registered.getAddress());
        Assertions.assertEquals(PeerConfiguration.createDefault().getMaximumRegistrationSeconds(), registered.getLifetimeSeconds());

        RegisterMessage remove = client.createRegister(new byte[0], 0, FINGERPRINT, 0L);

        Assertions.assertEquals(ObservedAddressStatus.UNREGISTERED,
                                ((ObservedAddressMessage) client.request(remove, node, true).getBody()).getStatus());
        Assertions.assertEquals(ResultStatus.NOT_REGISTERED, getStatus(client.request(new ListRoomsMessage(TOPIC, 0), node, false)));
    }

    @Test
    void register_should_hold_every_connection_of_a_key_up_to_the_limit_per_key() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client first = this.scenario.openClient();
        int limit = PeerConfiguration.createDefault().getMaximumConnectionsPerKey();
        List<PeerScenario.Client> connections = new ArrayList<>(List.of(first));

        while (connections.size() <= limit) {
            connections.add(this.scenario.openClient(first.getKeyPair()));
        }

        for (int index = 0; index < limit; index++) {
            ObservedAddressMessage answer = connections.get(index).register(node, FINGERPRINT, FIRST_CONNECTION + index);

            Assertions.assertEquals(ObservedAddressStatus.REGISTERED, answer.getStatus(), "connection " + index);
        }

        for (int index = 0; index < limit; index++) {
            Assertions.assertInstanceOf(RoomListMessage.class, connections.get(index).request(new ListRoomsMessage(TOPIC, 0), node, false).getBody(),
                                        "connection " + index + " stays registered beside the others");
        }

        PeerScenario.Client beyond = connections.get(limit);

        Assertions.assertEquals(ObservedAddressStatus.TOO_MANY_CONNECTIONS, beyond.register(node, FINGERPRINT, FIRST_CONNECTION + limit).getStatus());
        Assertions.assertEquals(ResultStatus.NOT_REGISTERED, getStatus(beyond.request(new ListRoomsMessage(TOPIC, 0), node, false)));

        RegisterMessage remove = first.createRegister(new byte[0], 0, FINGERPRINT, FIRST_CONNECTION);
        ObservedAddressMessage removed = (ObservedAddressMessage) first.request(remove, node, true).getBody();

        Assertions.assertEquals(ObservedAddressStatus.UNREGISTERED, removed.getStatus());
        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, beyond.register(node, FINGERPRINT, FIRST_CONNECTION + limit).getStatus(),
                                "a connection that ended frees its place");
    }

    @Test
    void register_should_move_only_the_connection_that_registers_from_a_new_address() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client first = this.scenario.openClient();
        PeerScenario.Client second = this.scenario.openClient(first.getKeyPair());
        PeerScenario.Client remapped = this.scenario.openClient(first.getKeyPair());

        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, first.register(node, FINGERPRINT, FIRST_CONNECTION).getStatus());
        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, second.register(node, FINGERPRINT, SECOND_CONNECTION).getStatus());
        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, remapped.register(node, FINGERPRINT, FIRST_CONNECTION).getStatus(),
                                "the first connection at a new address (a NAT that changed its port)");
        Assertions.assertEquals(ResultStatus.NOT_REGISTERED, getStatus(first.request(new ListRoomsMessage(TOPIC, 0), node, false)),
                                "the old address of the moved connection");
        Assertions.assertInstanceOf(RoomListMessage.class, remapped.request(new ListRoomsMessage(TOPIC, 0), node, false).getBody());
        Assertions.assertInstanceOf(RoomListMessage.class, second.request(new ListRoomsMessage(TOPIC, 0), node, false).getBody(),
                                    "the other connection of the key stays");
    }

    @Test
    void register_should_refuse_a_name_the_stamp_does_not_bind_to_the_key() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client client = this.scenario.openClient();
        PlayerStamp stamp = client.getStamp();
        RegisterMessage first = client.createRegister(new byte[0], REQUESTED_SECONDS, FINGERPRINT, 0L);
        ObservedAddressMessage challenge = (ObservedAddressMessage) client.request(first, node, true).getBody();

        for (String name : List.of("Kerrigan", "Player", "with#tag", " " + stamp.getName())) {
            RegisterMessage other = new RegisterMessage(challenge.getToken(), REQUESTED_SECONDS, name, stamp.getNonce(), FINGERPRINT, 0L, 0L);

            Assertions.assertEquals(ObservedAddressStatus.INVALID_NAME,
                                    ((ObservedAddressMessage) client.request(other, node, true).getBody()).getStatus(), name);
            Assertions.assertEquals(ResultStatus.NOT_REGISTERED, getStatus(client.request(new ListRoomsMessage(TOPIC, 0), node, false)), name);
        }

        RegisterMessage own = client.createRegister(challenge.getToken(), REQUESTED_SECONDS, FINGERPRINT, 0L);

        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, ((ObservedAddressMessage) client.request(own, node, true).getBody()).getStatus());

        RegisterMessage renamed = new RegisterMessage(new byte[0], REQUESTED_SECONDS, "Kerrigan", stamp.getNonce(), FINGERPRINT, 0L, 0L);

        Assertions.assertEquals(ObservedAddressStatus.INVALID_NAME,
                                ((ObservedAddressMessage) client.request(renamed, node, true).getBody()).getStatus(),
                                "a registered key cannot change its name either");
    }

    @Test
    void ping_should_answer_the_observed_address_and_version_only_when_padded() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client client = this.scenario.openClient();
        Envelope reply = client.request(new PingMessage(), node, true);
        PongMessage pong = (PongMessage) reply.getBody();

        Assertions.assertEquals(client.getLocalAddress(), pong.getAddress());
        Assertions.assertEquals(MeshPeer.SOFTWARE, pong.getSoftware());
        Assertions.assertEquals(node.getPeer().getKey(), reply.getSender());

        client.send(client.getEncoder().encodeRequest(new PingMessage(), client.takeRequestNumber()), node);
        this.scenario.await(() -> node.getPeer().getStatistics().getDroppedCount(DropReason.UNPADDED) == 1L, "the unpadded ping is dropped");
        client.request(new PingMessage(), node, true);

        Assertions.assertTrue(client.drain().isEmpty());
    }

    @Test
    void rooms_are_published_listed_and_withdrawn_and_expire() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client host = this.scenario.openClient();
        PeerScenario.Client player = this.scenario.openClient();
        PeerScenario.Client stranger = this.scenario.openClient();
        RoomAdvertisement room = this.createRoom(host.getKeyPair(), 1L, 1L, ROOM_LIFETIME_MILLIS, FINGERPRINT);

        Assertions.assertEquals(ResultStatus.NOT_REGISTERED, getStatus(host.request(new PublishRoomMessage(room), node, false)));
        host.register(node, FINGERPRINT);
        player.register(node, FINGERPRINT);
        Assertions.assertEquals(ResultStatus.ACCEPTED, getStatus(host.request(new PublishRoomMessage(room), node, false)));
        Assertions.assertEquals(ResultStatus.STALE, getStatus(host.request(new PublishRoomMessage(room), node, false)));
        Assertions.assertEquals(ResultStatus.INVALID, getStatus(player.request(new PublishRoomMessage(room), node, false)));

        RoomAdvertisement otherConnection = RoomAdvertisement.signPublication(host.getKeyPair(), SECOND_CONNECTION, TOPIC, 5L, 1L,
                                                                              room.getExpiryMillis(), room.getDescription(), List.of(), null);

        Assertions.assertEquals(ResultStatus.INVALID, getStatus(host.request(new PublishRoomMessage(otherConnection), node, false)),
                                "a room of another connection of the key than the registered one");
        Assertions.assertEquals(ResultStatus.NOT_REGISTERED, getStatus(stranger.request(new ListRoomsMessage(TOPIC, 0), node, false)));

        RoomListMessage listed = list(player, node, TOPIC);

        Assertions.assertEquals(1, listed.getTotal());
        Assertions.assertEquals(List.of(room), listed.getAdvertisements());
        Assertions.assertTrue(listed.getAdvertisements().getFirst().isSignatureValid());
        Assertions.assertEquals(0, list(player, node, OTHER_TOPIC).getTotal());

        RoomAdvertisement otherVersion = this.createRoom(player.getKeyPair(), 4L, 1L, ROOM_LIFETIME_MILLIS, OTHER_FINGERPRINT);

        Assertions.assertEquals(ResultStatus.ACCEPTED, getStatus(player.request(new PublishRoomMessage(otherVersion), node, false)));
        Assertions.assertEquals(2, list(player, node, TOPIC).getTotal());

        RoomAdvertisement tooLong = this.createRoom(host.getKeyPair(), 2L, 1L, PeerConfiguration.createDefault().getMaximumRoomLifetimeMillis() + 1L,
                                                    FINGERPRINT);

        Assertions.assertEquals(ResultStatus.INVALID, getStatus(host.request(new PublishRoomMessage(tooLong), node, false)));

        RoomAdvertisement withdrawal = RoomAdvertisement.signWithdrawal(host.getKeyPair(), PeerScenario.DEFAULT_CONNECTION, TOPIC, 1L, 2L,
                                                                        room.getExpiryMillis(), room.getDescription(), List.of());

        Assertions.assertEquals(ResultStatus.ACCEPTED, getStatus(host.request(new WithdrawRoomMessage(withdrawal), node, false)));
        Assertions.assertEquals(List.of(otherVersion), list(player, node, TOPIC).getAdvertisements());
        Assertions.assertEquals(ResultStatus.STALE, getStatus(host.request(new PublishRoomMessage(room), node, false)));

        RoomAdvertisement shortRoom = this.createRoom(host.getKeyPair(), 3L, 1L, SHORT_LIFETIME_MILLIS, FINGERPRINT);

        Assertions.assertEquals(ResultStatus.ACCEPTED, getStatus(host.request(new PublishRoomMessage(shortRoom), node, false)));
        Assertions.assertEquals(2, list(player, node, TOPIC).getTotal());
        this.scenario.getClock().advance(SHORT_LIFETIME_MILLIS);
        Assertions.assertEquals(List.of(otherVersion), list(player, node, TOPIC).getAdvertisements());
    }

    @Test
    void topic_requests_need_padding_and_tokens_and_records_come_only_from_mesh_neighbours() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeBuilder().setProofDifficulty(PROOF_DIFFICULTY).build());
        PeerScenario.Client member = this.scenario.openClient();
        PeerCard card = new PeerCard(PeerId.solveProof(member.getKey(), PROOF_DIFFICULTY), true, CoordinateReport.UNKNOWN);
        PeerStatistics statistics = node.getPeer().getStatistics();
        RoomAdvertisement room = this.createRoom(member.getKeyPair(), 1L, 1L, ROOM_LIFETIME_MILLIS, FINGERPRINT);

        Assertions.assertEquals(List.of(), node.getPeer().listRooms(TOPIC), "the node joins the topic");

        member.send(member.getEncoder().encodeRequest(new GetTopicMessage(TOPIC), member.takeRequestNumber()), node);
        this.scenario.await(() -> statistics.getDroppedCount(DropReason.UNPADDED) == 1L, "the unpadded topic request is dropped");

        TopicPeersMessage answer = (TopicPeersMessage) member.request(new GetTopicMessage(TOPIC), node, true).getBody();

        Assertions.assertTrue(answer.isMember());
        Assertions.assertEquals(AddressTokens.TOKEN_LENGTH, answer.getToken().length);
        Assertions.assertEquals(ResultStatus.INVALID, getStatus(member.request(new AnnounceMessage(TOPIC, card, new byte[AddressTokens.TOKEN_LENGTH]),
                                                                               node, false)));

        ResultMessage announced = (ResultMessage) member.request(new AnnounceMessage(TOPIC, card, answer.getToken()), node, false).getBody();

        Assertions.assertEquals(ResultStatus.ACCEPTED, announced.getStatus());
        Assertions.assertEquals(ANNOUNCE_LIFETIME_SECONDS, announced.getValue());
        Assertions.assertEquals(ResultStatus.INVALID, getStatus(member.request(new GraftMessage(OTHER_TOPIC, card, answer.getToken()), node, false)));

        member.send(member.getEncoder().encodeRequest(new GossipMessage(TOPIC, List.of(room)), member.takeRequestNumber()), node);
        this.scenario.await(() -> statistics.getDroppedCount(DropReason.NOT_IN_TOPIC) == 1L, "the records of a stranger are dropped");

        Assertions.assertEquals(List.of(), node.getPeer().listRooms(TOPIC));
        Assertions.assertEquals(ResultStatus.ACCEPTED, getStatus(member.request(new GraftMessage(TOPIC, card, answer.getToken()), node, false)));

        member.send(member.getEncoder().encodeRequest(new GossipMessage(TOPIC, List.of(room)), member.takeRequestNumber()), node);
        this.scenario.await(() -> statistics.getHandledCount(MessageKind.GOSSIP) == 2L, "the records of a mesh neighbour are taken");

        Assertions.assertEquals(List.of(room), node.getPeer().listRooms(TOPIC));
    }

    @Test
    void signal_is_relayed_unchanged_to_the_registered_target_connection() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client sender = this.scenario.openClient();
        PeerScenario.Client target = this.scenario.openClient();
        PeerScenario.Client otherDevice = this.scenario.openClient(target.getKeyPair());
        byte[] payload = {10, 20, 30, 40};
        byte[] otherPayload = {50, 60};

        sender.register(node, FINGERPRINT);
        target.register(node, FINGERPRINT);
        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, otherDevice.register(node, FINGERPRINT, SECOND_CONNECTION).getStatus());

        SignalMessage toOtherDevice = new SignalMessage(target.getKey(), SECOND_CONNECTION, otherPayload);

        Assertions.assertEquals(ResultStatus.DELIVERED, getStatus(sender.request(toOtherDevice, node, false)));
        SignalMessage otherSignal = (SignalMessage) otherDevice.await(envelope -> envelope.getKind() == MessageKind.SIGNAL).getBody();

        Assertions.assertArrayEquals(otherPayload, otherSignal.getPayload());

        long request = sender.takeRequestNumber();
        byte[] signal = sender.getEncoder().encodeRequest(new SignalMessage(target.getKey(), PeerScenario.DEFAULT_CONNECTION, payload), request);

        sender.send(signal, node);

        Envelope relayed = target.await(envelope -> envelope.getKind() == MessageKind.SIGNAL);
        Envelope result = sender.await(envelope -> envelope.isReply() && envelope.getRequestNumber() == request);

        Assertions.assertArrayEquals(signal, relayed.getDatagram(), "the first signal the target's connection gets is its own");
        Assertions.assertTrue(relayed.isSignatureValid());
        Assertions.assertEquals(sender.getKey(), relayed.getSender());
        Assertions.assertArrayEquals(payload, ((SignalMessage) relayed.getBody()).getPayload());
        Assertions.assertEquals(ResultStatus.DELIVERED, getStatus(result));

        IdentityKey unknown = IdentityKeyPair.generate(new SecureRandom()).getPublicKey();

        SignalMessage toUnknownKey = new SignalMessage(unknown, PeerScenario.DEFAULT_CONNECTION, payload);
        SignalMessage toUnknownConnection = new SignalMessage(target.getKey(), FIRST_CONNECTION, payload);

        Assertions.assertEquals(ResultStatus.UNKNOWN_TARGET, getStatus(sender.request(toUnknownKey, node, false)));
        Assertions.assertEquals(ResultStatus.UNKNOWN_TARGET, getStatus(sender.request(toUnknownConnection, node, false)),
                                "a connection of the key that is not registered");
    }

    @Test
    void sealed_is_relayed_unchanged_only_to_the_target_connection_it_names() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client sender = this.scenario.openClient();
        PeerScenario.Client target = this.scenario.openClient();
        PeerScenario.Client otherDevice = this.scenario.openClient(target.getKeyPair());
        byte[] ephemeralKey = new byte[SealedMessage.EPHEMERAL_KEY_LENGTH];

        target.register(node, FINGERPRINT);
        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, otherDevice.register(node, FINGERPRINT, SECOND_CONNECTION).getStatus());

        long request = sender.takeRequestNumber();
        byte[] sealed = sender.getEncoder().encodeRequest(new SealedMessage(target.getKey(), SECOND_CONNECTION, ephemeralKey, new byte[]{7, 8}),
                                                          request);

        sender.send(sealed, node);

        Envelope relayed = otherDevice.await(envelope -> envelope.getKind() == MessageKind.SEALED);
        Envelope result = sender.await(envelope -> envelope.isReply() && envelope.getRequestNumber() == request);

        Assertions.assertArrayEquals(sealed, relayed.getDatagram());
        Assertions.assertEquals(ResultStatus.DELIVERED, getStatus(result));
        Assertions.assertTrue(target.drain().stream().noneMatch(envelope -> envelope.getKind() == MessageKind.SEALED),
                              "the target's other connection gets no copy sealed for this one");

        SealedMessage toUnknownConnection = new SealedMessage(target.getKey(), FIRST_CONNECTION, ephemeralKey, new byte[]{7, 8});

        Assertions.assertEquals(ResultStatus.UNKNOWN_TARGET, getStatus(sender.request(toUnknownConnection, node, false)),
                                "a connection of the key that is not registered");
    }

    @Test
    void register_should_refuse_and_drop_the_connections_a_sign_out_ends() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client kept = this.scenario.openClient();
        PeerScenario.Client older = this.scenario.openClient(kept.getKeyPair());
        PeerScenario.Client later = this.scenario.openClient(kept.getKeyPair());
        long olderStartMillis = this.scenario.getClock().getEpochMillis();

        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, older.register(node, FINGERPRINT, SECOND_CONNECTION).getStatus());
        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, kept.register(node, FINGERPRINT, FIRST_CONNECTION).getStatus());

        this.scenario.getClock().advance(SIGN_OUT_DELAY_MILLIS);

        long nowMillis = this.scenario.getClock().getEpochMillis();
        SignOutRecord signOut = SignOutRecord.sign(kept.getKeyPair(), FIRST_CONNECTION, 1L, nowMillis, nowMillis + SHORT_LIFETIME_MILLIS);

        Assertions.assertEquals(ResultStatus.ACCEPTED, getStatus(kept.request(new PublishRecordMessage(signOut), node, false)));
        Assertions.assertEquals(ResultStatus.NOT_REGISTERED, getStatus(older.request(new ListRoomsMessage(TOPIC, 0), node, false)),
                                "the node drops the registration of the older connection");
        Assertions.assertEquals(ObservedAddressStatus.SIGNED_OUT, older.register(node, FINGERPRINT, SECOND_CONNECTION, olderStartMillis).getStatus(),
                                "the older connection cannot register again with its start");
        Assertions.assertInstanceOf(RoomListMessage.class, kept.request(new ListRoomsMessage(TOPIC, 0), node, false).getBody(),
                                    "the kept connection stays registered");

        this.scenario.getClock().advance(SIGN_OUT_DELAY_MILLIS);

        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, later.register(node, FINGERPRINT, THIRD_CONNECTION).getStatus(),
                                "a connection started after the sign-out registers");
    }

    @Test
    void unsigned_forged_replayed_old_malformed_and_oversize_datagrams_are_dropped_without_an_answer() throws IOException {
        PeerSocket node = this.scenario.openNode(PeerScenario.createNodeConfiguration());
        PeerScenario.Client client = this.scenario.openClient();
        ManualProtocolClock behind = new ManualProtocolClock();
        PeerScenario.Client late = this.scenario.openClient(behind);
        byte[] ping = client.getEncoder().encodePaddedRequest(new PingMessage(), client.takeRequestNumber());
        byte[] forged = client.getEncoder().encodePaddedRequest(new PingMessage(), client.takeRequestNumber());
        byte[] unsigned = client.getEncoder().encodePaddedRequest(new PingMessage(), client.takeRequestNumber());
        byte[] impostor = new MessageEncoder(IdentityKeyPair.generate(new SecureRandom()), this.scenario.getClock(), new SplittableRandom(1L))
                .encodePaddedRequest(new PingMessage(), 1L);

        behind.advance(-CLOCK_BEHIND_MILLIS);
        forged[forged.length - 1] ^= 1;
        Arrays.fill(unsigned, unsigned.length - IdentityKey.SIGNATURE_LENGTH, unsigned.length, (byte) 0);
        System.arraycopy(client.getKey().getBytes(), 0, impostor, Envelope.HEADER_LENGTH - Short.BYTES - IdentityKey.LENGTH, IdentityKey.LENGTH);

        client.send(ping, node);
        client.await(Envelope::isReply);
        client.send(ping, node);
        client.send(forged, node);
        client.send(unsigned, node);
        client.send(impostor, node);
        client.send(new byte[OVERSIZE_LENGTH], node);
        client.send(new byte[]{'B', 'L', 'N', 'K', 1, 2, 3}, node);
        late.send(late.getEncoder().encodePaddedRequest(new PingMessage(), late.takeRequestNumber()), node);

        this.scenario.await(() -> node.getPeer().getStatistics().getDroppedCount(DropReason.OUTSIDE_WINDOW) == 1L, "the old ping is dropped");
        client.request(new PingMessage(), node, true);

        PeerStatistics statistics = node.getPeer().getStatistics();

        Assertions.assertTrue(client.drain().isEmpty());
        Assertions.assertTrue(late.drain().isEmpty());
        Assertions.assertEquals(1L, statistics.getDroppedCount(DropReason.REPLAYED));
        Assertions.assertEquals(3L, statistics.getDroppedCount(DropReason.FORGED));
        Assertions.assertEquals(1L, statistics.getDroppedCount(DropReason.OVERSIZE));
        Assertions.assertEquals(1L, statistics.getDroppedCount(DropReason.MALFORMED));
        Assertions.assertEquals(2L, statistics.getHandledCount(MessageKind.PING));
    }

    @Test
    void key_rate_limit_drops_messages_over_the_burst_until_tokens_refill() throws IOException {
        PeerConfiguration configuration = PeerScenario.createNodeBuilder().setKeyBurst(4).setKeyRate(2).build();
        PeerSocket node = this.scenario.openNode(configuration);
        PeerScenario.Client client = this.scenario.openClient();
        PeerStatistics statistics = node.getPeer().getStatistics();

        for (int count = 0; count < 6; count++) {
            client.send(client.getEncoder().encodePaddedRequest(new PingMessage(), client.takeRequestNumber()), node);
        }

        this.scenario.await(() -> statistics.getHandledCount(MessageKind.PING) + statistics.getDroppedCount(DropReason.KEY_RATE) == 6L,
                            "six pings");
        Assertions.assertEquals(4L, statistics.getHandledCount(MessageKind.PING));
        Assertions.assertEquals(2L, statistics.getDroppedCount(DropReason.KEY_RATE));
        Assertions.assertEquals(4, client.drain().size());

        this.scenario.getClock().advance(1000L);

        for (int count = 0; count < 3; count++) {
            client.send(client.getEncoder().encodePaddedRequest(new PingMessage(), client.takeRequestNumber()), node);
        }

        this.scenario.await(() -> statistics.getHandledCount(MessageKind.PING) + statistics.getDroppedCount(DropReason.KEY_RATE) == 9L,
                            "three pings");
        Assertions.assertEquals(6L, statistics.getHandledCount(MessageKind.PING));
    }

    @Test
    void key_rate_limit_is_charged_only_after_the_signature_is_verified() throws IOException {
        PeerConfiguration configuration = PeerScenario.createNodeBuilder().setKeyBurst(4).setKeyRate(1).build();
        PeerSocket node = this.scenario.openNode(configuration);
        PeerScenario.Client victim = this.scenario.openClient();
        PeerScenario.Client attacker = this.scenario.openClient();
        PeerStatistics statistics = node.getPeer().getStatistics();
        int forgeries = 8;

        // The attacker claims the victim's key in pings it signs with its own key: the claim must not spend the victim's tokens.
        for (int count = 0; count < forgeries; count++) {
            byte[] forged = attacker.getEncoder().encodePaddedRequest(new PingMessage(), attacker.takeRequestNumber());

            System.arraycopy(victim.getKey().getBytes(), 0, forged, Envelope.HEADER_LENGTH - Short.BYTES - IdentityKey.LENGTH, IdentityKey.LENGTH);
            attacker.send(forged, node);
        }

        this.scenario.await(() -> statistics.getDroppedCount(DropReason.FORGED) + statistics.getDroppedCount(DropReason.KEY_RATE) == forgeries,
                            "the forged pings are dropped");

        Assertions.assertEquals(forgeries, statistics.getDroppedCount(DropReason.FORGED));

        victim.request(new PingMessage(), node, true);

        Assertions.assertEquals(1L, statistics.getHandledCount(MessageKind.PING));
        Assertions.assertTrue(attacker.drain().isEmpty());
    }

    @Test
    void address_rate_limit_counts_every_key_of_a_source_address() throws IOException {
        PeerConfiguration configuration = PeerScenario.createNodeBuilder().setAddressBurst(3).setAddressRate(1).build();
        PeerSocket node = this.scenario.openNode(configuration);
        PeerScenario.Client first = this.scenario.openClient();
        PeerScenario.Client second = this.scenario.openClient();
        PeerStatistics statistics = node.getPeer().getStatistics();

        for (PeerScenario.Client client : List.of(first, second, first, second)) {
            client.send(client.getEncoder().encodePaddedRequest(new PingMessage(), client.takeRequestNumber()), node);
        }

        this.scenario.await(() -> statistics.getHandledCount(MessageKind.PING) + statistics.getDroppedCount(DropReason.ADDRESS_RATE) == 4L,
                            "four pings");
        Assertions.assertEquals(3L, statistics.getHandledCount(MessageKind.PING));
        Assertions.assertEquals(1L, statistics.getDroppedCount(DropReason.ADDRESS_RATE));
    }

    @Test
    void stores_are_bounded_in_total_and_per_host() throws IOException {
        PeerConfiguration configuration = PeerScenario.createNodeBuilder().setMaximumRoomsPerTopic(2).setMaximumRoomsPerHost(1)
                                                      .setMaximumRegistrations(3).build();
        PeerSocket node = this.scenario.openNode(configuration);
        PeerScenario.Client first = this.scenario.openClient();
        PeerScenario.Client second = this.scenario.openClient();
        PeerScenario.Client third = this.scenario.openClient();
        PeerScenario.Client fourth = this.scenario.openClient();

        first.register(node, FINGERPRINT);
        second.register(node, FINGERPRINT);
        third.register(node, FINGERPRINT);

        RoomAdvertisement firstRoom = this.createRoom(first.getKeyPair(), 1L, 1L, ROOM_LIFETIME_MILLIS, FINGERPRINT);
        RoomAdvertisement firstExtraRoom = this.createRoom(first.getKeyPair(), 2L, 1L, ROOM_LIFETIME_MILLIS, FINGERPRINT);
        RoomAdvertisement secondRoom = this.createRoom(second.getKeyPair(), 1L, 1L, ROOM_LIFETIME_MILLIS, FINGERPRINT);
        RoomAdvertisement thirdRoom = this.createRoom(third.getKeyPair(), 1L, 1L, ROOM_LIFETIME_MILLIS, FINGERPRINT);

        Assertions.assertEquals(ResultStatus.ACCEPTED, getStatus(first.request(new PublishRoomMessage(firstRoom), node, false)));
        Assertions.assertEquals(ResultStatus.LIMIT_REACHED, getStatus(first.request(new PublishRoomMessage(firstExtraRoom), node, false)));
        Assertions.assertEquals(ResultStatus.ACCEPTED, getStatus(second.request(new PublishRoomMessage(secondRoom), node, false)));
        Assertions.assertEquals(ResultStatus.LIMIT_REACHED, getStatus(third.request(new PublishRoomMessage(thirdRoom), node, false)));

        RegisterMessage register = fourth.createRegister(new byte[0], REQUESTED_SECONDS, FINGERPRINT, 0L);
        ObservedAddressMessage challenge = (ObservedAddressMessage) fourth.request(register, node, true).getBody();
        RegisterMessage repeated = fourth.createRegister(challenge.getToken(), REQUESTED_SECONDS, FINGERPRINT, 0L);

        Assertions.assertEquals(ObservedAddressStatus.FULL, ((ObservedAddressMessage) fourth.request(repeated, node, true).getBody()).getStatus());
    }

    @Test
    void copies_of_a_signed_datagram_do_not_spend_the_key_rate_of_its_sender() {
        PeerConfiguration configuration = PeerScenario.createNodeBuilder().setProofDifficulty(PROOF_DIFFICULTY).setKeyBurst(4).setKeyRate(1).build();
        DirectPeerScenario node = new DirectPeerScenario(configuration);
        DirectPeerScenario.Sender victim = node.createSender();
        DirectPeerScenario.Sender copier = node.createSender();
        byte[] ping = victim.getEncoder().encodePaddedRequest(new PingMessage(), 1L);

        // The copier got one of the victim's datagrams (sent to another peer) and resends it here from its own address.
        for (int copy = 0; copy < COPIES; copy++) {
            node.receive(ping, copier.getAddress());
        }

        node.receive(victim.getEncoder().encodePaddedRequest(new PingMessage(), 2L), victim.getAddress());

        PeerStatistics statistics = node.getPeer().getStatistics();

        Assertions.assertEquals(COPIES - 1L, statistics.getDroppedCount(DropReason.REPLAYED));
        Assertions.assertEquals(0L, statistics.getDroppedCount(DropReason.KEY_RATE));
        Assertions.assertEquals(2L, statistics.getHandledCount(MessageKind.PING), "the victim's own ping is answered");
    }

    @Test
    void an_answer_for_a_seed_with_a_guessed_request_number_does_not_take_the_seeds_place() {
        PeerConfiguration configuration = PeerScenario.createNodeBuilder().setProofDifficulty(PROOF_DIFFICULTY).setKeyBurst(GUESSES * 2).build();
        DirectPeerScenario node = new DirectPeerScenario(configuration);
        DirectPeerScenario.Sender attacker = node.createSender();
        InetSocketAddress seed = node.createAddress();

        // The attacker greets the peer, so that the peer greets it back with a request number next to the ones of its seed hellos.
        node.getPeer().addSeed(seed);
        node.receive(attacker.getEncoder().encodePaddedRequest(new HelloMessage(attacker.getCard(), HelloMessage.ROUTING_CAPABILITY, "attacker"), 1L),
                     attacker.getAddress());

        for (int round = 0; round < ROUNDS; round++) {
            node.advanceRound();
        }

        long observed = -1L;

        for (DirectPeerScenario.Sent sent : node.getSent()) {
            if (sent.getTarget().equals(attacker.getAddress()) && !sent.getEnvelope().isReply() &&
                sent.getEnvelope().getKind() == MessageKind.HELLO) {
                observed = sent.getEnvelope().getRequestNumber();
            }
        }

        Assertions.assertTrue(observed >= 0L, "the peer greets the attacker");

        for (long guess = observed - GUESSES / 2; guess <= observed + GUESSES / 2; guess++) {
            HelloMessage answer = new HelloMessage(attacker.getCard(), HelloMessage.ROUTING_CAPABILITY, "attacker");

            node.receive(attacker.getEncoder().encodeReply(answer, guess & REQUEST_NUMBER_MASK), seed);
        }

        for (NodeEndpoint live : node.getPeer().getLiveNodes()) {
            Assertions.assertFalse(live.getAddress().equals(seed) && live.getKey().equals(attacker.getKey()),
                                   "the attacker's key is proved at the seed's address");
        }
    }

    @Test
    void a_leaf_only_peer_never_becomes_a_full_peer() {
        for (boolean leafOnly : List.of(false, true)) {
            PeerConfiguration configuration = PeerScenario.createNodeBuilder().setProofDifficulty(PROOF_DIFFICULTY).setLeafOnly(leafOnly).build();
            DirectPeerScenario node = new DirectPeerScenario(configuration);
            DirectPeerScenario.Sender stranger = node.createSender();
            HelloMessage hello = new HelloMessage(stranger.getCard(), HelloMessage.ROUTING_CAPABILITY, "stranger");

            // An unrequested hello from an address the peer never sent to proves that strangers reach it, unless it is leaf-only.
            node.receive(stranger.getEncoder().encodePaddedRequest(hello, 1L), stranger.getAddress());

            for (int round = 0; round < LEAF_ROUNDS; round++) {
                node.advanceRound();
            }

            HelloMessage card = null;
            boolean dialBack = false;

            for (DirectPeerScenario.Sent sent : node.getSent()) {
                Envelope envelope = sent.getEnvelope();

                dialBack |= envelope.getKind() == MessageKind.DIAL_BACK && !envelope.isReply();

                if (envelope.getBody() instanceof HelloMessage message) {
                    card = message;
                }
            }

            Assertions.assertNotNull(card);
            Assertions.assertEquals(!leafOnly, node.getPeer().getReachability().isFullPeer(), "leaf-only " + leafOnly);
            Assertions.assertEquals(!leafOnly, card.getCard().isReachable(), "the card says whether strangers reach the peer");

            if (leafOnly) {
                Assertions.assertFalse(dialBack, "a leaf-only peer runs no self-test");
            }
        }
    }

    @Test
    void answers_to_unproved_sources_are_never_longer_than_their_requests() {
        DirectPeerScenario node = new DirectPeerScenario(PeerScenario.createNodeBuilder().setProofDifficulty(PROOF_DIFFICULTY).build());

        node.getPeer().listRooms(TOPIC);

        for (boolean padded : List.of(false, true)) {
            DirectPeerScenario.Sender sender = node.createSender();

            for (Message request : this.createRequests(node, sender)) {
                InetSocketAddress source = node.createAddress();
                byte[] datagram = padded ? sender.getEncoder().encodePaddedRequest(request, 1L) : sender.getEncoder().encodeRequest(request, 1L);

                node.receive(datagram, source);

                Assertions.assertTrue(node.countBytesTo(source) <= datagram.length,
                                      request.getKind() + (padded ? " padded" : "") + ": " + node.countBytesTo(source) + " > " + datagram.length);
            }
        }

        Assertions.assertEquals(0L, node.getPeer().getStatistics().getDroppedCount(DropReason.DEFECT));
    }

    @Test
    void mutated_signed_datagrams_of_every_kind_never_fail_a_handler() {
        PeerConfiguration configuration = PeerScenario.createNodeBuilder().setProofDifficulty(PROOF_DIFFICULTY).setKeyBurst(FUZZ_BURST)
                                                      .setAddressBurst(FUZZ_BURST).setRateLimiterCapacity(FUZZ_BURST).build();
        DirectPeerScenario node = new DirectPeerScenario(configuration);
        DirectPeerScenario.Sender sender = node.createSender();
        SplittableRandom random = new SplittableRandom(FUZZ_SEED);
        List<byte[]> samples = new ArrayList<>();
        List<InetSocketAddress> sources = new ArrayList<>(List.of(sender.getAddress()));

        // Many source addresses, so that the penalties of the changed datagrams (counted per key and address) leave most of them heard.
        for (int index = 0; index < FUZZ_SOURCES; index++) {
            sources.add(node.createAddress());
        }

        node.getPeer().listRooms(TOPIC);
        Assertions.assertNotNull(node.getPeer().useCommunity(CHANNEL), "the peer takes part in the channel");

        for (Message request : this.createRequests(node, sender)) {
            samples.add(sender.getEncoder().encodeRequest(request, 1L));
        }

        for (Message reply : this.createReplies(node, sender)) {
            samples.add(sender.getEncoder().encodeReply(reply, 1L));
        }

        for (int round = 0; round < FUZZ_ROUNDS; round++) {
            byte[] datagram = samples.get(random.nextInt(samples.size())).clone();
            int bodyLength = (datagram[Envelope.HEADER_LENGTH - Short.BYTES] & BYTE_MASK) << Byte.SIZE |
                             datagram[Envelope.HEADER_LENGTH - 1] & BYTE_MASK;
            int changes = 1 + random.nextInt(MAXIMUM_CHANGES);

            for (int change = 0; change < changes && bodyLength > 0; change++) {
                int index = Envelope.HEADER_LENGTH + random.nextInt(bodyLength);

                datagram[index] = random.nextBoolean() ? (byte) (datagram[index] ^ 1 << random.nextInt(Byte.SIZE))
                                                       : BOUNDARY_BYTES[random.nextInt(BOUNDARY_BYTES.length)];
            }

            node.receive(sender.resign(datagram, random.nextLong()), sources.get(random.nextInt(sources.size())));

            if (round % MAINTENANCE_ROUNDS == 0) {
                node.advanceRound();
            }
        }

        PeerStatistics statistics = node.getPeer().getStatistics();

        LOGGER.info("Fuzzed peer: {0}", statistics);

        Assertions.assertEquals(0L, statistics.getDroppedCount(DropReason.DEFECT), statistics.toString());
        Assertions.assertTrue(statistics.getDroppedCount(DropReason.MALFORMED) < FUZZ_ROUNDS, "some mutations decode and reach the handlers");
    }

    private @NotNull List<@NotNull Message> createRequests(@NotNull DirectPeerScenario node, @NotNull DirectPeerScenario.Sender sender) {
        IdentityKeyPair keyPair = sender.getKeyPair();
        long nowMillis = node.getClock().getEpochMillis();
        RoomAdvertisement room = RoomAdvertisement.signPublication(keyPair, PeerScenario.DEFAULT_CONNECTION, TOPIC, 1L, 1L,
                                                                   nowMillis + ROOM_LIFETIME_MILLIS,
                                                                   new RoomDescription(FINGERPRINT, "Room", "Map", 1, CAPACITY),
                                                                   List.of(), null);
        RoomAdvertisement withdrawal = RoomAdvertisement.signWithdrawal(keyPair, PeerScenario.DEFAULT_CONNECTION, TOPIC, 1L, 2L,
                                                                        room.getExpiryMillis(),
                                                                        new RoomDescription(FINGERPRINT, "Room", "Map", 1, CAPACITY),
                                                                        List.of());
        ChatLineRecord line = ChatLineRecord.sign(keyPair, new PlayerStamp(keyPair.getPublicKey(), "player", 1L), CHANNEL, 1L,
                                                  nowMillis, false, "hello");
        byte[] token = new byte[AddressTokens.TOKEN_LENGTH];

        return List.of(new RegisterMessage(new byte[0], REQUESTED_SECONDS, "player", 1L, FINGERPRINT, 0L, 0L),
                       new PublishRoomMessage(room),
                       new ListRoomsMessage(TOPIC, 0),
                       new WithdrawRoomMessage(withdrawal),
                       new SignalMessage(keyPair.getPublicKey(), PeerScenario.DEFAULT_CONNECTION, new byte[]{1, 2, 3}),
                       new PingMessage(),
                       new HelloMessage(sender.getCard(), HelloMessage.ROUTING_CAPABILITY, "fuzz"),
                       new FindPeersMessage(node.getPeer().getId(), sender.getCard()),
                       new FindPeersMessage(node.getPeer().getId(), null),
                       new DialBackMessage(sender.getCard(), 1L, null),
                       new DialBackMessage(sender.getCard(), 1L, sender.getAddress()),
                       new ProbeMessage(1L),
                       new GetTopicMessage(TOPIC),
                       new AnnounceMessage(TOPIC, sender.getCard(), token),
                       new GraftMessage(TOPIC, sender.getCard(), token),
                       new PruneMessage(TOPIC),
                       new GossipMessage(TOPIC, List.of(room)),
                       new GossipMessage(CHANNEL, List.of(line)),
                       new IHaveMessage(TOPIC, List.of(room.getRecordId())),
                       new IWantMessage(TOPIC, List.of(room.getRecordId())),
                       new PublishRecordMessage(line),
                       new RecentMessage(CHANNEL, true),
                       new SealedMessage(keyPair.getPublicKey(), PeerScenario.DEFAULT_CONNECTION, new byte[SealedMessage.EPHEMERAL_KEY_LENGTH],
                                         new byte[]{1, 2, 3}),
                       new PublishRecordMessage(SignOutRecord.sign(keyPair, PeerScenario.DEFAULT_CONNECTION, 1L,
                                                                   nowMillis, nowMillis + SHORT_LIFETIME_MILLIS)));
    }

    private @NotNull List<@NotNull Message> createReplies(@NotNull DirectPeerScenario node, @NotNull DirectPeerScenario.Sender sender) {
        PeerContact contact = new PeerContact(sender.getKey(), sender.getCard().getProofNonce(), sender.getAddress());
        RoomAdvertisement room = RoomAdvertisement.signPublication(sender.getKeyPair(), PeerScenario.DEFAULT_CONNECTION, TOPIC, 1L, 1L,
                                                                   node.getClock().getEpochMillis() + ROOM_LIFETIME_MILLIS,
                                                                   new RoomDescription(FINGERPRINT, "Room", "Map", 1, CAPACITY),
                                                                   List.of(), null);

        return List.of(new HelloMessage(sender.getCard(), HelloMessage.ROUTING_CAPABILITY, "fuzz"),
                       new PeersMessage(CoordinateReport.UNKNOWN, List.of(contact)),
                       new TopicPeersMessage(TOPIC, new byte[AddressTokens.TOKEN_LENGTH], true, List.of(new TopicMember(contact, true))),
                       new ResultMessage(ResultStatus.ACCEPTED, 1L),
                       new DialBackMessage(sender.getCard(), 1L, sender.getAddress()),
                       new PongMessage(sender.getAddress(), "fuzz"),
                       new ObservedAddressMessage(ObservedAddressStatus.REGISTERED, sender.getAddress(), 1, new byte[0], CoordinateReport.UNKNOWN),
                       new RoomListMessage(1, 0, List.of(room)));
    }
}
