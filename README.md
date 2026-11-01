# NodeWave

Off-grid mesh walkie-talkie for Android. Phones talk to each other directly over **Bluetooth Low Energy**: no internet, no servers, no Google Play Services. 100% native Android APIs, ready for F-Droid.

**Status: v0.3.2** - phones discover each other by stable node ID, exchange chat messages with delivery confirmation over pure BLE, and relay messages across intermediate phones (multi-hop). See the [changelog](CHANGELOG.md).

## Features (v0.3.2)

- Each node is at once a GATT server, advertiser, scanner and on-demand GATT client.
- Stable per-install node ID, so peers are recognised even when BLE addresses rotate.
- Live list of nodes: direct ones with signal strength (RSSI, dBm), indirect ones as `via <neighbour>`.
- Multi-hop relay with TTL and duplicate suppression; delivery ACKs travel back across the relays.
- Free-text chat to a selected node or to all nodes, with delivery status (`inviato` / `CONSEGNATO` / `FALLITO`), plus the number of relays crossed.
- Per-node ping and broadcast of "CIAO - 73".
- On-screen `MESH_RADIO` log console.

## Requirements

- Android 10 or later (`minSdk 29`, `targetSdk 35`) with Bluetooth LE.
- Android 10/11: location services must be switched **on** or the system returns no scan results.
- To build: JDK 17+ (the one bundled with Android Studio works) and the Android SDK with platform 35.

## Build and install

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS example
./gradlew assembleDebug      # APK in app/build/outputs/apk/debug/
./gradlew installDebug       # installs on every connected device
```

On Xiaomi/MIUI, "Install via USB" may require a Mi account and a SIM; copy the APK to the phone and install it manually instead.

## Try it

1. Install on two phones and grant the requested permissions.
2. Tap **START RADIO** on both. Each should list the other under *Nodi vicini*.
3. Tap a node to select it, type a message and tap **INVIA** (or **TUTTI** for everyone), or use **PING (CIAO - 73)**. The other phone shows it and the sender's chat marks it `CONSEGNATO`.
4. Follow the radio from a computer:

```sh
adb logcat -s MESH_RADIO
adb -s <serial> logcat -s MESH_RADIO   # with several devices connected
```

## Testing the relay (three phones)

1. Install the same version on all three phones and start the radio on each.
2. Wait a few seconds: the node list of every phone should show the other two (indirect ones appear after the first neighbour announcement).
3. Phones in the same room all see each other, so force the relay: on the **sender and the receiver** switch on *DEBUG: ignora link diretto*, leaving the third phone as it is.
4. Send a message from the sender to the receiver. The receiver shows it with `1 relay` and the sender marks it `CONSEGNATO · 1 relay`.
5. Alternatively, switch Bluetooth off on the relay phone and watch the indirect node disappear (it can take up to a minute for a stale route to expire if the neighbour announcement was missed).

## Permissions

| API level | Runtime permissions | Manifest notes |
|---|---|---|
| 31+ (Android 12+) | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | `BLUETOOTH_SCAN` has `neverForLocation` |
| 29-30 (Android 10-11) | `ACCESS_FINE_LOCATION` | `BLUETOOTH`, `BLUETOOTH_ADMIN`, location capped at `maxSdkVersion="30"` |

## Architecture

Clean MVVM in Kotlin and Jetpack Compose (Material 3), with Coroutines and Flow throughout.

```
com.nodewave.mesh
├── MainActivity.kt        Compose UI, permission requests
├── MainViewModel.kt       StateFlow state for the UI
├── protocol/
│   ├── Packet.kt              24-byte header wire format (MESSAGE / PING / ACK / HELLO)
│   ├── Router.kt              next-hop selection (direct, route, flood)
│   ├── RoutingTable.kt        routes learned from neighbours' HELLOs
│   ├── HelloPayload.kt        neighbour list carried by HELLO
│   ├── NodeAdvertisement.kt   node ID + name in the BLE scan response
│   ├── NodeIdentity.kt        persisted stable node ID
│   └── MeshMessenger.kt       chat history, relay, seen-cache, delivery ACKs, HELLOs
└── transport/
    ├── MeshTransport.kt   hardware-agnostic radio contract
    ├── BleTransport.kt    pure-BLE implementation
    └── MeshLog.kt         unified MESH_RADIO logger
```

The UI never touches radio APIs; everything goes through `MeshTransport`, and `MeshMessenger` adds the protocol on top without knowing about BLE. See [CLAUDE.md](CLAUDE.md) for the full rules (permissions, concurrency, timeouts, logging).

## Roadmap

- **v0.4.0** - to be planned (ideas: foreground service for background operation, message persistence, encryption).

## Tests

```sh
./gradlew testDebugUnitTest   # codec, routing and an in-memory multi-node mesh simulation
```

## License

NodeWave is free software under the [GNU General Public License v3.0](LICENSE).
