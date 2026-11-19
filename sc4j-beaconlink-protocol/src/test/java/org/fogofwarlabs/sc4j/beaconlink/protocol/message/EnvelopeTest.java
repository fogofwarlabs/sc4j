package org.fogofwarlabs.sc4j.beaconlink.protocol.message;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateReport;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.NetworkCoordinate;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PeerId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.ModFingerprint;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomAdvertisement;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomDescription;
import org.fogofwarlabs.sc4j.beaconlink.protocol.test.ManualProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.RecordId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKind;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicRecord;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Tests the signed envelope of the protocol: every message kind (the topic and community kinds included) round trips with a valid signature,
 * unvalidated requests are padded to the length of their answers, and malformed, oversize, cut or changed datagrams are rejected or fail the
 * signature check without an exception; changed bodies of every kind and record that still decode encode again.
 */
final class EnvelopeTest {
    private static final @NotNull Logger LOGGER = Logger.of(EnvelopeTest.class);
    private static final long REQUEST_NUMBER = 0xfffffff0L;
    private static final int PORT = 47001;
    private static final int LIFETIME_SECONDS = 120;
    private static final long LIFETIME_MILLIS = LIFETIME_SECONDS * 1000L;
    private static final long CONNECTION = 0x0102030405060708L;
    private static final long CONNECTION_START_MILLIS = 1_700_000_000_000L;
    private static final int CAPACITY = 4;
    private static final long FUZZ_SEED = 20261108L;
    private static final int FUZZ_ROUNDS = 2000;
    private static final int BODY_FUZZ_ROUNDS = 5_000;
    private static final int MAXIMUM_BODY_CHANGES = 3;
    private static final byte @NotNull [] BOUNDARY_BYTES = {0, 1, 0x7f, (byte) 0x80, (byte) 0xff};
    private static final int FLAGS_OFFSET = 6;
    private static final int KIND_OFFSET = 5;
    private static final int VERSION_OFFSET = 4;
    private static final int BYTE_MASK = 0xff;
    private static final long PROOF_NONCE = 77L;
    private static final double COORDINATE_ERROR = 0.25;
    private static final @NotNull NetworkCoordinate COORDINATE = new NetworkCoordinate(new double[]{12.0, -40.0, 7.0}, 9.0);
    private static final @NotNull CoordinateReport REPORT = new CoordinateReport(COORDINATE, COORDINATE_ERROR);
    private static final @NotNull PeerCard CARD = new PeerCard(PROOF_NONCE, true, REPORT);
    private static final @NotNull String SOFTWARE = "sc4j-beaconlink-peer 0.2";
    private static final @NotNull TopicKey TOPIC = TopicKey.of(TopicKind.MOD, "test-mod");
    private static final @NotNull TopicKey CHANNEL = TopicKey.ofChannel("General");
    private static final byte @NotNull [] TOKEN = {1, 2, 3, 4};

    private static @NotNull Envelope decode(byte @NotNull [] datagram) {
        DecodeResult result = Envelope.decode(datagram, datagram.length);
        Envelope envelope = result.getEnvelope();

        Assertions.assertNull(result.getFailure());
        Assertions.assertNotNull(envelope);

        return envelope;
    }

    private static @NotNull DecodeFailure reject(byte @NotNull [] datagram) {
        DecodeResult result = Envelope.decode(datagram, datagram.length);
        DecodeFailure failure = result.getFailure();

        Assertions.assertNull(result.getEnvelope());
        Assertions.assertNotNull(failure);

        return failure;
    }

    private static @NotNull List<@NotNull TopicRecord> findRecords(@NotNull Message body) {
        return switch (body) {
            case GossipMessage gossip -> gossip.getRecords();
            case PublishRecordMessage publish -> List.of(publish.getRecord());
            case PublishRoomMessage publish -> List.of(publish.getAdvertisement());
            case WithdrawRoomMessage withdraw -> List.of(withdraw.getAdvertisement());
            case RoomListMessage list -> List.copyOf(list.getAdvertisements());
            default -> List.of();
        };
    }

    private final @NotNull IdentityKeyPair sender;
    private final @NotNull ManualProtocolClock clock;
    private final @NotNull MessageEncoder encoder;

    EnvelopeTest() {
        this.sender  = IdentityKeyPair.generate(new SecureRandom());
        this.clock   = new ManualProtocolClock();
        this.encoder = new MessageEncoder(this.sender, this.clock, new SplittableRandom(FUZZ_SEED));
    }

