# NodeWave

Off-grid mesh walkie-talkie for Android. Phones talk to each other directly over **Bluetooth Low Energy**: no internet, no servers, no Google Play Services. 100% native Android APIs, ready for F-Droid.

**Status: v0.1.0** - two phones discover each other and exchange a "CIAO - 73" ping over pure BLE. See the [changelog](CHANGELOG.md).

## Features (v0.1.0)

- Each node is at once a GATT server, advertiser, scanner and on-demand GATT client.
- Live list of nearby nodes with signal strength (RSSI, dBm).
- Per-node ping and broadcast of "CIAO - 73".
- Received-messages box and on-screen `MESH_RADIO` log console.

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
3. Tap **PING (CIAO - 73)** on one phone; the other shows the message.
4. Follow the radio from a computer:

```sh
adb logcat -s MESH_RADIO
adb -s <serial> logcat -s MESH_RADIO   # with several devices connected
```

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
└── transport/
    ├── MeshTransport.kt   hardware-agnostic radio contract
    ├── BleTransport.kt    pure-BLE implementation
    └── MeshLog.kt         unified MESH_RADIO logger
```

The UI never touches radio APIs; everything goes through `MeshTransport`. See [CLAUDE.md](CLAUDE.md) for the full rules (permissions, concurrency, timeouts, logging).

## Roadmap

- **v0.2.0** - stable node IDs, packet header, free-text chat, delivery ACK.
- **v0.3.0** - multi-hop relay.

## License

No license has been chosen yet; until one is added, all rights are reserved by the authors.
