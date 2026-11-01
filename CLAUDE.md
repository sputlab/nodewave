# NodeWave — Architectural Guidelines (package `com.nodewave.mesh`)

Off-grid mesh walkie-talkie, 100% F-Droid ready, native hardware only.

- **Language:** 100% Kotlin (Coroutines, `Flow` and `StateFlow` for all async streams and state management).
- **UI Framework:** 100% Jetpack Compose (Material 3). FORBIDDEN: XML layouts, Fragments, AppCompat.
- **SDK Levels:** `minSdk = 29` (Android 10), `targetSdk = 35` (Android 15+).
- **Zero Proprietary Dependencies:** No Google Play Services, no Nearby Connections. Only standard Android APIs (F-Droid compatible).
- **Architecture:** Clean MVVM. The UI observes the ViewModel's `StateFlow`s. Hardware radio APIs are strictly isolated behind the `MeshTransport` interface.
- **Strict Permission Rules (API 29 vs API 31+):**
  - API 31+ (Android 12+): request at runtime `BLUETOOTH_SCAN` (with `neverForLocation` in the Manifest), `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT`. Do NOT request legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` on API 31+.
  - API 29–30 (Android 10–11): clean fallback requesting `ACCESS_FINE_LOCATION` at runtime, and use `BLUETOOTH` / `BLUETOOTH_ADMIN` with `maxSdkVersion="30"` in the Manifest.
- **Radio & Concurrency Rules:**
  - BLE hardware callbacks (Advertising, Scanner, GATT Server/Client) run on arbitrary system threads: always wrap them with `callbackFlow` or run on `Dispatchers.IO`.
  - Enforce strict timeouts (`withTimeoutOrNull`) on every GATT connection and write operation.
  - Log every radio state transition, error and permission check with the unified tag `MESH_RADIO` (debug via `adb logcat -s MESH_RADIO`).
- **Layering:** `MeshTransport` moves opaque bytes between peers identified by stable node ID; the application protocol (packet framing, chat, ACKs) lives in `com.nodewave.mesh.protocol` and must stay free of radio/BLE APIs so it can be unit-tested on the JVM.
- **License:** GPL-3.0. Do not add dependencies that are incompatible with it.
- **Relay:** multi-hop logic (routing, seen-cache, TTL, HELLO) lives in `MeshMessenger`/`Router`/`RoutingTable` and is covered by the in-memory mesh simulation in `MeshNetworkTest`; extend that test whenever relay behaviour changes.
- **Cryptography:** only the standard JCA/JCE (no custom primitives, no third-party crypto libraries); crypto code lives in `com.nodewave.mesh.crypto`, free of Android APIs so it runs in JVM tests. Every security property needs a test that fails when the check is removed. Keep `docs/SECURITY.md` truthful, limitations included.
- **Process model:** the radio, identity, storage and messenger belong to `MeshEngine` (owned by `NodeWaveApp`) and run under `RadioService`; view models only observe. Never tie the radio to an activity or view-model lifecycle.
- **Persistence:** framework SQLite behind the `MessageStore`/`PeerKeyStore` interfaces (JVM tests use in-memory fakes); message text is encrypted at rest with the Keystore.
- **Sessions:** session keys exist only in memory (`SessionManager`), are wiped when a session ends and are never written to disk or logs. Only the identity key (encrypted by the Keystore) and public keys are stored. Any change to session adoption/retirement needs a test with both ends starting a handshake at the same moment and a long virtual-time run: that is how a real bug (each end dropping the session the other still used) was found.
- **Transport links:** the transport hands out opaque link ids; a packet may be sent back on the link it arrived on (`SendOptions.viaLink`) and a send that expects an answer keeps the link open briefly (`expectReply`). The messenger only trusts a link hint after the packet it came with was authenticated, and never for relayed packets.
- **Tests:** JVM tests use the in-memory mesh in `TestSupport.kt` with a virtual clock and per-node random seeds, so they are deterministic. Verify a new security test by breaking the check it covers on purpose and confirming it fails; a test that also passes without the check is worthless.
