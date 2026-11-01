# Changelog

All notable changes to NodeWave are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).

## [Unreleased]

Planned for v0.3.0: multi-hop relay.

## [0.2.0] - 2026-11-01

Stable node identity and an application protocol on top of the BLE transport.

### Added
- **Stable node ID**: 8 random bytes generated at first launch and persisted; announced in the BLE scan response together with the node name. Nodes are now identified by this ID instead of the rotating BLE address, so the nearby-nodes list and message senders always match.
- **Packet format** with a 14-byte header `[version][type][senderId 8B][messageId 4B]` and types `MESSAGE`, `PING`, `ACK` (`protocol/Packet.kt`).
- **Free-text chat**: type a message (up to 200 UTF-8 bytes) and send it to the selected node (**INVIA**) or to every visible node (**TUTTI**).
- **Delivery ACK**: the receiver confirms each message; the chat shows `invio…`, `inviato`, `CONSEGNATO` or `FALLITO`. ACKs are retried once and duplicate messages are de-duplicated.
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
