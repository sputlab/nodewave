# Changelog

All notable changes to NodeWave are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).

## [Unreleased]

### Changed
- All user-facing text is now English (the UI was Italian up to v0.3.2): `NODES`, `STATE`, `SEND` / `ALL`, `sending…` / `sent` / `DELIVERED` / `FAILED`, `DEBUG: skip direct link (force relay)`. Changelog entries for earlier versions use the English labels.
- The development language of the project is English (code, comments, commit messages, docs, UI). The ping payload remains the ham-radio greeting `CIAO - 73`.

## [0.3.2] - 2026-11-01

Fewer failed links, from the logs of a three-phone run: 15 of 77 connection attempts failed (19%), 14 of them involving the Honor View 10, clustered on the once-a-minute HELLO bursts of phones that had been started together.

### Fixed
- **HELLO bursts no longer line up.** Each periodic round is drawn at random from 60 s +/- 15 s, and a neighbour-set change is announced after a random 0-4 s, so phones started together drift apart instead of connecting to each other at the same moment every minute.
- **Retries wait long enough.** User sends now make 3 attempts with growing, jittered waits (about 0.8 s then 1.6 s, plus up to 0.4 s random) instead of 2 attempts 0.4 s apart: a failed link usually means the peer is mid-session with us for about 2 s, which swallowed both old attempts.

### Changed
- BLE scan mode `LOW_LATENCY` -> `BALANCED`, to leave radio time for connections. This is a hypothesis (continuous scanning is known to starve connections on some Huawei chipsets), not something the logs proved: compare the failure rate against 0.3.1.

### Tests
- 54 unit tests (new: HELLO jitter bounds and desynchronisation across different seeds). Retry timing and scan mode need real hardware and are not covered by the JVM suite.

## [0.3.1] - 2026-11-01

Radio reliability and log readability, from analysing logs of a three-phone run (Honor, Sony, Mi Note 10).

### Fixed
- **HELLOs no longer delay chat and ACKs.** They were 83% of all radio sends and shared a single send queue with user traffic: a HELLO stuck for 5 s in service discovery delayed an ACK by 5 s. HELLOs are now low priority: they are skipped (and retried on the next 2 s tick) when the radio is busy, and give up on a stuck step after 2 s instead of 5 s.
- **Chat messages and ACKs retry once** on transient link failures (GATT status 133/62, connect or discovery timeout). Retries only happen before anything was written, so a retry can never duplicate a write.

### Changed
- HELLO interval 20 s -> 60 s (sent immediately when the neighbour set changes); routes now live 150 s. About three times less background radio traffic.
- Radio logs name the node instead of a MAC address (`Mi Note 10 [e24a14]`), HELLO sends are logged as such, and expected radio errors take one line instead of a stack trace.
- `MeshTransport.send` gained a `lowPriority` flag; low-priority sends fail fast with `TransportBusyException` when the radio is in use.

### Tests
- 53 unit tests (new: HELLO scheduling and busy-radio retry). The BLE retry itself needs real hardware and is not covered by the JVM suite.

## [0.3.0] - 2026-11-01

Multi-hop relay: messages now cross intermediate nodes to reach phones that are out of direct range.

