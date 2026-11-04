# Changelog

All notable changes to NodeWave are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [SemVer](https://semver.org/).

## [Unreleased]

### Added
- **Editable node name** (Settings, Identity): the name becomes a label of the unchanged ID, announced over BLE (up to 19 bytes, cleaned of control characters and never cut inside a character), shown in the top bar, used in the contact code, and applied at once by refreshing the advertisement. Empty restores the phone's model.
- Map attribution "© OpenStreetMap contributors" whenever a map source is set.
- **New interface**, tactical and modern: five screens behind a bottom bar, an animated radar, glow and corner-bracket panels, hexagonal node avatars, signal bars.
  - **CHATS**: conversation list with unread counts and group invitations; personal and group conversations as bubbles with delivery state; creating and managing groups (members, admin, leave).
  - **NETWORK**: radar of the nodes around you (closer = stronger signal), live counters (in range, via relay, encrypted, sent, received, delivered, relayed) and a feed of what happens on the mesh.
  - **MAP**: offline-capable map (osmdroid) with the nodes that shared a position and your own; import a map file, or download the visible area from a tile server you configure.
  - **NODES**: every node ever seen, with search and filters (favourites, verified, in range), a label of your own, favourites, verification by QR code, position, "forget".
  - **SETTINGS**: BLE scan and announce profiles and power (applied by restarting the radio), LoRa board parameters (saved for when a board is connected; USB devices are listed), position sharing, map sources, clear history, diagnostics, about.
  - The previous test console lives on as Diagnostics (Settings).
- **Position sharing**: a new sealed packet type `POSITION`, sent one copy per recipient to nobody, only verified contacts, or everyone in range (per-node policy), at most once every 20 s per recipient, older positions never replace newer ones. A fixed station can share hand-entered coordinates every minute without the GPS. The GPS runs only while the app is on screen.
- **Known nodes** are remembered in the database (version 4): names, labels, favourites, last seen and last position. A conversation list, activity feed and statistics are derived from the same state.
- `INTERNET` permission, used only for downloading map tiles on request; `ACCESS_FINE_LOCATION` on every API level, asked only for position features (BLE on API 31+ still never uses location); `usb.host` as an optional feature.
- Groups are now stored in the database (they had only been kept in memory by the app).
- 190 unit tests (new: position codec and policy, directory, conversations, activity). Each rule was checked by breaking it.

### Added
- **Link benchmark** (BENCH tab): between two directly visible phones it opens a fresh link and measures connection time, negotiated MTU, throughput and write latency with acknowledged writes and with write-without-response (about 24 KB each, counted by the receiver, so loss shows), plus RSSI at the start and end. "repeat" runs it every 20 s so you can walk away and watch range. Results go to the `MESH_RADIO` log as `BENCH ...`. The frames (`BenchFrame`) are consumed by the transport and never reach the protocol.

## [0.6.0] - 2026-11-01

Verified contacts, group chat and a radio watchdog that recovers a deaf scanner.

### Added
- **Verified contacts.** Each phone shows a QR code with its full public key and name; scanning someone's code (CameraX + ZXing, both Apache-2.0, no Google services) marks the node as verified and pins its key, so a handshake under any other key for that node ID is refused, even one found by brute-forcing the 64-bit ID. Verified nodes are marked in the node list. New `CAMERA` permission, asked only when scanning, and the camera is not required to install the app.
- **Group chat** for up to 8 members with one admin: create, invite, accept or decline, change members, leave, and chat. A message is sent as one separately sealed copy per member over the existing pairwise sessions, with ACKs and resends: no group key, no new cryptography, forward secrecy of the pairwise sessions kept. Receivers enforce the rules (joined group, sender is a member, only the admin changes the list). Groups and their history are stored on the device (database version 3). Wire: new packet type `GROUP`; the header is unchanged and older nodes drop it.
- A test interface with three tabs: RADIO (the previous screen), CONTACTS (your code, scanner, who is verified) and GROUPS.
- Scan diagnostics: the watchdog message reports the scan callbacks in its window, and `LINKSTATS` counts raw scan callbacks and those without NodeWave data.
- 173 unit tests (new: contact code, pinned keys including after a restart, group codec, the whole group lifecycle and every group rule). Each security test was checked by breaking the rule it covers.

### Changed
- **Watchdog.** In the field a Mi Note 10 stayed deaf to scans for two hours while the radio was reopened eleven times, and a Honor and a Sony lost their scans too. Restarting only the scan never helped, so the watchdog now reopens the radio at the first silence (90 s) and, if that did not help twice, stops and starts the whole transport, which is what doing it by hand does (verified on the Mi: stop, IDLE, STARTING, ACTIVE in 2 s at 361 s). Its log line gives the scan callbacks in the window.
- New app icon (adaptive, with a monochrome variant for themed icons), also used in the README and as F-Droid listing metadata (`fastlane/metadata/android/en-US/images/icon.png`).
- All user-facing text is now English (the UI was Italian up to v0.3.2): `NODES`, `STATE`, `SEND` / `ALL`, `sending…` / `sent` / `DELIVERED` / `FAILED`, `DEBUG: skip direct link (force relay)`. Changelog entries for earlier versions use the English labels.
- The development language of the project is English (code, comments, commit messages, docs, UI). The ping payload remains the ham-radio greeting `CIAO - 73`.
- `docs/SECURITY.md` describes verified contacts and groups, with their limits.

### Fixed
- **The radio could stay deaf for hours.** A Mi Note 10 found no node for over two hours while its radio looked active; the 0.5.0 watchdog reopened it eleven times without effect, and only stopping and starting the radio by hand cured it. A Honor and a Sony lost their scans the same way. See the watchdog entry above.

### Verified on devices
- Watchdog on the Mi alone: reopen at 103 s and 181 s, then stop, IDLE, STARTING, ACTIVE in 2 s at 361 s, and the timers start again.
- Group "Rifugio" created on the Mi with the Honor and the Sony: both invitations arrived, were accepted, and a group message was delivered 2/2 in about 2 s with the right author on both receivers.
- Contact QR code scanned with the camera, verified mark shown (tried by hand).
- Not verified on hardware: a deaf scanner that the new watchdog has to recover in the field, members leaving or being removed on real radios.

## [0.5.0] - 2026-11-01

Forward secrecy, key rotation, authenticated HELLOs and a much more reliable radio link.

### Added
- **Forward secrecy.** The static pair key is gone. Each pair of nodes runs a signed ephemeral ECDH handshake (identity keys now only *sign*): `IA, EA, Sign_IA(req|idA|idB|EA)` answered by `IB, EB, Sign_IB(resp|idB|idA|EB|EA)`. Keys are derived with HKDF-SHA256 into one key per direction plus a session ID, live in memory only and are wiped when the session ends. Node IDs do not change (they are still the hash of the identity key).
- **Key rotation.** A session is replaced after 1 hour or 500 messages by a new handshake started in the background, so traffic never stalls; the old session decrypts stragglers for 2 minutes and is wiped. Every session ends after 2 hours at the latest, and stopping the radio wipes all of them.
- **Authenticated HELLOs.** Neighbour announcements are sealed with the session (confidential and authenticated) and carry an increasing sequence number, so they cannot be forged, read by eavesdroppers or replayed to bring back a stale route. A neighbour without a session first gets a handshake.
- **Replies on the same connection.** The GATT server gained a TX characteristic that notifies; the client subscribes. An ACK or a handshake response is sent back as a notification on the link that carried the request, and a send that expects an answer keeps its connection open for a short while (2.5 s) so follow-ups reuse it. A direct exchange (handshake, message, ACK) now needs one connection instead of several, and the "connection both ways at once" collisions largely disappear.
- **Resend.** A message with no ACK after 10 s is sent again (twice at most, then `FAILED`); the receiver recognises copies. This also recovers the message sent right after a peer restarted and lost its sessions.
- **Radio watchdog.** If nothing is heard for 90 s the scan is restarted; if that does not help the whole radio (GATT server, advertising, scan) is reopened. The wait doubles after each fruitless restart (up to 10 min), nothing is restarted while a link is open or a packet arrived in the last 30 s, and advertising is refreshed every 4 minutes. Seen on an Honor View 10 and on a Mi Note 10: the app said `ACTIVE` but scanning had silently died (0 nodes for an hour); restarting only the scan did not help the Mi, a full restart did.
- `LINKSTATS` log line every 30 s (connections opened/ok/failed by error code, reuses, replies, watchdog restarts).
- Connection priority HIGH on outgoing links (short connection interval for the burst).
- 148 unit tests (new: ECDSA and DER signature handling, the complete session manager, key rotation, forward-secrecy wiping, replayed and forged handshakes/HELLOs/ACKs, simultaneous handshakes, restarts, resends, link reuse). Eight security checks were each broken on purpose to confirm a test fails.

### Changed
- Packet format version 4: `KEY` payload is the 195-byte signed handshake message; MESSAGE/PING/ACK/HELLO payloads are `sid | nonce | ciphertext | tag` (32 bytes of overhead, text limit stays 180 bytes).
- `MeshTransport.send` takes `SendOptions(lowPriority, expectReply, viaLink)` and `IncomingPayload` carries an opaque `linkId`.
- The "E2E key" indicator now means *a live session exists*.
- `Permission check OK` is logged once at start instead of on every send.

### Fixed
- **Sessions could break after two minutes when both phones started a handshake at the same time.** Each side adopted the newest session and retired the others, but the two picked different ones; once the grace period ended each dropped the session the other still used and packets stopped decrypting until a new handshake. Found by breaking a security test on purpose and noticing the mutation survived. Adoption now retires only sessions of the same role, and sessions are bounded by age.
- A message sent just after the other phone restarted was lost silently (now resent, see above).
- **A resend could never cross a relay that had already forwarded the first copy.** The relay's duplicate filter was permanent, so when the ACK was lost on the way back the sender's resend reached the relay (seen in the logs: `Relay: already seen`) and was dropped, and the destination never got the chance to re-acknowledge. Relays now suppress duplicates for 8 s only (shorter than the 10 s resend delay); the destination still shows each message once.

### Measured and verified on devices
Same scripted pings through the UI, three phones next to each other (Mi Note 10 and Honor View 10 send, the Sony receives and answers):

| | 0.4.0 | 0.5.0 |
|---|---|---|
| Pings delivered | 18/18 | 18/18 |
| Delivery time, median (p90) | 2.7 s (3.8 s) | 1.4 s (1.7 s) |
| Connections opened | 101 | 68 |
| Connections failed | 12% (Honor 23%, Sony 8%) | 4% (Honor 5%, Sony 0%) |

A Mi Note 10 <-> Honor View 10 run of 24 pings gave a median of 1.3 s and 1 failed connection out of 43. One ping in the three-phone run took 14.7 s (a resend). The Honor -> Sony pings could not be driven by the script (their button is off screen on the Honor), so that direction is not part of the numbers. Small samples, phones next to each other: read it as "clearly better", not as a guarantee.

Also checked on the phones:
- encrypted relay Mi -> Honor -> Sony with the debug switch on both ends: delivered with `relays=1`, ACK back through the Honor (about 8 s, every hop went through the Honor, whose links produced several retried errors);
- screen off: the Sony (display off for over a minute) and the Honor (with *App launch > Manage manually*) received a ping and acknowledged it in about a second;
- watchdog: with the phone alone it restarted the scan after 104 s and reopened the whole radio after 181 s with no error, and the phone then found the Honor again.

### Breaking
- v0.5.0 is **not wire-compatible** with earlier versions (protocol version 4). Update every device. Node IDs, identity keys and chat history are kept.

### Known limitations
- Not verified on hardware: key rotation after an hour (simulation only, it reuses the handshake path), real Doze, and a phone losing its scan on its own while the watchdog is running (the escalation was only exercised with the phone alone). The Honor's and the Sony's links still produce transient errors (`133`, `62`, `19`) that the retries hide, so a relayed delivery can take several seconds. See [docs/SECURITY.md](docs/SECURITY.md) for the security limits; the code has not been independently audited.

## [0.4.0] - 2026-11-01

Background operation, persistent history and end-to-end encryption.

### Added
- **Foreground service** (`RadioService`, type `connectedDevice`): the radio keeps running with the screen off or the app closed. A low-importance notification shows the state and the number of nearby nodes, with a *Stop radio* action. START/STOP RADIO now start and stop the service. Android 13+ asks for the notification permission (the radio works without it).
- **Persistent chat history** (framework SQLite, no new dependency): messages and their delivery status survive restarts; a message that was still `sending…` when the app died is shown as `FAILED`. A *CLEAR* button deletes the history. Message text is encrypted at rest with an Android Keystore AES-256-GCM key.
- **End-to-end encryption** (standard JCA only, see [docs/SECURITY.md](docs/SECURITY.md)):
  - every device has a P-256 identity key pair, stored encrypted by the Keystore; its **node ID is now derived from the public key** (first 8 bytes of SHA-256), so IDs are self-certifying;
  - a `KEY` packet exchange, routed and relayed like a message, runs automatically before the first message to a node; keys are checked (point on curve, hash equals the claimed ID) and remembered;
  - pair key = ECDH + HKDF-SHA256 bound to both node IDs; messages are AES-256-GCM sealed with the header fields as AAD, so relays cannot read, alter, redirect or re-label them;
  - **delivery ACKs are sealed too**, so a forged ACK cannot mark a message delivered;
  - only authenticated packets enter the replay (seen) cache, and it is rebuilt from the stored history at start-up.
- The service holds a **partial wake lock** while the radio runs (new `WAKE_LOCK` permission, install-time) so the GATT server stays reachable with the screen off.
- UI: full node ID (grouped by four digits) for this device and every node, and an `E2E key` indicator on nodes whose key we hold.
- `docs/SECURITY.md`: design, what is protected and the known limitations.
- 101 unit tests (new: HKDF against the RFC 5869 vector, key validation, sealing and tamper detection, a full encrypted mesh simulation with eavesdropping, tampering, forged ACKs/keys, replays, restarts and history persistence). Security-critical checks were verified by breaking each one on purpose and confirming a test fails.

### Changed
- The radio, identity, storage and messenger now live in a process-wide `MeshEngine` owned by the application, not in the view model; the UI only observes it.
- Maximum message size 200 -> 180 bytes of text, to leave room for the 28-byte encryption overhead within one BLE write.
- Packet format version 3: new `KEY` type; MESSAGE/PING payloads are sealed text and ACK payloads are a sealed message ID.

### Fixed
- Restoring the history could duplicate a message created while it was loading.
- Node list: the first row was cut off under the header when a node was inserted above the first visible one; the debug switch moved to the console header to give the list room.

### Breaking
- v0.4.0 is **not wire-compatible** with earlier versions (protocol version 3, encrypted payloads). Update every device.
- **Node IDs change** on first launch (they are now derived from a freshly generated key). Nothing was persisted before, so there is no history to migrate.

### Verified on devices (Mi Note 10, Honor View 10, Sony XQ-AS52)
- Foreground service with notification and *Stop radio* action; keys and history survive an app reinstall.
- Key exchange from scratch (Mi Note 10 <-> Sony) followed by an encrypted ping and an authenticated ACK: `DELIVERED`.
- **Encrypted relay**: Mi -> Honor -> Sony with the debug switch on both ends; the Sony received it with `relays=1` and the ACK came back through the Honor (`DELIVERED (relays=1)`).
- **Screen off**: all three phones received a ping and sent their ACK. The Honor stayed reachable for more than 8 minutes with the screen off, once *App launch > Manage manually* is set for NodeWave.
- Honor with default EMUI settings is **not** reachable with the screen off: EMUI moves the process into a freezer cgroup (no log output, peers get `Timeout during write`), and neither the foreground service, the wake lock nor the Doze whitelist prevent it. The README explains the setting.
- Observed: transient GATT errors (`133`, `62`, `19`) between phones are retried automatically; a delivery can take 3 to 11 seconds when they happen.
- Not tested: real Doze (the phones were charging on USB).

### Known limitations
- Phone makers' battery managers can freeze the app despite the foreground service (see README).
- Metadata (node IDs, names, packet types, sizes, timing) is visible to anyone in Bluetooth range; there is no forward secrecy or key rotation; HELLO packets are not authenticated. See [docs/SECURITY.md](docs/SECURITY.md). The code has not been independently audited.
- The Keystore cipher, SQLite store and foreground service are Android components that the JVM tests cannot exercise; they are verified on devices only.

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
