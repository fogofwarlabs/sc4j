# Beacon.Link peer operator guide: headless peer and launcher public mode

**English** | [한국어](operator.ko.md)

How to run a full peer for other players: the launcher in public mode (a desktop that stays on) or the headless node `sc4j-beaconlink-node` (a server). Every SC4J is a Beacon.Link peer; there is no central server. Wire details: [protocol reference](protocol.md).

## What a full peer does

A full peer is a peer that strangers can reach. It routes lookups (Kademlia), runs and helps dial-back self-tests, stores the topics closest to its ID and gossips their records (rooms, presences, chat lines, sign-outs), and is the home peer of players behind NATs: it registers their connections (at most 8 per player key), reports their observed address (no STUN service needed), publishes and pushes their records, relays hole-punching signals and sealed whispers, and refuses connections signed out elsewhere (`SIGNED_OUT`). It answers pings.

It carries no game traffic of games it is not part of, relays no channel datagram and cannot forge signed records or signals; it can only drop or delay them, and players use several peers.

## Launcher public mode

Every open launcher runs the computer's node, logged in or not. The first launcher of an install holds it (`node.lock`); another launcher of the install takes it within 5 minutes after the first closes. The node keeps one key (`peer.key`) and one port.

**Role** (`node.json`): automatic (default, "Help other players connect" on) listens on its UDP port (default 7455; a free logged port when 7455 is taken) on every IPv4 interface and becomes a full peer once its self-test passes, otherwise stays a leaf. Off: leaf-only on a free port; it still joins and keeps `peers.json` current.

**Port mapping** (`node.json` `portMapping`, "Open ports on the router (UPnP)", default on): the launcher asks the routers to open its node's port (automatic role only) and the port of each game session, with PCP, NAT-PMP and UPnP-IGD at once: every router that answers, on every local network, maps the port with the first of these protocols it speaks, a lease of one hour renewed at half and released at close; the mappings are named `SC4J Beacon.Link` in the router's list. Once a node observes the public address, only the mapping whose external address equals it is kept (PCP first, then NAT-PMP, then UPnP) and the others are released at once: their router is behind another NAT or not on the route of the traffic. A mapped address that is private or in 100.64.0.0/10 is not used. With the setting off, or a router without these protocols, a full peer needs a manual forward. The headless node never maps.

**Serving other players**:

1. Launcher, Settings, Beacon.Link: "Help other players connect" on, UDP port (default 7455), Apply.
2. Router: with port mapping on, nothing to do when the router opens the port (log line `port mapping by ...`); otherwise forward UDP (not TCP) 7455 to this PC and give the PC a fixed LAN address (DHCP reservation).
3. Windows Defender Firewall: the launcher reads its inbound rules for its own executable and for the Java runtime it starts games with (checked once when both are one program, as in a development install); when none allows one of them or one blocks it (a cancelled first-run Windows prompt adds block rules), a notice offers Allow, which after Windows asks for approval, once for each such program, adds inbound UDP and TCP rules named `SC4J (<program>)` for every profile and removes the program's block rules.
4. Keep the launcher running; no game needs to be open.

**The panel** shows the network state ("Connected", "No peer answers", "No peers"), "External access OK", "External access not checked" or "No external access (UDP 7455)", the local and external IP addresses and the peer counts. "External access OK" needs a passed self-test or a stranger's hello; the very first seed shows "not checked" until another peer connects. "Node runs in another launcher" means another launcher of the install holds the node.

**Port mapping log lines** (launcher and game logs): `port mapping by <protocol> at <router>: <external address> -> :<port>/UDP, lease <time>`; `port mapping not available (PCP: ..., NAT-PMP: ..., UPnP: ...)`; `port mapping by <protocol> at <router> kept: <external address> matches observed`; `port mapping at <router> skipped: mapped address <a> differs from observed <b> (another NAT outside, or not on the route)` or `... is not public (another NAT outside)`; `port mapping by <protocol> at <router> renewed`, `released` or `lost: ...`. A `network connectivity DIRECT` line ends with `inbound needs the firewall to allow SC4J`.

**Firewall log lines** (launcher and game logs, Windows): `firewall allows <program>`, `firewall has no inbound rule for <program>` or `firewall blocks <program>`, where `<program>` is the process's executable (`SC4J.exe` for the launcher, the Java runtime for a game); the launcher log also has `LAUNCHER: firewall ...` lines for the Java runtime it starts games with.

**No external access**: check the port mapping log lines, the forward and the firewall rule. An external IP in 100.64.0.0/10, or one that differs from the router's WAN address, is behind carrier-grade NAT: this PC cannot be a full peer. With a dynamic IP, use a dynamic DNS name as the seed address.

