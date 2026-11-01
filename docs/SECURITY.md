# NodeWave security notes

Status: **v0.4.0, not independently audited.** The design uses only standard, well-reviewed primitives from the
Java/Android platform (no custom cryptography, no third-party crypto libraries), but nobody outside this project
has reviewed it. Do not rely on it where lives or liberty depend on it.

## What is protected

| Property | How |
|---|---|
| Message **confidentiality** between two nodes, even across relays | AES-256-GCM with a key only the two endpoints can derive |
| Message **integrity and authenticity** | the GCM tag; the sender, recipient, type and message ID are bound into it (AAD) |
| **Delivery receipts cannot be forged** | ACKs are sealed with the same pair key |
| Relays **cannot read or alter** what they forward | they never hold any key for the traffic they carry |
| Chat history and identity key **at rest** | text encrypted with an Android Keystore AES-256-GCM key; private key wrapped by it; `allowBackup=false` |

## How it works

1. **Identity.** At first launch each device generates a P-256 key pair. Its **node ID is the first 8 bytes of
   SHA-256(public key)**, so an ID can only be used by whoever holds the matching key. The private key never
   leaves the device (stored encrypted with a Keystore-backed key).
2. **Key exchange.** Before the first message to a node, the sender transmits a `KEY` packet with its public key and
   the receiver answers with its own. Both packets are routed (and relayed) like messages. A receiver accepts a key
   only if the point is a valid point on the curve **and** its hash matches the sender ID in the packet; otherwise
   the packet is dropped. Keys are then remembered (trust on first use).
3. **Pair key.** ECDH(P-256) followed by HKDF-SHA256 (RFC 5869) with an info string binding both node IDs, giving a
   256-bit key identical on both sides.
4. **Messages.** `payload = nonce(12) || ciphertext || tag(16)`, a fresh random nonce per message. The AAD is
   `version, type, senderId, destId, messageId`. `ttl` and `hops` are excluded because every relay changes them.
5. **Replay.** Authenticated messages enter a seen-cache keyed by `(senderId, messageId)`; only authenticated
   packets are added, so a forged packet cannot suppress a real one. The cache is rebuilt from the stored history
   at start-up.

The wire format is documented in `app/src/main/java/com/nodewave/mesh/protocol/Packet.kt`.

## Known limitations

- **Metadata is visible.** Anyone in Bluetooth range can see node IDs, node names (they are advertised), packet
  type, sender and recipient IDs, sizes and timing. Only message *text* is protected.
- **Node IDs are 64 bits.** To impersonate a specific node an attacker must find a different key pair whose hash
  starts with the same 8 bytes, roughly 2^64 work: out of reach for casual attackers, not for a well-funded one.
  For high-stakes conversations, compare node IDs out of band (the app shows the full ID, grouped by four digits).
- **First contact is trusted.** The ID-to-key binding stops key substitution for an ID you already know, but
  nothing proves that an ID belongs to the person you think it does until you compare it.
- **No forward secrecy.** The pair key comes from the long-term keys, so an attacker who later extracts a device's
  private key can decrypt traffic they recorded earlier. There is no key rotation yet.
- **HELLO packets are not authenticated.** A nearby attacker can announce fake neighbour lists and misdirect or
  black-hole routing (a denial of service). It cannot read or forge messages.
- **Replays beyond the stored history** (older than the last 500 messages, or after *CLEAR*) are not recognised
  and would show up as duplicate messages.
- **A compromised phone** (malware, unlocked device, root) exposes everything the app can read.
- **Release builds are debug-signed** for now; verify what you install.

## Reporting a problem

Please open an issue, or contact the maintainers privately for anything that looks exploitable.