    private @NotNull RoomAdvertisement createRoom(boolean withdrawn) {
        RoomDescription description = new RoomDescription(ModFingerprint.of(new byte[]{4, 2}), "Room", "Map", 1, CAPACITY);
        long expiry = this.clock.getEpochMillis() + LIFETIME_MILLIS;

        return withdrawn ? RoomAdvertisement.signWithdrawal(this.sender, CONNECTION, TOPIC, 1L, 2L, expiry, description, List.of())
                         : RoomAdvertisement.signPublication(this.sender, CONNECTION, TOPIC, 1L, 1L, expiry, description, List.of(), COORDINATE);
    }

    private @NotNull List<@NotNull Message> createRequests() throws UnknownHostException {
        ModFingerprint fingerprint = ModFingerprint.of(new byte[]{4, 2});
        NodeEndpoint node = new NodeEndpoint(this.sender.getPublicKey(), new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        PeerId target = PeerId.of(this.sender.getPublicKey(), PROOF_NONCE);
        RecordId record = this.createRoom(false).getRecordId();

        return List.of(new RegisterMessage(new byte[]{1, 2, 3}, LIFETIME_SECONDS, "player", PROOF_NONCE, fingerprint, 0L, 0L),
                       new PublishRoomMessage(this.createRoom(false)),
                       new ListRoomsMessage(TOPIC, 3),
                       new WithdrawRoomMessage(this.createRoom(true)),
                       new SignalMessage(this.sender.getPublicKey(), CONNECTION, new byte[]{9, 8, 7}),
                       new PingMessage(),
                       new HelloMessage(CARD, HelloMessage.ROUTING_CAPABILITY, SOFTWARE),
                       new FindPeersMessage(target, CARD),
                       new FindPeersMessage(target, null),
                       new DialBackMessage(CARD, 7L, null),
                       new DialBackMessage(CARD, 7L, node.getAddress()),
                       new ProbeMessage(7L),
                       new GetTopicMessage(TOPIC),
                       new AnnounceMessage(TOPIC, CARD, TOKEN),
                       new GraftMessage(TOPIC, CARD, TOKEN),
                       new PruneMessage(TOPIC),
                       new GossipMessage(TOPIC, List.of(this.createRoom(false), this.createRoom(true))),
                       new IHaveMessage(TOPIC, List.of(record)),
                       new IHaveMessage(TOPIC, List.of()),
                       new IWantMessage(TOPIC, List.of(record)),
                       new PublishRecordMessage(this.createPresence()),
                       new PublishRecordMessage(this.createLine()),
                       new RecentMessage(CHANNEL, true),
                       new RecentMessage(TOPIC, false),
                       new SealedMessage(this.sender.getPublicKey(), CONNECTION, new byte[SealedMessage.EPHEMERAL_KEY_LENGTH], new byte[]{1, 2, 3}),
                       new PublishRecordMessage(this.createSignOut()),
                       new GossipMessage(CHANNEL, List.of(this.createPresence(), this.createLine())));
    }

    private @NotNull PresenceRecord createPresence() throws UnknownHostException {
        NodeEndpoint homePeer = new NodeEndpoint(this.sender.getPublicKey(), new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        PresenceContent content = new PresenceContent("player", "test-mod", "1.0.0", new byte[PresenceContent.IDENTITY_DIGEST_LENGTH],
                                                      PresenceStatus.IN_GAME, "brb", "General", new byte[PresenceContent.WHISPER_KEY_LENGTH],
                                                      List.of(homePeer), COORDINATE);

        return PresenceRecord.sign(this.sender, new PlayerStamp(this.sender.getPublicKey(), "player", PROOF_NONCE), CHANNEL, CONNECTION, 3L,
                                   this.clock.getEpochMillis() + LIFETIME_MILLIS, content);
    }

    private @NotNull SignOutRecord createSignOut() {
        return SignOutRecord.sign(this.sender, CONNECTION, 5L, CONNECTION_START_MILLIS, CONNECTION_START_MILLIS + LIFETIME_MILLIS);
    }

    private @NotNull ChatLineRecord createLine() {
        return ChatLineRecord.sign(this.sender, new PlayerStamp(this.sender.getPublicKey(), "player", PROOF_NONCE), CHANNEL, 4L,
                                   this.clock.getEpochMillis(), true, "waves");
    }

    private @NotNull List<@NotNull Message> createReplies() throws UnknownHostException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("::1"), PORT);
        PeerContact contact = new PeerContact(this.sender.getPublicKey(), PROOF_NONCE, address);

        return List.of(new ObservedAddressMessage(ObservedAddressStatus.TOKEN_REQUIRED, address, 0, new byte[16], REPORT),
                       new TopicPeersMessage(TOPIC, TOKEN, true, List.of(new TopicMember(contact, false))),
                       new TopicPeersMessage(TOPIC, new byte[0], false, List.of()),
                       new RoomListMessage(1, 0, List.of(this.createRoom(false))),
                       new HelloMessage(new PeerCard(PROOF_NONCE, false, CoordinateReport.UNKNOWN), 0, SOFTWARE),
                       new PeersMessage(CoordinateReport.UNKNOWN, List.of()),
                       new PeersMessage(REPORT, List.of(contact)),
                       new ResultMessage(ResultStatus.ACCEPTED, 5L),
                       new PongMessage(address, SOFTWARE),
                       new DialBackMessage(CARD, 7L, address),
                       new DialBackMessage(CARD, 7L, null));
    }