### Added
- **Relay**: a node that receives a packet addressed to someone else forwards it. Next hop is chosen by `Router`: the destination itself if directly visible, else the neighbour a route points to, else a flood to all neighbours (never back to the packet's author).
- **Loop and storm protection**: a seen-cache keyed by `(senderId, messageId)` drops duplicate copies, and a TTL (default 5) bounds how far a packet travels. Messages reach nodes up to 5 links away.
- **Reachability**: nodes exchange `HELLO` packets (every 20 s and whenever the neighbour set changes) listing their direct neighbours. The node list now shows indirect nodes as `via <neighbour>`; routes expire after 60 s or as soon as the relay is no longer visible.
- **Multi-hop ACKs**: acknowledgements are routed back the same way; the chat shows the number of relays crossed (`DELIVERED · 1 relay`).
- **Debug switch** "skip direct link (force relay)": the node never hands its own packets straight to their destination, so relaying can be tested with all phones in range. Enable it on both the sender and the receiver to force the ACK through the relay as well.
- Router, routing table, HELLO codec and a JVM mesh simulation (in-memory radio, up to 7 nodes) covering relay, forced relay, exactly-once delivery in a full mesh, TTL limits, route learning and expiry. 51 unit tests in total.

### Changed
- Packet header v2 is 24 bytes: `[version][type][ttl][hops][senderId][destId][messageId]`. `destId` and `ttl`/`hops` are new; new packet type `HELLO`.
- `MeshMessenger` now owns routing and exposes `nodes` (direct and indirect) instead of the UI reading peers from the transport.
- "ALL" and broadcast ping now address every known node, direct or indirect.

### Breaking
- v0.3.0 is **not wire-compatible** with v0.2.0 (protocol version 1 packets are dropped). Update every device.

### Known limitations
- Flooding is used whenever no route is known yet, which is wasteful on large meshes; routes are learned from HELLOs only up to two hops.
- HELLOs and relayed packets share the single GATT send queue, so a busy node adds latency to chat messages.

## [0.2.0] - 2026-11-01

Stable node identity and an application protocol on top of the BLE transport.

### Added
- **Stable node ID**: 8 random bytes generated at first launch and persisted; announced in the BLE scan response together with the node name. Nodes are now identified by this ID instead of the rotating BLE address, so the nearby-nodes list and message senders always match.
- **Packet format** with a 14-byte header `[version][type][senderId 8B][messageId 4B]` and types `MESSAGE`, `PING`, `ACK` (`protocol/Packet.kt`).
- **Free-text chat**: type a message (up to 200 UTF-8 bytes) and send it to the selected node (**SEND**) or to every visible node (**ALL**).
- **Delivery ACK**: the receiver confirms each message; the chat shows `sending…`, `sent`, `DELIVERED` or `FAILED`. ACKs are retried once and duplicate messages are de-duplicated.
- `MeshMessenger`: protocol layer (framing, chat history, ACKs) that depends only on `MeshTransport`.
- 16 unit tests for the packet codec and the advertisement format.
- GPL-3.0 `LICENSE`.

### Changed
- `PeerNode.id` is now the stable node ID; `BleTransport` keeps a node-ID to BLE-address map and requires the local node ID in its constructor.
- The received-messages box became a two-way chat view; PING is now a typed packet.
- Raw BLE addresses are no longer shown in the UI.

### Breaking
- v0.2.0 is **not wire-compatible** with v0.1.0: older nodes advertise no node ID and are ignored, and plain-text writes are dropped as malformed. Update every device.

## [0.1.0] - 2026-11-01

First release: the "CIAO - 73" radio ping between two phones over pure Bluetooth Low Energy.

### Added
- `MeshTransport` contract (`state`, `discoveredPeers`, `incomingPayloads`, `start`, `stop`, `send`, `broadcast`) isolating radio hardware from the UI.
- `BleTransport`: GATT server (Write and Write No Response), BLE advertiser, service-filtered scanner with live RSSI, and short-lived GATT client for sends. Every GATT step is bounded by a 5 s timeout.
- Peer discovery with node name carried in the scan response; stale peers pruned after 15 s.
- Runtime permission handling split by API level: `BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT` on API 31+, `ACCESS_FINE_LOCATION` on API 29-30.
- Detection of disabled Bluetooth and, on Android 10/11, disabled location services (`ERROR_HARDWARE_DISABLED`).
- `MeshLog`: unified `MESH_RADIO` logging to logcat and to an in-app console.
- Tactical dark Jetpack Compose UI: node header with transport state, START/STOP RADIO, nearby nodes with RSSI, per-node PING, broadcast, received messages and live log console.
- Project guidelines in `CLAUDE.md`.

### Verified on
- Sony XQ-AS52 (Android 12+) and Honor View 10 BKL-L09 (Android 10), in both directions.

### Known limitations
- Sender shown on received messages is the sender's rotating BLE address and may differ from the address listed in nearby nodes (fixed in v0.2.0 by stable node IDs).
- Each send opens a new GATT connection (about 1 s), which suits pings but not frequent messaging.
- Single hop only: no relaying between nodes.
