# Changelog

All notable changes to NodeWave are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).

## [Unreleased]

Planned for v0.2.0: stable node identity, binary packet header, free-text chat, delivery ACK, codec unit tests.

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
- Sender shown on received messages is the sender's rotating BLE address and may differ from the address listed in nearby nodes (addressed by v0.2.0 stable node IDs).
- Each send opens a new GATT connection (about 1 s), which suits pings but not frequent messaging.
- Single hop only: no relaying between nodes.