    private void assertRoundTrip(@NotNull Message message, boolean reply) {
        byte[] datagram = reply ? this.encoder.encodeReply(message, REQUEST_NUMBER) : this.encoder.encodeRequest(message, REQUEST_NUMBER);
        Envelope envelope = decode(datagram);

        Assertions.assertEquals(message.getKind(), envelope.getKind());
        Assertions.assertEquals(reply, envelope.isReply());
        Assertions.assertEquals(REQUEST_NUMBER, envelope.getRequestNumber());
        Assertions.assertEquals(this.clock.getEpochMillis(), envelope.getTimestampMillis());
        Assertions.assertEquals(this.sender.getPublicKey(), envelope.getSender());
        Assertions.assertTrue(envelope.isSignatureValid(), message.getKind().toString());
        Assertions.assertArrayEquals(datagram, envelope.getDatagram());
    }

    @Test
    void every_kind_round_trips_with_a_valid_signature() throws UnknownHostException {
        for (Message message : this.createRequests()) {
            this.assertRoundTrip(message, false);
        }

        for (Message message : this.createReplies()) {
            this.assertRoundTrip(message, true);
        }
    }

    @Test
    void bodies_read_back_their_fields() throws UnknownHostException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT);
        ObservedAddressMessage observed = new ObservedAddressMessage(ObservedAddressStatus.REGISTERED, address, LIFETIME_SECONDS, new byte[]{5},
                                                                     REPORT);
        ObservedAddressMessage readObserved = (ObservedAddressMessage) decode(this.encoder.encodeReply(observed, 1L)).getBody();
        RegisterMessage register = new RegisterMessage(new byte[]{1, 2}, LIFETIME_SECONDS, "player", PROOF_NONCE, ModFingerprint.of(new byte[]{3}),
                                                       CONNECTION, CONNECTION_START_MILLIS);
        RegisterMessage readRegister = (RegisterMessage) decode(this.encoder.encodeRequest(register, 1L)).getBody();
        SignalMessage signal = new SignalMessage(this.sender.getPublicKey(), CONNECTION, new byte[]{9, 8});
        SignalMessage readSignal = (SignalMessage) decode(this.encoder.encodeRequest(signal, 1L)).getBody();

        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, readObserved.getStatus());
        Assertions.assertEquals(address, readObserved.getAddress());
        Assertions.assertEquals(LIFETIME_SECONDS, readObserved.getLifetimeSeconds());
        Assertions.assertArrayEquals(new byte[]{5}, readObserved.getToken());
        Assertions.assertEquals(COORDINATE, readObserved.getCoordinate().getCoordinate());
        Assertions.assertEquals(COORDINATE_ERROR, readObserved.getCoordinate().getError());
        Assertions.assertArrayEquals(new byte[]{1, 2}, readRegister.getToken());
        Assertions.assertEquals("player", readRegister.getDisplayName());
        Assertions.assertEquals(PROOF_NONCE, readRegister.getStampNonce());
        Assertions.assertEquals(new PlayerStamp(this.sender.getPublicKey(), "player", PROOF_NONCE),
                                readRegister.getStamp(this.sender.getPublicKey()));
        Assertions.assertEquals(ModFingerprint.of(new byte[]{3}), readRegister.getFingerprint());
        Assertions.assertEquals(CONNECTION, readRegister.getConnection());
        Assertions.assertEquals(CONNECTION_START_MILLIS, readRegister.getConnectionStartMillis());
        Assertions.assertEquals(this.sender.getPublicKey(), readSignal.getTarget());
        Assertions.assertEquals(CONNECTION, readSignal.getTargetConnection());
        Assertions.assertArrayEquals(new byte[]{9, 8}, readSignal.getPayload());

        PongMessage readPong = (PongMessage) decode(this.encoder.encodeReply(new PongMessage(address, "node 1.0"), 1L)).getBody();

        Assertions.assertEquals(address, readPong.getAddress());
        Assertions.assertEquals("node 1.0", readPong.getSoftware());

        DialBackMessage readDialBack = (DialBackMessage) decode(this.encoder.encodeReply(new DialBackMessage(CARD, -3L, address), 1L)).getBody();
        DialBackMessage readRefusal = (DialBackMessage) decode(this.encoder.encodeReply(new DialBackMessage(CARD, 4L, null), 1L)).getBody();
        ProbeMessage readProbe = (ProbeMessage) decode(this.encoder.encodeRequest(new ProbeMessage(-3L), 1L)).getBody();

        Assertions.assertEquals(-3L, readDialBack.getNonce());
        Assertions.assertEquals(address, readDialBack.getAddress());
        Assertions.assertEquals(PROOF_NONCE, readDialBack.getCard().getProofNonce());
        Assertions.assertEquals(4L, readRefusal.getNonce());
        Assertions.assertNull(readRefusal.getAddress());
        Assertions.assertEquals(-3L, readProbe.getNonce());
    }

    @Test
    void community_bodies_read_back_their_fields() throws UnknownHostException {
        PresenceRecord presence = this.createPresence();
        ChatLineRecord line = this.createLine();
        byte[] ephemeralKey = new byte[SealedMessage.EPHEMERAL_KEY_LENGTH];

        Arrays.fill(ephemeralKey, (byte) 7);

        byte[] publish = this.encoder.encodeRequest(new PublishRecordMessage(presence), 1L);
        PublishRecordMessage readPublish = (PublishRecordMessage) decode(publish).getBody();
        RecentMessage readRecent = (RecentMessage) decode(this.encoder.encodeRequest(new RecentMessage(CHANNEL, true), 1L)).getBody();
        SealedMessage sealed = new SealedMessage(this.sender.getPublicKey(), CONNECTION, ephemeralKey, new byte[]{4, 5});
        SignOutRecord signOut = this.createSignOut();
        SignOutRecord readSignOut = (SignOutRecord) ((PublishRecordMessage) decode(this.encoder.encodeRequest(new PublishRecordMessage(signOut), 1L))
                .getBody()).getRecord();
        SealedMessage readSealed = (SealedMessage) decode(this.encoder.encodeRequest(sealed, 1L)).getBody();
        GossipMessage readGossip = (GossipMessage) decode(this.encoder.encodeRequest(new GossipMessage(CHANNEL, List.of(presence, line)), 1L))
                .getBody();
        PresenceRecord readPresence = (PresenceRecord) readPublish.getRecord();
        ChatLineRecord readLine = (ChatLineRecord) readGossip.getRecords().getLast();

        Assertions.assertEquals(presence, readPresence);
        Assertions.assertTrue(readPresence.isSignatureValid());
        Assertions.assertEquals(CONNECTION, readPresence.getConnection());
        Assertions.assertEquals(presence.getContent(), readPresence.getContent());
        Assertions.assertEquals(PresenceStatus.IN_GAME, readPresence.getContent().getStatus());
        Assertions.assertEquals("brb", readPresence.getContent().getAwayText());
        Assertions.assertEquals(new PlayerStamp(this.sender.getPublicKey(), "player", PROOF_NONCE), readPresence.getStamp());
        Assertions.assertEquals(new PlayerStamp(this.sender.getPublicKey(), "player", PROOF_NONCE), readLine.getStamp());
        Assertions.assertEquals(presence.getRecordId(), readPresence.getRecordId());
        Assertions.assertEquals(CHANNEL, readRecent.getTopic());
        Assertions.assertTrue(readRecent.isBacklog());
        Assertions.assertEquals(this.sender.getPublicKey(), readSealed.getTarget());
        Assertions.assertEquals(CONNECTION, readSealed.getTargetConnection());
        Assertions.assertEquals(signOut, readSignOut);
        Assertions.assertTrue(readSignOut.isSignatureValid());
        Assertions.assertEquals(TopicKey.ofPlayer(this.sender.getPublicKey()), readSignOut.getTopic());
        Assertions.assertEquals(CONNECTION, readSignOut.getKeptConnection());
        Assertions.assertEquals(CONNECTION_START_MILLIS, readSignOut.getTimestampMillis());
        Assertions.assertTrue(readSignOut.isSignedOut(CONNECTION + 1L, CONNECTION_START_MILLIS - 1L), "an older other connection is signed out");
        Assertions.assertFalse(readSignOut.isSignedOut(CONNECTION + 1L, CONNECTION_START_MILLIS), "a later connection is not");
        Assertions.assertFalse(readSignOut.isSignedOut(CONNECTION, 0L), "the kept connection is not");
        Assertions.assertArrayEquals(ephemeralKey, readSealed.getEphemeralKey());
        Assertions.assertArrayEquals(new byte[]{4, 5}, readSealed.getSealed());
        Assertions.assertEquals(List.of(presence, line), readGossip.getRecords());
        Assertions.assertTrue(readLine.isEmote());
        Assertions.assertEquals("waves", readLine.getText());
        Assertions.assertTrue(readLine.isSignatureValid());
        Assertions.assertEquals(line.getTimestampMillis() + ChatLineRecord.LIFETIME_MILLIS, readLine.getExpiryMillis());
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PublishRecordMessage(this.createRoom(false)));

        PlayerStamp stamp = new PlayerStamp(this.sender.getPublicKey(), "player", 0L);

        Assertions.assertThrows(IllegalArgumentException.class, () -> ChatLineRecord.sign(this.sender, stamp, TOPIC, 1L, 0L, false, "text"));
    }

    @Test
    void peer_layer_bodies_read_back_their_fields() throws UnknownHostException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("192.0.2.7"), PORT);
        PeerId target = PeerId.of(this.sender.getPublicKey(), PROOF_NONCE);
        PeerContact contact = new PeerContact(this.sender.getPublicKey(), PROOF_NONCE, address);
        HelloMessage hello = new HelloMessage(new PeerCard(-1L, false, CoordinateReport.UNKNOWN), HelloMessage.ROUTING_CAPABILITY, SOFTWARE);
        HelloMessage readHello = (HelloMessage) decode(this.encoder.encodeReply(hello, 1L)).getBody();
        FindPeersMessage readFind = (FindPeersMessage) decode(this.encoder.encodePaddedRequest(new FindPeersMessage(target, CARD), 1L)).getBody();
        FindPeersMessage readClientFind = (FindPeersMessage) decode(this.encoder.encodeRequest(new FindPeersMessage(target, null), 1L)).getBody();
        PeersMessage readPeers = (PeersMessage) decode(this.encoder.encodeReply(new PeersMessage(REPORT, List.of(contact)), 1L)).getBody();
        PeerCard readCard = readFind.getCard();

        Assertions.assertEquals(-1L, readHello.getCard().getProofNonce());
        Assertions.assertFalse(readHello.getCard().isReachable());
        Assertions.assertEquals(HelloMessage.ROUTING_CAPABILITY, readHello.getCapabilities());
        Assertions.assertEquals(SOFTWARE, readHello.getSoftware());
        Assertions.assertEquals(target, readFind.getTarget());
        Assertions.assertNotNull(readCard);
        Assertions.assertEquals(PROOF_NONCE, readCard.getProofNonce());
        Assertions.assertTrue(readCard.isReachable());
        Assertions.assertEquals(COORDINATE, readCard.getCoordinate().getCoordinate());
        Assertions.assertEquals(CoordinateReport.MAXIMUM_ERROR, readHello.getCard().getCoordinate().getError());
        Assertions.assertNull(readClientFind.getCard());
        Assertions.assertEquals(COORDINATE, readPeers.getCoordinate().getCoordinate());
        Assertions.assertEquals(COORDINATE_ERROR, readPeers.getCoordinate().getError());
        Assertions.assertEquals(List.of(contact), readPeers.getPeers());
        Assertions.assertEquals(target, readPeers.getPeers().getFirst().getId());
        Assertions.assertEquals(PROOF_NONCE, readPeers.getPeers().getFirst().getProofNonce());
    }

    @Test
    void topic_bodies_read_back_their_fields() throws UnknownHostException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("192.0.2.7"), PORT);
        TopicMember member = new TopicMember(new PeerContact(this.sender.getPublicKey(), PROOF_NONCE, address), true);
        RoomAdvertisement room = this.createRoom(false);
        TopicPeersMessage readPeers = (TopicPeersMessage) decode(this.encoder.encodeReply(new TopicPeersMessage(TOPIC, TOKEN, true, List.of(member)),
                                                                                          1L)).getBody();
        AnnounceMessage readAnnounce = (AnnounceMessage) decode(this.encoder.encodeRequest(new AnnounceMessage(TOPIC, CARD, TOKEN), 1L)).getBody();
        GraftMessage readGraft = (GraftMessage) decode(this.encoder.encodeRequest(new GraftMessage(TOPIC, CARD, TOKEN), 1L)).getBody();
        GossipMessage readGossip = (GossipMessage) decode(this.encoder.encodeRequest(new GossipMessage(TOPIC, List.of(room)), 1L)).getBody();
        IWantMessage readWant = (IWantMessage) decode(this.encoder.encodeRequest(new IWantMessage(TOPIC, List.of(room.getRecordId())), 1L)).getBody();
        ListRoomsMessage readList = (ListRoomsMessage) decode(this.encoder.encodeRequest(new ListRoomsMessage(TOPIC, 2), 1L)).getBody();

        Assertions.assertEquals(TOPIC, readPeers.getTopic());
        Assertions.assertArrayEquals(TOKEN, readPeers.getToken());
        Assertions.assertTrue(readPeers.isMember());
        Assertions.assertEquals(List.of(member), readPeers.getMembers());
        Assertions.assertEquals(TOPIC, readAnnounce.getTopic());
        Assertions.assertEquals(PROOF_NONCE, readAnnounce.getCard().getProofNonce());
        Assertions.assertArrayEquals(TOKEN, readAnnounce.getToken());
        Assertions.assertArrayEquals(TOKEN, readGraft.getToken());
        Assertions.assertEquals(List.of(room), readGossip.getRecords());
        Assertions.assertEquals(TOPIC, readGossip.getRecords().getFirst().getTopic());
        Assertions.assertEquals(List.of(room.getRecordId()), readWant.getIds());
        Assertions.assertEquals(TOPIC, readList.getTopic());
        Assertions.assertEquals(2, readList.getOffset());
        Assertions.assertNotEquals(TOPIC, TopicKey.of(TopicKind.MOD, "test-mod-2"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new IWantMessage(TOPIC, List.of()));
    }

    @Test
    void encode_padded_request_should_reach_the_length_of_every_unvalidated_answer() throws UnknownHostException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("::1"), PORT);
        byte[] ping = this.encoder.encodePaddedRequest(new PingMessage(), 1L);
        byte[] largestAnswer = this.encoder.encodeReply(new ObservedAddressMessage(ObservedAddressStatus.TOKEN_REQUIRED, address,
                                                                                   RegisterMessage.MAXIMUM_LIFETIME_SECONDS,
                                                                                   new byte[RegisterMessage.MAXIMUM_TOKEN_LENGTH], REPORT), 1L);
        byte[] helloAnswer = this.encoder.encodeReply(new HelloMessage(CARD, HelloMessage.ROUTING_CAPABILITY,
                                                                       "n".repeat(HelloMessage.MAXIMUM_SOFTWARE_LENGTH)), 1L);
        byte[] pingAnswer = this.encoder.encodeReply(new PongMessage(address, "n".repeat(HelloMessage.MAXIMUM_SOFTWARE_LENGTH)), 1L);
        byte[] dialBackAnswer = this.encoder.encodeReply(new DialBackMessage(CARD, 1L, address), 1L);
        List<PeerContact> fullList = new ArrayList<>();

        for (int index = 0; index < PeersMessage.MAXIMUM_COUNT; index++) {
            fullList.add(new PeerContact(this.sender.getPublicKey(), index, address));
        }

        byte[] find = this.encoder.encodePaddedRequest(new FindPeersMessage(PeerId.of(this.sender.getPublicKey(), 1L), CARD), 1L);
        byte[] peersAnswer = this.encoder.encodeReply(new PeersMessage(REPORT, fullList), 1L);
        List<TopicMember> fullMembers = new ArrayList<>();

        for (int index = 0; index < TopicPeersMessage.MAXIMUM_COUNT; index++) {
            fullMembers.add(new TopicMember(new PeerContact(this.sender.getPublicKey(), index, address), true));
        }

        byte[] getTopic = this.encoder.encodePaddedRequest(new GetTopicMessage(TOPIC), 1L);
        byte[] topicAnswer = this.encoder.encodeReply(new TopicPeersMessage(TOPIC, new byte[RegisterMessage.MAXIMUM_TOKEN_LENGTH], true,
                                                                            fullMembers), 1L);

        Assertions.assertEquals(MessageEncoder.UNVALIDATED_REQUEST_LENGTH, ping.length);
        Assertions.assertTrue(largestAnswer.length <= ping.length);
        Assertions.assertTrue(helloAnswer.length <= ping.length);
        Assertions.assertTrue(pingAnswer.length <= ping.length);
        Assertions.assertTrue(dialBackAnswer.length <= ping.length);
        Assertions.assertTrue(decode(ping).isSignatureValid());
        Assertions.assertEquals(PeersMessage.MAXIMUM_DATAGRAM_LENGTH, find.length);
        Assertions.assertEquals(PeersMessage.MAXIMUM_DATAGRAM_LENGTH, peersAnswer.length);
        Assertions.assertEquals(TopicPeersMessage.MAXIMUM_DATAGRAM_LENGTH, getTopic.length);
        Assertions.assertEquals(TopicPeersMessage.MAXIMUM_DATAGRAM_LENGTH, topicAnswer.length);
        Assertions.assertTrue(TopicPeersMessage.MAXIMUM_DATAGRAM_LENGTH <= Envelope.MAXIMUM_DATAGRAM_LENGTH);
    }

    @Test
    void encode_should_throw_for_a_kind_sent_the_wrong_way() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> this.encoder.encodeReply(new PingMessage(), 1L));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> this.encoder.encodeRequest(new ResultMessage(ResultStatus.ACCEPTED, 0L), 1L));
        Assertions.assertThrows(IllegalArgumentException.class, () -> this.encoder.encodeRequest(new PingMessage(), -1L));
    }

    @Test
    void decode_should_reject_malformed_datagrams_with_their_reason() {
        byte[] ping = this.encoder.encodeRequest(new PingMessage(), 1L);
        byte[] badMagic = ping.clone();
        byte[] badVersion = ping.clone();
        byte[] unknownKind = ping.clone();
        byte[] replyPing = ping.clone();
        byte[] unknownFlag = ping.clone();
        byte[] longBody = ping.clone();
        byte[] trailing = new byte[ping.length + 1];
        byte[] padded = this.encoder.encodePaddedRequest(new PingMessage(), 1L);
        byte[] dirtyPadding = padded.clone();

        badMagic[0]                          = 'X';
        badVersion[VERSION_OFFSET]           = Envelope.PROTOCOL_VERSION + 1;
        unknownKind[KIND_OFFSET]             = (byte) 0xee;
        replyPing[FLAGS_OFFSET]              = 1;
        unknownFlag[FLAGS_OFFSET]            = 2;
        longBody[Envelope.HEADER_LENGTH - 1] = 1;
        System.arraycopy(ping, 0, trailing, 0, ping.length);
        dirtyPadding[Envelope.HEADER_LENGTH + Short.BYTES] = 1;

        Assertions.assertEquals(DecodeFailure.TOO_SHORT, reject(new byte[Envelope.MINIMUM_DATAGRAM_LENGTH - 1]));
        Assertions.assertEquals(DecodeFailure.TOO_LONG, reject(new byte[Envelope.MAXIMUM_DATAGRAM_LENGTH + 1]));
        Assertions.assertEquals(DecodeFailure.BAD_MAGIC, reject(badMagic));
        Assertions.assertEquals(DecodeFailure.UNSUPPORTED_VERSION, reject(badVersion));
        Assertions.assertEquals(DecodeFailure.UNKNOWN_KIND, reject(unknownKind));
        Assertions.assertEquals(DecodeFailure.BAD_FLAGS, reject(replyPing));
        Assertions.assertEquals(DecodeFailure.BAD_FLAGS, reject(unknownFlag));
        Assertions.assertEquals(DecodeFailure.MALFORMED_BODY, reject(longBody));
        Assertions.assertEquals(DecodeFailure.BAD_SIGNATURE_FIELD, reject(trailing));
        Assertions.assertEquals(DecodeFailure.BAD_PADDING, reject(dirtyPadding));
    }

    @Test
    void decode_should_reject_a_body_with_bytes_left_over() {
        byte[] result = this.encoder.encodeReply(new ResultMessage(ResultStatus.ACCEPTED, 0L), 1L);
        byte[] extended = new byte[result.length + 1];
        int bodyLengthOffset = Envelope.HEADER_LENGTH - Short.BYTES;
        int bodyLength = (result[bodyLengthOffset] & BYTE_MASK) << Byte.SIZE | result[bodyLengthOffset + 1] & BYTE_MASK;
        int bodyEnd = Envelope.HEADER_LENGTH + bodyLength;

        System.arraycopy(result, 0, extended, 0, bodyEnd);
        System.arraycopy(result, bodyEnd, extended, bodyEnd + 1, result.length - bodyEnd);
        extended[bodyLengthOffset + 1] = (byte) (bodyLength + 1);

        Assertions.assertEquals(DecodeFailure.MALFORMED_BODY, reject(extended));
    }

    @Test
    void forged_and_changed_datagrams_fail_the_signature_check() {
        byte[] datagram = this.encoder.encodeRequest(new SignalMessage(this.sender.getPublicKey(), CONNECTION, new byte[]{1, 2, 3}), 1L);
        byte[] forgedSignature = datagram.clone();
        byte[] otherSender = datagram.clone();
        byte[] unsigned = datagram.clone();
        IdentityKey impostor = IdentityKeyPair.generate(new SecureRandom()).getPublicKey();

        forgedSignature[forgedSignature.length - 1] ^= 1;
        System.arraycopy(impostor.getBytes(), 0, otherSender, Envelope.HEADER_LENGTH - Short.BYTES - IdentityKey.LENGTH, IdentityKey.LENGTH);
        Arrays.fill(unsigned, unsigned.length - IdentityKey.SIGNATURE_LENGTH, unsigned.length, (byte) 0);

        Assertions.assertFalse(decode(forgedSignature).isSignatureValid());
        Assertions.assertFalse(decode(otherSender).isSignatureValid());
        Assertions.assertFalse(decode(unsigned).isSignatureValid());
    }

    @Test
    void cut_and_randomly_changed_datagrams_never_throw_and_never_verify() throws UnknownHostException {
        SplittableRandom random = new SplittableRandom(FUZZ_SEED);
        List<byte[]> datagrams = new ArrayList<>();

        for (Message message : this.createRequests()) {
            datagrams.add(this.encoder.encodeRequest(message, 1L));
        }

        for (byte[] datagram : datagrams) {
            for (int length = 0; length < datagram.length; length++) {
                Assertions.assertNull(Envelope.decode(datagram, length).getEnvelope(), "length " + length);
            }
        }

        for (int round = 0; round < FUZZ_ROUNDS; round++) {
            byte[] changed = datagrams.get(random.nextInt(datagrams.size())).clone();
            int changes = 1 + random.nextInt(3);

            for (int change = 0; change < changes; change++) {
                changed[random.nextInt(changed.length)] ^= (byte) (1 + random.nextInt(BYTE_MASK));
            }

            Envelope envelope = Envelope.decode(changed, changed.length).getEnvelope();

            Assertions.assertTrue(envelope == null || !envelope.isSignatureValid(), "round " + round);
        }
    }

    @Test
    void changed_bodies_of_every_kind_and_record_never_throw_and_encode_again() throws UnknownHostException {
        SplittableRandom random = new SplittableRandom(FUZZ_SEED);
        List<byte[]> datagrams = new ArrayList<>();
        int decoded = 0;
        int verified = 0;

        for (Message message : this.createRequests()) {
            datagrams.add(this.encoder.encodeRequest(message, 1L));
        }

        for (Message message : this.createReplies()) {
            datagrams.add(this.encoder.encodeReply(message, 1L));
        }

        for (int round = 0; round < BODY_FUZZ_ROUNDS; round++) {
            byte[] changed = datagrams.get(random.nextInt(datagrams.size())).clone();
            int bodyLength = (changed[Envelope.HEADER_LENGTH - Short.BYTES] & BYTE_MASK) << Byte.SIZE |
                             changed[Envelope.HEADER_LENGTH - 1] & BYTE_MASK;
            int changes = 1 + random.nextInt(MAXIMUM_BODY_CHANGES);

            // Changes inside the body only, so that the body readers see every field out of range: single bits and boundary values.
            for (int change = 0; change < changes && bodyLength > 0; change++) {
                int index = Envelope.HEADER_LENGTH + random.nextInt(bodyLength);

                changed[index] = random.nextBoolean() ? (byte) (changed[index] ^ 1 << random.nextInt(Byte.SIZE))
                                                      : BOUNDARY_BYTES[random.nextInt(BOUNDARY_BYTES.length)];
            }

            Envelope envelope = Envelope.decode(changed, changed.length).getEnvelope();

            if (envelope == null) {
                continue;
            }

            decoded++;

            Message body = envelope.getBody();
            byte[] again = envelope.isReply() ? this.encoder.encodeReply(body, 1L) : this.encoder.encodeRequest(body, 1L);

            Assertions.assertNotNull(Envelope.decode(again, again.length).getEnvelope(), "round " + round + ": " + body.getKind());

            for (TopicRecord record : findRecords(body)) {
                Assertions.assertNotNull(record.getRecordId());

                if (record.isSignatureValid()) {
                    verified++;
                }
            }
        }

        for (int round = 0; round < BODY_FUZZ_ROUNDS; round++) {
            int length = Envelope.MINIMUM_DATAGRAM_LENGTH + random.nextInt(Envelope.MAXIMUM_DATAGRAM_LENGTH - Envelope.MINIMUM_DATAGRAM_LENGTH);
            byte[] noise = new byte[length];

            random.nextBytes(noise);
            System.arraycopy(Envelope.MAGIC, 0, noise, 0, Envelope.MAGIC.length);
            noise[VERSION_OFFSET] = (byte) Envelope.PROTOCOL_VERSION;
            noise[KIND_OFFSET]    = (byte) (1 + random.nextInt(MessageKind.values().length + 1));

            Assertions.assertNotNull(Envelope.decode(noise, noise.length));
        }

        LOGGER.info("Body fuzzing: {0} of {1} changed datagrams decoded, {2} records in them still verified", decoded, BODY_FUZZ_ROUNDS, verified);

        Assertions.assertTrue(decoded > BODY_FUZZ_ROUNDS / MAXIMUM_BODY_CHANGES / 2, "changed bodies that still decode: " + decoded);
    }
}