**Files** in `<install>/userdata/beaconlink/` ([formats](protocol.md#files)):

| File | Content |
|---|---|
| `node.json` | role `automatic` or `off`, port, port mapping |
| `peer.key` | the node's key |
| `node.lock` | held by the launcher that runs the node |
| `peers.json` | learned peers |
| `nodes.json` | the user's node list (Peers button of the panel); until saved, `<install>/launcher/seeds.json` stands in |

## Headless peer

`gradlew :sc4j-beaconlink-node:installDist` builds `sc4j-beaconlink-node/build/install/sc4j-beaconlink-node/`: `bin/sc4j-beaconlink-node` (`.bat` on Windows), `lib/` (node, peer library, protocol, base; no engine, mod or graphics), `docs/` (`operator.md`, `protocol.md`, `operator.ko.md`, `protocol.ko.md`), `LICENSE`, `NOTICE`, `licenses/`. It needs Java 25 (headless is enough).

```text
sc4j-beaconlink-node --state <dir> --bind <ip>:<port> [--peer <host>:<port>]...
```

| Option | Meaning |
|---|---|
| `--state <dir>` | required; the node's folder (key, learned peers, logs), created if absent |
| `--bind <ip>:<port>` | required IP literal and UDP port, no default, for example `203.0.113.7:7455`; IPv6 in brackets (`[2001:db8::1]:7455`), but the peer layer routes IPv4 only |
| `--peer <host>:<port>` | a seed, repeatable; names are resolved once at start; the first seed runs without one |
| `--help`, `-h` | usage |

Exit codes: `0` normal end (also `--help`), `1` run failure (such as an address that cannot be bound), `2` command line error. The node runs the launcher node's peer with the default limits until stopped (Ctrl+C, SIGTERM, service stop); at stop it merges its learned peers into `peers.json` and logs its counts. Behind a NAT, bind the inside address of a UDP forward.

| Environment variable | Meaning |
|---|---|
| `JAVA_OPTS`, `SC4J_BEACONLINK_NODE_OPTS` | JVM options, for example `-Xmx256m` |
| `SC4J_BEACONLINK_NODE_CACHE` | folder for the JVM's temporary files (`<it>/tmp`, created at every start); default `<install>/.cache`; set it when the install is read-only |

### State folder

| File | Content |
|---|---|
| `identity.key` | the node's peer key (`BLKF` version 1), created at first start; keep it private; a new key is a new peer |
| `peers.json` | learned full peers, at most 256, merged every 5 minutes and at stop; dropped after 3 starts without answer or 30 days without success; greeted at the next start, so a restart needs no seed |
| `logs/` | log files |

The node writes nothing outside this folder and the cache folder.

### Log lines

- `Node <key> listens at /<ip>:<port>.` and `Peer key: <64 hexadecimal digits>` at every start; the key is only for pinned-key seed entries.
- `Joining the network through <n> seeds and learned peers.`, then `Network state: CONNECTED` once a peer answers, or `Network state: NO_PEER_REACHABLE` when none answered within 30 s. A first seed (`0 seeds`) logs `CONNECTED` when the first peer arrives.
- `Reachable: an unrequested hello arrived from ...` or `... probe ...`: a full peer. `Not reachable: the probe of a dial-back to ... did not arrive.`: check the forward, the firewall and the bind address.
- `Peer counts: handled ..., dropped ...` every hour; `Peer <key> stops: ...` at stop ([Counters](#counters)).
- `Graylisted the peer <key> at <address> for <time> ms.` ([Limits](#limits)).

## Running as a Linux service

Example: Rocky Linux 9, 1 vCPU, 1 GB, public IP on the interface.

```sh
dnf install java-25-openjdk-headless tar
useradd --system --no-create-home --shell /sbin/nologin sc4j
tar -xf sc4j-beaconlink-node.tar -C /opt           # the installDist folder, as /opt/sc4j-beaconlink-node
```

`/etc/systemd/system/sc4j-node.service`:

```ini
[Unit]
Description=SC4J Beacon.Link node
After=network-online.target
Wants=network-online.target

[Service]
User=sc4j
Group=sc4j
Environment=JAVA_OPTS=-Xmx256m
Environment=SC4J_BEACONLINK_NODE_CACHE=/var/cache/sc4j-node
ExecStart=/opt/sc4j-beaconlink-node/bin/sc4j-beaconlink-node --state /var/lib/sc4j-node --bind 203.0.113.7:7455
StateDirectory=sc4j-node
CacheDirectory=sc4j-node
Restart=always
RestartSec=10
MemoryMax=512M
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```sh
systemctl daemon-reload
systemctl enable --now sc4j-node
journalctl -u sc4j-node -f                         # or /var/lib/sc4j-node/logs/
```

- `StateDirectory=` and `CacheDirectory=` create the writable `/var/lib/sc4j-node` and `/var/cache/sc4j-node`; everything else is read-only under `ProtectSystem=strict`, so `SC4J_BEACONLINK_NODE_CACHE` must point outside `/opt`.
- `MemoryMax=` caps the whole process; keep it well above the heap.
- Bind the address of the interface with the public IP (`ip -4 addr`).
- Firewall: inbound UDP only, for example `firewall-cmd --permanent --add-port=7455/udp && firewall-cmd --reload` or `ufw allow 7455/udp`.
- Check from another machine with the launcher's peer check (Settings, Beacon.Link, Peers, Ping): round trip time, version (`sc4j-beaconlink-peer 0.11`) and the observed external IP, or `No answer`.

**Update**:

1. `gradlew :sc4j-beaconlink-node:installDist`, then `tar -cf sc4j-beaconlink-node.tar -C sc4j-beaconlink-node/build/install sc4j-beaconlink-node`; copy it to the server (`/tmp`).
2. `systemctl stop sc4j-node`.
3. `rm -rf /opt/sc4j-beaconlink-node && tar -xf /tmp/sc4j-beaconlink-node.tar -C /opt` (the state folder is untouched).
4. `systemctl daemon-reload` if the unit changed, then `systemctl start sc4j-node`.
5. The log shows the same `Peer key`, `listens at`, and `Network state: CONNECTED` once a peer answers.

**Moving** to another machine: copy the state folder (the key keeps the identity); peers drop the old address after three starts without answer.

**Rotating the key**: stop, move `identity.key` away, start. Other peers see a new peer; seeds that pinned the old key report the wrong key until they pin the new one.

## Limits

Every table is bounded; none can be changed on the command line.

| Limit | Default |
|---|---|
| datagram length | 1200 bytes |
| rate per source IP address (before any signature work) | burst 256, then 128 per second |
| rate per sender key (after signature and nonce) | burst 32, then 16 per second |
| rate buckets held | 8192 addresses and 8192 keys; a full table evicts only buckets that are full again |
| replay window | 30 s around the node's clock |
| remembered nonces | 65536, at most 2048 per source IP address; a full cache drops new messages |
| routing table | 8 per k-bucket, 4 cached; 2 of one /24 per bucket, 8 of one /24 and 16 of one /16 per table; 256 leaves outside |
| registrations | 4096, at most 300 s |
| topics | 256; rooms per topic 512 (withdrawals included), 8 per host key, 4 per host connection, at most 600 s ahead; presences per topic 256, 8 connections per player; one sign-out per player; chat backlog 32 lines |
| records of one author | rooms burst 8, then 1/s; chat lines burst 5, then 1/s (backlog lines dated over 5 s ago: 32, then 1/s); presences and sign-outs burst 16, then 1 per 5 s |
| subscribed clients | 256 per topic, 90 s each |
| stored announcements | 2048 topics, 64 members each, 10 minutes |
| mesh per topic | 6 neighbours (4 to 12), at least 2 of the node's own choice |
| self-test probes | 4 at once, then 1 per second per IP address |
| penalties | 40 points graylist a key at an address for 10 minutes; also the key and the address alone when the address is proved ([points](protocol.md#receiving)) |
| answers to an unproved address | never longer than the request |

A proved address is graylisted as a whole IP address (all ports): players behind one carrier-grade NAT share its rate and graylist entry.

## Counters

Dropped datagrams are never answered. The node logs drop counts every hour and at stop: `OVERSIZE`, `ADDRESS_RATE`, `MALFORMED`, `UNEXPECTED_REPLY`, `OWN_KEY`, `OUTSIDE_WINDOW`, `FORGED`, `KEY_RATE`, `REPLAYED`, `REPLAY_SOURCE_FULL`, `REPLAY_CACHE_FULL`, `UNPADDED`, `NOT_A_PEER`, `INVALID_PROOF`, `INVALID_ENTRY`, `UNREQUESTED_PROBE`, `NOT_IN_TOPIC`, `INVALID_RECORD`, `COORDINATE_OUTLIER`, `GRAYLISTED`, `DEFECT`.

| Counter | Meaning |
|---|---|
| `DEFECT` | a node bug (the datagram is lost, the node runs on); report it with the log lines around `Failed to handle a datagram` |
| `OUTSIDE_WINDOW` in numbers | check the server clock (NTP) |
| `COORDINATE_OUTLIER` | a round trip that did not fit the claimed coordinate; a few are normal |
| `ADDRESS_RATE`, `KEY_RATE`, `GRAYLISTED` | senders over their limits; many mean a flood, which the limits hold |
| `UNREQUESTED_PROBE` | a probe for no waiting test, ignored |

Dial-back probes are normal: the node sends small `PROBE` datagrams for other peers' self-tests (rate-limited as above) and receives probes for its own. A forged request can make the node and a third peer each send one small datagram to an address that did not ask.

## Privacy

A full peer's IP address is public (routing tables, topic member lists). A node sees its registered players' addresses, display names, keys and mod fingerprints, the signed public records of its topics, and the sender, target and size of whispers it relays, not their text. Signals between players are signed, not encrypted, and carry channel offers (ephemeral keys, address candidates). Game traffic never passes the node; players who play together see each other's addresses. The launcher says this to players in the help of "Help other players connect" and the Beacon.Link privacy statement of its About box.
