# Beacon.Link protocol reference, protocol 1

**English** | [한국어](protocol.ko.md)

The wire and files of Beacon.Link: peer and client messages (`sc4j-beaconlink-protocol`), receiving rules (`sc4j-beaconlink-peer`, `MeshPeer`) and channels between participants (`sc4j-beaconlink`). Every SC4J runs a peer (launcher node, runtime embedded peer, headless node of the [operator guide](operator.md)); there is no central server. The Javadoc of the named classes is the normative detail.

Integers are big-endian and unsigned unless stated. A datagram is at most 1200 bytes. Texts are UTF-8 with a one-byte length; invalid UTF-8 is refused. An address is its family (1 byte, `4` or `6`), 4 or 16 address bytes and the port (2); no name is looked up while reading. Readers refuse truncated fields, out-of-range values and trailing bytes without throwing.

## Identities

- **Keys**: Ed25519 (JDK); the 32-byte public key is the identity. A new key is a new identity. The player key (`accounts/<id>/identity.key`, created by the launcher with the account) signs rooms, presences, chat lines, whispers and signals and is shown as `Name#abcd12` (name and the first 6 hex digits of SHA-256 of the key, `IdentityKey.getTag`). A peer key is for routing only and never shown: the launcher node's `peer.key`, the headless node's `<state>/identity.key`, a runtime run's embedded peer key in memory; it is created at first use.
- **Key file** (`IdentityKeyFileUtils`): magic `BLKF`, version `1`, private key bytes, public key bytes; written atomically through a temporary file of the folder (on POSIX `rw-------`, kept by the replaced file).
- **Connections**: each client session draws a random connection number (8 bytes) for its life; the epoch millisecond of its start is its connection start. A participant (`LinkParticipant`, wire form key 32 + number 8) is player key + connection number. Registrations, rooms, signals, channels, the overlay, the lobby and the lockstep transport address participants, so connections of one account work side by side, even with each other. Friends, presences, chat lines, whispers and the ignore list are per player (`LinkPlayer`). Registering any connection needs the player key and its stamp.
- **Player names** (`PlayerNameUtils`): Unicode NFC, no white space around, inner white space runs made one space; 1 to 32 UTF-8 bytes; no `#`, control, format, private-use, surrogate or unassigned character. A valid name is its own normalized form. The stamp binds the name to the key, so another name is another account.
- **Accounts** (`AccountStore`, `Account`): one folder per account, named by its id (first 16 hex digits of SHA-256 of the key), with key, name, player stamp, friends and ignored players. The user creates (name, random key, stamp) or imports an account and chooses the one a game runs with (`--account <dir>`). No rename. Every process using an account holds a shared lock of its lock file (`AccountLock`); deleting takes the exclusive lock. A run without an account is the guest `Guest`, with key and stamp in memory.
- **Account backup** (`AccountBackup`, `.sc4jaccount`): magic `BLKA`, version `1`, flags (bit 0: password), for a protected backup the salt (16), PBKDF2-HMAC-SHA256 iteration count (4) and AES-GCM nonce (12), then the body (name, stamp difficulty (1), stamp nonce (8), key file, friends, ignored players), encrypted with AES-256-GCM under the password's key with the header as associated data when protected. A backup whose stamp does not bind its name to its key, and any other file, is refused.
- **Game command line**: a run takes Beacon.Link only from `--account <dir>` (absent: guest), `--seeds <file>` (node list), `--seed <host:port>` (repeatable) and `--peers <file>` (shared peers file; absent: learned peers neither read nor kept), and offers it only with one of the last three. The launcher passes the account folder, the seeds by the seed rule (the user's `nodes.json` once saved, otherwise the shipped `seeds.json`) and the install's `peers.json`. A run's embedded peer is a leaf. The launcher's node is the computer's long-lived peer: the first launcher of an install holds `node.lock` and runs it with `peer.key`; a further launcher takes over after the first closes.
- **Peer ID** (`PeerId`): SHA-256 of `BLNK peer id 1`, the peer key and its proof nonce (8). The nonce is valid when SHA-256 of `BLNK peer proof 1`, the key and the nonce starts with 20 zero bits (the network's difficulty); a peer finds its smallest valid nonce at each start. Receivers check the proof before using an ID. IDs are compared by XOR distance (Kademlia).
- **Player stamp** (`PlayerStamp`): a nonce for which SHA-256 of `BLNK player stamp 1`, the player key, the name's UTF-8 length (1), the name and the nonce (8) starts with 24 zero bits, for a valid name; found once when the launcher creates the account, kept in `account.json`. Registrations, presences and chat lines carry name and nonce; a name the stamp does not bind is refused.

## Roles

- **Full peer**: its self-test proved that strangers reach its socket. Only full peers enter routing tables, `PEERS` answers, the storing peers of topics and `peers.json`; they store topic announcements and form the topic meshes.
- **Leaf**: self-test `NOT_VERIFIED` or `NOT_REACHABLE`. It looks up, greets and answers like any peer, is known outside routing tables, and promotes itself when a later test proves it.
- **Leaf-only** (`PeerConfiguration.isLeafOnly`): never a full peer; its cards always say not reachable and it never asks for a dial-back, but it serves others' self-tests as helper and third peer. The launcher's node in the off role.
- **Home peer**: a peer where a client connection registered (`REGISTER`; up to 3, the client's first registered nodes). It reports the observed address, takes part in the client's topics, pushes their records, and relays signals and whispers to its registered connections. A runtime session is a client of its seeds and learned nodes with an embedded leaf on the same socket; the launcher's node and the headless node run alone on their own socket (`PeerSocket`, `PeerBootstrap.run`).
- **Participant**: one connection of a player in a room. Game traffic flows only on channels between participants.

## Envelope (`Envelope`, `MessageEncoder`)

| Field | Bytes | Meaning |
|---|---|---|
| magic | 4 | `BLNK` |
| version | 1 | `1` |
| kind | 1 | message kind code (below) |
| flags | 1 | bit 0: reply (a reply is never answered); other bits zero |
| request number | 4 | chosen by the requester; a reply repeats it |
| timestamp | 8 | sender wall clock, epoch milliseconds (signed 64-bit) |
| nonce | 8 | random per message, also per resend |
| sender key | 32 | Ed25519 public key of the sender |
| body length | 2 | |
| body | n | kind-specific |
| padding length | 2 | |
| padding | p | zero bytes |
| signature | 64 | Ed25519 signature of the sender over every byte before it |

The header is 61 bytes, the trailer `2 + p + 64`. An envelope does not name its receiver, so a signed datagram is valid at any peer: the nonce check comes before the key rate, and penalties graylist a key or an address alone only for a proved address ([Receiving](#receiving)).

**Request numbers**: random in the peer layer (never one still waiting); counted up from a random start by the topic layer, the self-test and clients. A reply is accepted only with a waiting number, from the asked address and, when known, with the expected key.

**Padding**: requests to an unproved address (`PING`, `REGISTER`, `HELLO`, `DIAL_BACK`) are padded to 256 bytes, `FIND_PEERS` to 617 (the longest `PEERS`), `GET_TOPIC` to 1155 (the longest `TOPIC_PEERS`). A peer never answers an unproved address with more bytes than the request had; a longer answer is not sent (`UNPADDED`), refusals included (`NOT_REGISTERED` to `PUBLISH_ROOM`, `LIST_ROOMS`, `SIGNAL`, `PUBLISH_RECORD`, `RECENT`; `INVALID` to `ANNOUNCE`, `GRAFT`).

## Receiving

### At a peer (`MeshPeer.receive`)

Checks in this order; a failure is dropped, counted and never answered:

1. length at most 1200 bytes (`OVERSIZE`);
2. source IP rate: burst 256, then 128 per second (`ADDRESS_RATE`);
3. address graylist (`GRAYLISTED`);
4. envelope and body structure (`MALFORMED`); client-only replies `OBSERVED_ADDRESS`, `ROOM_LIST` (`UNEXPECTED_REPLY`); own key (`OWN_KEY`);
5. timestamp within 30 s of the receiver's clock (`OUTSIDE_WINDOW`);
6. signature (`FORGED`);
7. graylist of keys and of keys at addresses (`GRAYLISTED`);
8. nonce: (sender key, nonce) remembered for twice the window, 65536 in all, at most 2048 per source IP; a full cache refuses new messages (`REPLAYED`, `REPLAY_SOURCE_FULL`, `REPLAY_CACHE_FULL`);
9. sender key rate: burst 32, then 16 per second (`KEY_RATE`), charged only for fresh, validly signed messages.

Handlers count what they do not use: `UNPADDED`, `NOT_A_PEER` (a peer message not from a peer known at its address, or a reply to no waiting request), `INVALID_PROOF`, `INVALID_ENTRY` (per unused `PEERS` or `TOPIC_PEERS` entry), `UNREQUESTED_PROBE`, `NOT_IN_TOPIC`, `INVALID_RECORD` (per record), `COORDINATE_OUTLIER` (per sample). A handler that throws loses the datagram, logs the exception and counts `DEFECT`. The counts are logged at stop (and hourly by the headless node).

### Penalties and graylist (`Graylist`)

| Penalty | Points |
|---|---|
| an invalid record: another topic or a kind that does not belong there, a bad signature or stamp, a too long lifetime | 10 |
| a card with a forged proof | 10 |
| a record over its author's rate | 2 |
| an expired record; a topic message from a peer outside the topic's mesh; a reply to no request; an unpadded request; a coordinate sample the settled coordinate rejects | 1 |

Points count per key and source address and are forgotten 60 s after the last penalty. At 40 points that key at that address is graylisted for 10 minutes. When the address is proved for the key (a mesh neighbour grafted with its token, a routing contact that answered there, a registered client), the key alone and the address alone (IP address, all ports; on loopback the socket address) are graylisted too: their datagrams are dropped before (address) or right after (key) the signature check, and the peer leaves every mesh, member list and stored announcement. Penalties and the graylist are local.

### At a client (`BeaconLinkClient`)

The session socket carries channel datagrams (magic `BLCH`) and envelopes. With an embedded peer, the session keeps an envelope only if it is a reply to a waiting session request (from that node) or to one of its whispers, a signal or whisper for the session's key from one of its nodes, or a node's push for a topic the peer does not take part in; the peer gets the rest. The session takes:

- a reply: waiting number, from the asked node, inside the window, validly signed, with the node's key (pinned, or that of the node's first answer in the session; another key marks the node as answering with the wrong key), fresh nonce;
- a signal: from a node it is registered at, for its own key and connection, not from its own participant, inside the window, validly signed; only signals of channels it has or waits for and joins of rooms it holds are acted on and remembered against replay (per sender key);
- a push (`GOSSIP`, `IHAVE`, `SEALED`): from a node it is registered at, with the node's key (a whisper: its sender's key), inside the window, validly signed; records are checked as a relay checks them; a whisper's nonce is remembered under its sender.

Failures are dropped and counted per source; a defect is logged and the poll goes on.

## Message kinds (`MessageKind`)

| Code | Kind | Direction | Body |
|---|---|---|---|
| 1 | `REGISTER` | client -> home peer | token (length byte, at most 32 bytes), lifetime seconds (2; `0` removes the registration of the connection), display name (text, at most 32 bytes), stamp nonce (8), mod fingerprint, connection number (8), connection start (epoch ms, 8), flags (1, all zero) |
| 2 | `OBSERVED_ADDRESS` | reply to `REGISTER` | status (1, below), observed address, granted lifetime seconds (2), token (length byte + bytes; with `TOKEN_REQUIRED`), the peer's coordinate report (17) |
| 3 | `PUBLISH_ROOM` | client -> home peer | a room advertisement without the withdrawn flag |
| 4 | `LIST_ROOMS` | client -> home peer | mod topic key (33), page offset (2) |
| 5 | `ROOM_LIST` | reply to `LIST_ROOMS` | total rooms of the topic (2), page offset (2), count (1) and advertisements |
| 6 | `WITHDRAW_ROOM` | client -> home peer | a room advertisement with the withdrawn flag |
| 7 | `SIGNAL` | client -> home peer -> client | target key (32), target connection (8), payload (two-byte length, at most 512 bytes); the home peer forwards the whole datagram unchanged to the registration of that connection only |
| 8 | `PING` | anyone -> peer; padded | empty |
| 9 | `HELLO` | peer <-> peer, request and reply; requests padded | card (proof nonce 8, flags 1: bit 0 reachable, coordinate report 17), capabilities (2, bit set: `1` answers `FIND_PEERS`, `2` takes part in topics; unknown bits ignored), software version (text, at most 32 bytes) |
| 10 | `FIND_PEERS` | peer -> peer, client -> peer; padded to 617 bytes | target ID (32), card flag (1: `0` none (a client), `1` a card follows) and the sender's card |
| 12 | `RESULT` | reply to `PUBLISH_ROOM`, `WITHDRAW_ROOM`, `LIST_ROOMS` (refusal), `SIGNAL`, `ANNOUNCE`, `GRAFT`, `PUBLISH_RECORD`, `RECENT`, `SEALED` | status (1, below), value (8: the lifetime in seconds of an accepted announcement or subscription, else `0`) |
| 13 | `PONG` | reply to `PING` | observed address, peer software version (text, at most 32 bytes) |
| 14 | `DIAL_BACK` | peer -> known peer -> third known peer, request and reply; the requester's request padded | the sender's card, nonce (8), address flag (1) and address: none in the requester's request and in a refusing reply, the requester's observed address in the forwarded request and in an accepting reply |
| 15 | `PROBE` | third peer -> requester's observed address | nonce (8); never answered |
| 16 | `PEERS` | reply to `FIND_PEERS` | the answering peer's coordinate report (17), count (1, at most 8) and entries: key (32), proof nonce (8), address; the full peers of the answering peer's routing table closest to the target, the asker left out |
| 17 | `GET_TOPIC` | peer -> storing peer or member; padded to 1155 bytes | topic key (33) |
| 18 | `TOPIC_PEERS` | reply to `GET_TOPIC` | topic key (33), address token (length byte, at most 32 bytes), member flag (1: the answering peer is in the topic), count (1, at most 16) and entries: key (32), proof nonce (8), address, reachable flag (1) |
| 19 | `ANNOUNCE` | member -> storing peer | topic key (33), the sender's card, the storing peer's token for the sender |
| 20 | `GRAFT` | member -> member | topic key (33), the sender's card, the other member's token for the sender |
| 21 | `PRUNE` | member -> mesh neighbour | topic key (33); never answered |
| 22 | `GOSSIP` | member -> mesh neighbour, answer to `IWANT`, or home peer -> subscribed client | topic key (33), count (1) and records, each its record kind (1: `1` room, `2` presence, `3` chat line, `4` sign-out) and its wire form; never answered |
| 23 | `IHAVE` | member -> mesh neighbour or member, home peer -> subscribed client | topic key (33), count (1, 0 to 32) and record IDs (16 each); none keeps a mesh link alive; never answered |
| 24 | `IWANT` | member -> sender of an `IHAVE`, subscribed client -> home peer | topic key (33), count (1, 1 to 32) and record IDs (16 each); answered with `GOSSIP` |
| 25 | `PUBLISH_RECORD` | client -> home peer | one record with its kind code: the client's own presence, chat line or sign-out |
| 26 | `RECENT` | client -> home peer | topic key (33), flags (1: bit 0 backlog); subscribes the registered client to the topic (or renews it) |
| 27 | `SEALED` | player -> target connection's home peer -> target connection | target key (32), target connection (8), the sender's ephemeral X25519 key (32), sealed text (two-byte length, at most 512 bytes); the home peer forwards the whole datagram unchanged to the registration of that connection only |

`OBSERVED_ADDRESS` status (`ObservedAddressStatus`): `2` TOKEN_REQUIRED, `3` REGISTERED, `4` UNREGISTERED, `5` FULL, `6` TOO_MANY_CONNECTIONS, `7` INVALID_NAME, `8` SIGNED_OUT (code `1` is not used).

`RESULT` status (`ResultStatus`): `1` ACCEPTED, `2` STALE (an equal or newer sequence is stored), `3` INVALID (also a bad token or a graft for a topic the peer is not in), `4` NOT_REGISTERED, `5` LIMIT_REACHED (also a host over its record rate, a full mesh, the most topics), `6` DELIVERED, `7` UNKNOWN_TARGET. Message kind code `11` is not used.

## Registration and observed address

- The first `REGISTER` from an address carries no token; the answer is `TOKEN_REQUIRED` with a 16-byte token (HMAC-SHA256 of address, port and key under a per-run secret, valid for two 60 s buckets). The `REGISTER` with the token registers the connection at its observed address (at most 4096 registrations, at most 300 s). A refresh from the registered address needs no token.
- A proved `REGISTER` or refresh whose display name and stamp nonce are no valid stamp of the key at the peer's difficulty is refused with `INVALID_NAME`.
- Only registered connections publish, list, subscribe and signal (`FIND_PEERS` and `SEALED` are answered to anybody within the length rule); large answers go only to the registered address. Clients refresh at half the granted lifetime; a NAT remap shows as `TOKEN_REQUIRED` and the client registers again.
- **Connections** (`RegistrationStore`): one registration per (key, connection number), at most 8 live per key; a further connection gets `TOO_MANY_CONNECTIONS` (an expired one yields first), so a crashed session's place frees within 300 s. The same connection at a new address moves; a connection registering at the address of another connection of its key replaces it (one address is one socket). Lifetime `0` from the registered address removes only that connection.
- A request that needs a registration is taken from the address of any registered connection of its sender key and acts for that connection: a published room must name it (`INVALID` otherwise), subscriptions are per connection, a signal or whisper names its target connection, which alone gets it.
- `REGISTER` carries the connection start. A peer holding a sign-out of the key ([Community](#community-presencerecord-chatlinerecord-signoutrecord-sealedmessage)) refuses with `SIGNED_OUT` every connection but the kept one that started before the sign-out's timestamp, removes such registrations, and drops them all when it takes the sign-out in; the client then registers nowhere again.
- A client asks its nodes for more nodes with a padded `FIND_PEERS` of a random target (no card) and records them in the shared peers file ([Files](#files)): every node once after its first registration, and a listed node again while the client knows fewer than 8 learned peers, 30 s after its last answer with the interval doubling, at most 8 times per session.

## Peer layer (`PeerRouting`, `RoutingTable`, `PeerLookup`)

**Proof**: a peer greets seeds, candidates (senders of hello or lookup requests at unproved addresses), remembered peers and quiet known peers with padded `HELLO` requests, two per 5 s round. An answer at the greeted address proves address and key; so does an answer to `FIND_PEERS`. A seed without a key trusts the first validly signed answer from its address carrying the waiting random request number; a seed with a pinned key is greeted as a known peer. Every card and `PEERS` entry proof is checked; an entry is used only with an IPv4 address of the same kind as the answering peer's (public, or local network and loopback).

**Routing table**: Kademlia k-buckets by common ID prefix, 8 peers per bucket, a replacement cache of 4 proved peers per bucket; only peers whose card says reachable enter. Limits: 2 of one IPv4 /24 (IPv6 /48) per bucket, 8 of one /24 per table, 16 of one IPv4 /16 (IPv6 /32) per table; loopback unlimited. A full bucket keeps its peers; a peer leaves after 2 unanswered requests in a row (lookups, or hellos after 10 minutes of silence) and the newest cached peer within the limits replaces it. Proved leaves and full peers without room are known outside the table (at most 256, dropped after 60 s of silence): the self-test uses them, and the topic layer while the table is empty; they are never answered to `FIND_PEERS` nor remembered.

**Lookups**: iterative, 3 requests in flight per path, until the path's 8 closest peers answered or failed (2 s per request with one resend, 30 s per lookup, at most 8 lookups at once). An owner lookup (`MeshPeer.findPeers`) uses 3 disjoint paths by default: start peers are dealt in turn, a named peer joins only the asking path unless another path knows it, a path takes at most 2 peers of one /16. A peer looks up its own ID once its table holds a peer and its first self-test settled, again when the self-test proves it reachable (the reachable flag of its requests makes the asked peers take it in), every 30 s while its table holds fewer than 8 peers, and otherwise refreshes a random bucket every 10 minutes.

**Bootstrap** (`PeerBootstrap`, `KnownNodes`): the seeds (seed rule; the node's `--peer` options; a run's `--seeds` and `--seed`) and the 32 best learned peers (`peers.json`: most recent success first, then longest known, at most 2 per /24 before the others) are greeted together; the first answer connects. The routing table's full peers are merged into `peers.json` every 5 minutes and at stop; a runtime session records the nodes that register it, its embedded peer's full peers and the nodes its listed nodes report. A learned peer is dropped after 3 starts in a row tried without answer, when another key answers at its address, or 30 days after its last success.

## Reachability self-test (`ReachabilityCheck`)

- A peer asks up to two random known peers (helpers) for a `DIAL_BACK`, padded like a ping. A helper serves only a requester with a valid card proof: it forwards the request with the requester's observed address to a random third known peer and answers with that address, or answers without one when it knows no third peer.
- The third peer serves only a helper known at its proved address and sends a `PROBE` to the named address (at most 4 at once, then 1 per second per IP address).
- The requester is reachable (a full peer) when a probe of its waiting dial-back, or any padded `HELLO` request, arrives from an address its socket never sent to (by IP; on loopback by port; up to 4096 addresses remembered 10 minutes). An accepted dial-back without a probe within 10 s makes it a leaf until a later test or an unrequested hello.
- Reachable is kept; other results are retested 5 minutes after an accepted test, 30 s after no helper accepted or the probe came from an address the peer had sent to. A leaf-only peer never asks.
- With a spoofed source, one request makes two hosts send to a victim (about 1.1 times its bytes).

## Topics (`TopicKey`, `TopicSwarms`, `TopicDirectory`, `Swarm`)

**Topic key**: kind (1: `1` MOD, `2` CHANNEL, `3` PLAYER) and SHA-256 of `BLNK topic 1\0`, the kind code and the UTF-8 name: a mod's id (all versions share it); a channel's name with white space cleaned, lower case, at most 32 bytes (shared by every mod); a player's key in hex. The full peers closest to the hash are the storing peers. A peer takes part in a topic while its owner or a registered client uses it (a room or record published through it, a listing, a subscription; a `LIST_ROOMS` for a new topic waits up to 2 s for the first joining) and leaves 10 minutes after the last use unless it holds an unexpired record published through it or a subscribed client; at most 256 topics.

**Joining**: look up the storing peers over 3 disjoint paths (with an empty table, ask known peers), `GET_TOPIC` each, `ANNOUNCE` with the answer's token. A storing peer keeps an announcement 10 minutes, at most 64 members per topic (a new member replaces the older of two random ones) and 2048 topics; members announce again every 4 minutes, every 30 s while their mesh is empty. Storing peers answer `GET_TOPIC` from announcements, members from the members they know: at most 16, reachable first. The token (16 bytes, as for `REGISTER`) proves the asker's address.

**Mesh**: a member grafts members up to 6 mesh neighbours (reachable first; `GET_TOPIC` first for one it has no token of), grafts more below 4, prunes to 6 above 12 (those that grafted it first). At least 2 neighbours are its own grafts: it accepts others' grafts up to 10 and grafts while it has fewer than 2 own; refused grafts go to other members. A neighbour silent for 60 s is dropped; a link idle for 30 s gets an empty `IHAVE`, which keeps a leaf's NAT mappings open.

**Gossip**: a new record goes at once to every mesh neighbour and subscribed client (`GOSSIP`). Every 5 s round, the IDs taken in since the last round go to the neighbours and 6 other reachable members, and the IDs of the last two rounds to subscribed clients (`IHAVE`), who ask with `IWANT`; a new neighbour gets every ID. A record ID is the first 16 bytes of SHA-256 of `BLNK record 1\0` and the record's wire form.

**Record checks**, in order:

1. only from mesh neighbours or an asked member;
2. record ID not seen before;
3. topic and kind: rooms in mod topics, chat lines in channel topics, a presence in a player topic only from that player, a sign-out only in its player's own topic;
4. sequence (stale dropped);
5. signature;
6. author rate per kind: rooms burst 8, then 1/s; chat lines burst 5, then 1/s, and lines dated more than 5 s ago (a backlog) from a history allowance of 32, then 1/s; presences and sign-outs together burst 16, then 1 per 5 s;
7. the author's stamp of the display name (presences, chat lines);
8. expiry and lifetime: room store 512 per topic, 8 per host key and 4 per host connection, at most 600 s ahead; community store 256 presences, newest per connection, at most 8 connections per player (a further one takes the place of the player's connection that expires first, if that one expires earlier), at most 300 s ahead; the 32 newest chat lines, dated at most 30 s ahead, kept 5 minutes; the newest sign-out per player, at most 300 s ahead and dated at most 30 s ahead.

Every failure is counted and penalized.

## Rooms (`RoomAdvertisement`, `RoomDescription`, `RoomKey`)

**Advertisement** (record kind 1, in its mod topic, signed by the host): flags (1, bit 0 withdrawn), topic key (33), host key (32), host connection (8), room number (4), sequence (4), expiry (epoch ms, 8), description, home peers (count 1, at most 3; key 32 + address), settled network coordinate (flag 1; `1`: 16 bytes follow; none on a withdrawal), the host's Ed25519 signature over `Beacon.Link room advertisement 1\0` and every byte before it. At most 545 bytes plus home peers and coordinate (716 with three IPv6 entries).

**Description**: mod fingerprint (length byte, 1 to 192 bytes: the mod identity's wire form, naming mod id, version and engine version), room name and map name (text, at most 48 bytes each, room name not empty), players and capacity (1 each; players at most capacity, capacity at least 1), room flags (1: bit 0 private, bit 1 started, other bits zero), join key (32, private rooms only), mod data (length byte, at most 64 bytes, and the bytes).

**Mod data** (`RoomSettings.getModData`): defined and documented by the mod, carried unread by peers and clients. The demo publishes one byte, the turn rate (`DemoLobby`).

**Room key** (`RoomKey`): host key + host connection + room number. A higher sequence replaces a lower one; a withdrawal stays until its expiry. Hosts refresh every 40 s with a 120 s expiry. Peers check topic, signature, expiry and host rate and list a topic's rooms. A client lists the rooms of its own fingerprint apart from the mod's other versions (with the refusal their hosts would give) and shows each room's mod id and version. A room is joinable with the client's mod identity, players below capacity and the game not started; others are closed.

**Private rooms**: the advertisement carries the public half of an X25519 join key pair; the password is never sent. The joiner's offer ([Signals](#signals-channelsignal)) carries proof = HMAC-SHA256(key = X25519 secret of the offer's ephemeral key and the join key, `SC4J Beacon.Link room password 1` || host key || room number (4) || joiner key || password UTF-8). The host agrees the secret with the private half and refuses a wrong proof (`WRONG_PASSWORD`). Relaying nodes know neither secret nor password; a proof fits its offer only. A joiner that shares no node with the host registers at the host's home peers (neither learned nor remembered).

## Community (`PresenceRecord`, `ChatLineRecord`, `SignOutRecord`, `SealedMessage`)

**Presence** (record kind 2): flags (1, zero), topic key (33), player key (32), connection number (8), stamp nonce (8), sequence (4), expiry (8), display name (text, at most 32; bound by the stamp), mod id (text, at most 48; the launcher's is `sc4j-launcher`, `BeaconLink.LAUNCHER_MOD_ID`), mod version (text, at most 24), first 8 bytes of SHA-256 of the mod fingerprint (equal for players who can play together), status (1: `1` available, `2` away, `3` in a room, `4` in a game), away text (text, at most 32, empty unless away), channel name (text, at most 32, empty for none), X25519 whisper key (32, per session), home peers (as in a room), settled network coordinate (as in a room; renewed only with the 50 s refresh), signature over `Beacon.Link presence 1\0` and every byte before it.

- Every connection publishes its own presence into its mod topic, every joined channel and the player's own topic; it refreshes every 50 s with a 120 s lifetime, signs again at once on change, and sends a presence expiring in 5 s when it leaves a channel or closes. An appear-offline connection publishes none (there is no account-wide setting).
- Stores and clients keep the newest presence per connection (key and connection number).
- **Merged presence** (`ClientCommunity`): a player is online while any connection has a live presence in a topic the viewer follows. The highest-ranked connection gives state, mod and away text: in a game, in a room, online in a mod (available, not the launcher), in the launcher (available with mod id `sc4j-launcher`), away. The live connection count is the device count. User lists show each player once.
- **Own player topic**: every connection subscribes to it and so sees the account's other connections and sign-outs.

**Chat line** (record kind 3, channel topics only): flags (1, bit 0 emote), topic key (33), author key (32), stamp nonce (8), author sequence (4), timestamp (8), display name (text, at most 32), text (1 to 255 bytes), signature over `Beacon.Link chat line 1\0` and every byte before it. Expires 5 minutes after its timestamp.

**Home peers in topics**: a client's first registered nodes (at most 3) take part in its topics.

- `PUBLISH_RECORD` (a registered client's own record) puts a record into its topic. `RECENT` subscribes for 90 s (the `RESULT` value; at most 256 subscribers per topic), renewed at a third; with the backlog flag the peer first pushes the presences, chat backlog and sign-outs it holds.
- The peer pushes new records (`GOSSIP`) and every round the IDs of the last two rounds (`IHAVE`); the subscriber fetches missed ones with `IWANT`. Clients hold no links to other clients for chat.
- A line refused with `NOT_REGISTERED` is held (at most 8 per topic and home peer, the oldest gives way) and published again in order once the peer registered the client anew.
- Nothing on a line's path waits for a round or a poll interval.
- A client checks pushed records as a relay does (topic, kind, expiry, stamp, signature), drops seen record IDs and trusts its home peers for author rates.

**Whisper** (`SEALED`):

- The sender seals one copy per live connection of the target, using that connection's newest presence: X25519 agreement of a fresh key pair with the whisper key, a one-time key by HKDF-SHA256 (salt SHA-256 of `SC4J Beacon.Link whisper 1`, both player keys and both X25519 keys), ChaCha20-Poly1305 over timestamp (8) and text (at most 255) with a zero nonce and the two player keys as associated data.
- Each signed copy names its target connection and goes to each home peer of that connection, resent every second up to 4 times until delivered or refused by all. A home peer holding that registration forwards it unchanged to it alone and answers `DELIVERED`, otherwise `UNKNOWN_TARGET`.
- The whisper is delivered when one copy is (the others are still sent), and not online when every copy was refused or ran out of attempts.
- Anyone may send one (address and key rates bound it); the answer is never longer than the request. Home peers see sender, target and size only; nothing is stored for an offline target.

**Sign out everywhere else** (record kind 4, `SignOutRecord`, `BeaconLinkSession.signOutOtherDevices`): flags (1, zero), the player's own topic key (33), player key (32), kept connection (8, the signer), sequence (4), timestamp (8, epoch ms), expiry (8, 240 s after the timestamp; peers take at most 300 s ahead), signature over `Beacon.Link sign out 1\0` and every byte before it; no stamp.

- The signer publishes it in the player's own topic through its home peers (`PUBLISH_RECORD`; a home peer that registers it later gets it too).
- A connection that gets a valid sign-out of its player naming another connection, timestamped after its own connection start, ends with `LinkFailure.SIGNED_OUT`: it closes its channels, withdraws its rooms, sends its leaving presences, unregisters and registers nowhere again. Nodes holding the sign-out refuse such connections at their next refresh (within 150 s) while it lives. Connections started after the timestamp are not affected.
- Every client following that player topic drops at once the presences of the ended connections (all but the kept one whose presence sequence, a second, is at most the timestamp's second), and their later presences while the sign-out lives.
- It ends sessions; it does not revoke a stolen key (that needs a new account).

**Ignore list** (`CommunitySession.ignore`, `getIgnored`): the account's `blocked.json` ([Files](#files); a guest's in memory). Ignored players' lines and whispers are not handed out, mods hide their rooms; nothing goes on the network.

## Network coordinates (`NetworkCoordinate`, `CoordinateReport`, `CoordinateEstimator`)

Vivaldi with 3 dimensions and a height: the estimated round trip time of two hosts is the distance of their points plus both heights.

- **Coordinate**: 16 bytes, three components and the height, each a signed 32-bit count of whole milliseconds (components within 10,000 ms of the origin, height 0 to 10,000 ms; anything else is refused, the smallest int included).
- **Report**: coordinate and error (1 byte, hundredths, at most 150; 1.5 for a host that knows nothing). Cards (`HELLO`, `FIND_PEERS`, `DIAL_BACK`, `ANNOUNCE`, `GRAFT`), `PEERS` and `OBSERVED_ADDRESS` carry the sender's report; nothing is sent for coordinates alone.
- **Samples**: a peer times its `HELLO` requests (to candidates and quiet peers) and lookup requests, with the answer's report; a client times each answered node request that was not resent, with the node's report from its last `OBSERVED_ADDRESS` or `PEERS`. A runtime session and its embedded peer share one estimator.
- **Update**: a sample moves the coordinate by 0.25 times the own error's share of both errors times the difference of measured and estimated time; the error follows the sample's relative error with the same weight. Height at least 0.1 ms; samples over 5 s ignored; a weak pull toward the origin. At most one sample per remote host every 5 minutes (the last 64 remembered).
- **Settled**: after samples of 6 different hosts with an error of at most 0.6. Only settled coordinates go into rooms and presences; `LatencyEstimate` (good up to 100 ms, fair up to 200 ms, poor above) is unknown while either side has none.
- **Outliers**: a settled coordinate rejects samples whose measured and estimated times differ by more than a factor of 4 (a peer counts `COORDINATE_OUTLIER` and 1 penalty point) and takes every 8th of a run of them, which raises the error of a host that moved until it is no longer settled.
- **Privacy**: a coordinate tells a whole millisecond of latency space and no address; appear-offline players publish none; a room's coordinate is the host's.

## Channels between participants (`sc4j-beaconlink`, package `channel`)

### Signals (`ChannelSignal`)

The `SIGNAL` payload is kind (1), the sender's connection number (8) and fields. The signed sender key with that connection is the sending participant; the `SIGNAL` names the target key and connection, so a join names the room by the host connection it goes to and the room number of its offer. Address candidates are a count (1, at most 8) and addresses: the client's port mapping on its router first, when it holds one, then the addresses its nodes observed, then its local addresses.

| Kind | Fields after the sender's connection |
|---|---|
| `1` OFFER | initiator's receive number (8), X25519 ephemeral public key (32), join flag (1), with the flag `1`: room number (4), the joiner's mod identity (length byte and bytes) and the password proof of a private room (length byte `0` or `32`, and the bytes; section [Rooms](#rooms-roomadvertisement-roomdescription-roomkey)); then the candidates |
| `2` ANSWER | initiator's receive number (8), responder's receive number (8), ephemeral public key (32), candidates |
| `3` REFUSAL | initiator's receive number (8), reason (1): `0` the host does not hold the room, otherwise the code of the engine `JoinRefusal` (engine version, mod identity, full room, `9` wrong password) |

Both ephemeral keys and receive numbers are authenticated by both identities. The participant with the lower key offers; of two connections of one account, the lower connection number (both unsigned); a joiner always offers. A host keeps at most 16 unconnected joins, the newest replacing the oldest.

### Key schedule (`ChannelKeys`)

- transcript = SHA-256(`SC4J Beacon.Link channel 1` || initiator identity || initiator connection || responder identity || responder connection || initiator ephemeral || responder ephemeral || initiator receive number || responder receive number)
- PRK = HMAC-SHA256(key = transcript, X25519 secret)
- key(initiator -> responder) = HMAC-SHA256(PRK, `initiator to responder` || 0x01)
- key(responder -> initiator) = HMAC-SHA256(PRK, `responder to initiator` || 0x01)

An all-zero secret is refused; secrets are zeroed after use.

### Datagrams (`ChannelDatagramUtils`, `ChannelCipher`, `ChannelFrame`)

`BLCH` (4) | receiver's receive number (8) | counter (8) | ChaCha20-Poly1305 ciphertext of the frame | tag (16); at most 1200 bytes. Associated data: the 20 header bytes; nonce: 4 zero bytes and the counter. Counters start at 0 per direction; a 256-counter replay window is checked before decryption and recorded after authentication. Channel datagrams share the client's UDP socket with envelopes, told apart by the magic.

**Frame types** (`ChannelFrameType`): `1` PROBE, `2` PROBE_ACK (hole punching), `3` DATA (at most 1024 bytes), `4` PING, `5` PONG (keepalive every 2.5 s, delay), `6` CLOSE, `7` RELAY_QUERY, `8` RELAY_STATUS, `9` RELAY_FORWARD, `10` RELAY_DELIVER; a relay frame names the other participant (wire form, 40 bytes). A pair without a direct path relays through the participant with direct channels to both and the lowest measured delay: A sends RELAY_FORWARD(B, sealed A->B datagram) to C, C sends RELAY_DELIVER(A, same bytes) to B. The relay cannot read or change it and passes at most a budget per pair. Peers never relay channel datagrams of games they are not part of.

### Overlay (`LinkOverlay`, `ChannelOverlay`)

An overlay datagram is a port byte and at most 1023 bytes, addressed by participant. Ports 1 to 15 are the engine's: `1` engine lobby (its participant lists name each member's participant in wire form), `2` engine lockstep transport. Ports 16 to 255 (`LinkOverlay.FIRST_MOD_PORT`) are for a mod's own protocols, documented by the mod.

## Files

Shared files (`SharedFile`) are used by several processes at once (launcher node, games, a headless node in its own folder). A writer opens the file read-write, locks it exclusively, reads it, merges its changes, writes the result in place, truncates the rest, forces it to disk and unlocks; a reader takes a shared lock. No rename, temporary or backup file. A missing or empty file holds nothing; a damaged one holds nothing (one warning) and is rewritten by the next merge. Each process takes the merged result as its view.

| File | Content |
|---|---|
| `<install>/launcher/seeds.json` | shipped seed list, replaced per release; same format as `nodes.json` |
| `<install>/userdata/beaconlink/nodes.json` | the user's node list (`NodeListFile`); written when the user saves it and passed as `--seeds`; until it exists, `seeds.json` stands in. `{"format": 1, "nodes": [...]}` with `"host:port"` (any key) or `{"address": "host:port", "key": "<64 hex digits>"}` (pinned key); at most 64 KiB, nesting at most 8, IPv6 hosts in brackets |
| `<install>/userdata/beaconlink/node.json` | launcher Beacon.Link settings `{"format": 1, "role": "automatic", "port": 7455, "portMapping": true}`; `automatic` (listens on the port, full peer once its self-test passes) or `off` (leaf-only on a free port); `portMapping` (optional, default `true`): the router is asked to open the ports of the node and the games; missing: automatic on 7455 with port mapping |
| `<install>/userdata/beaconlink/peers.json`, a node's `<state>/peers.json` | learned peers (`PeerStore`, shared), below |
| `<install>/userdata/beaconlink/peer.key`, a node's `<state>/identity.key` | key files; the proof nonce is found again at each start |
| `<install>/userdata/beaconlink/node.lock` | empty; locked by the launcher running the node |
| `<install>/userdata/beaconlink/accounts/<id>/` | an account ([Identities](#identities)), below |

**`peers.json`**: `{"format": 1, "peers": [{"key": "<hex>", "address": "<IP literal>", "port": <port>, "firstSeen": <ms>, "lastSuccess": <ms>, "failures": <n>}, ...]}`, at most 256 peers and 256 KiB; `lastSuccess` 0 is a reported peer that never answered. Merge: union by key; per key the newer `lastSuccess` gives address, port and failures (equal: the larger `failures`); `firstSeen` is the minimum; of two keys at one address the newer `lastSuccess` stays; entries with 3 failures or older than 30 days (`lastSuccess`, or `firstSeen` if it never answered) are dropped; cut at 256 by `lastSuccess` descending, `firstSeen` ascending, key ascending. A process merges only entries it changed since its last merge, every 5 minutes and at stop; leaves are never written.

**Account folder**: `identity.key` (player key); `account.json` `{"format": 1, "name": "<name>", "difficulty": 24, "stamp": <nonce>, "lastUsed": <epoch ms>}` (a folder whose stamp does not bind its name holds no account); `friends.json` (`PlayerListStore`, shared: `{"format": 1, "players": [{"key": "<hex>", "time": <ms>, "removed": false}, ...]}`; per key the newest `time` wins, a removal on equal time; removals stay 30 days; at most 16 players, oldest additions give way; merged on each own change, reread every 10 s); `blocked.json` (ignored players, same format and rules, at most 256); `account.lock` (empty, shared-locked by every process using the account).

Topics, announcements, meshes, records, registrations, subscriptions, whisper keys, penalties and the graylist live in memory only.

## Versioning

Protocol 1 is the only protocol. Receivers drop envelopes of another version byte and drop or ignore unknown kinds and capability bits. Before 1.0 the protocol number, record and file versions and the numbers in hash and signature domains are all `1`, and layouts may change in place. Peers send their software version in `HELLO` and `PONG` (`MeshPeer.SOFTWARE`, `sc4j-beaconlink-peer 0.11`); no rule depends on it.

The peer layer routes IPv4 only: the runtime's session socket (`UdpDatagramEndpoint`) is IPv4, and a process uses one socket (the observed address must be the hole-punching address). IPv6 `PEERS` entries are dropped and IPv6 seeds are unreachable; the address encoding and the /48 and /32 limits cover IPv6.
