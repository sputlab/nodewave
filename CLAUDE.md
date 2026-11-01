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
