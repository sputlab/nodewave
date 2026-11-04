# NodeWave security notes

Status: **v0.9.2, not independently audited.** The design uses only standard, well-reviewed primitives from the
Java/Android platform (no custom cryptography, no third-party crypto libraries), but nobody outside this project
has reviewed it. Do not rely on it where lives or liberty depend on it.

## What is protected

| Property | How |
|---|---|
| Message **confidentiality** between two nodes, even across relays | AES-256-GCM with a session key only the two endpoints can derive |
| **Forward secrecy** | session keys come from ephemeral ECDH, live in memory only and are wiped when the session ends; stealing a phone's long-term key later does not decrypt recorded traffic |
| **Key rotation** | a new handshake replaces the session after 1 hour or 500 messages, without interrupting traffic |
| Message **integrity and authenticity** | the GCM tag; sender, recipient, type, message ID and session ID are bound into it (AAD) |
| **Delivery receipts cannot be forged** | ACKs are sealed with the same session |
| **Neighbour announcements (HELLO) cannot be forged or replayed** | sealed with the session and carry a sequence number that must increase |
| Relays **cannot read or alter** what they forward | they never hold any key for the traffic they carry |
| A **verified contact cannot be impersonated** | its full public key was scanned from its QR code in person and is pinned: a handshake under any other key for that node ID is refused, even if someone found a key with the same 64-bit ID |
| **Group messages** are end-to-end encrypted and authenticated per author | each member gets its own copy sealed with the pairwise session; a copy cannot be forged by another member or by a relay |
| Chat history and identity key **at rest** | text encrypted with an Android Keystore AES-256-GCM key; private key wrapped by it; `allowBackup=false` |

## How it works

1. **Identity.** At first launch each device generates a P-256 key pair used for **signing only**. Its **node ID is
   the first 8 bytes of SHA-256(public key)**, so an ID can only be used by whoever holds the matching key. The
   private key never leaves the device (stored encrypted with a Keystore-backed key).
2. **Handshake** (two `KEY` packets, routed and relayed like messages), a signed ephemeral Diffie-Hellman:
   - request `A -> B`: `IA, EA, Sign_IA("req" | idA | idB | EA)`
   - response `B -> A`: `IB, EB, Sign_IB("resp" | idB | idA | EB | EA)`

   `IA`/`IB` are the identity public keys, `EA`/`EB` fresh ephemeral keys. Each side checks that the point is
   on the curve, that SHA-256 of the identity key matches the sender's node ID, and the signature. The response
   signs `EA`, so only the live initiator can accept it, and the transcript names both nodes, so it cannot be
   replayed to someone else.
3. **Session keys.** `HKDF-SHA256(ECDH(ea, EB))` bound to both node IDs and both ephemeral keys, expanded into
   one key per direction plus a 32-bit session ID. The ephemeral private keys are dropped right after.
4. **Messages.** `payload = sid(4) | nonce(12) | ciphertext | tag(16)`, a fresh random nonce per message. The AAD is
   `version, type, senderId, destId, messageId, sid`. `ttl` and `hops` are excluded because every relay changes
   them. Directional keys mean a payload cannot be reflected back to its sender.
5. **Using sessions safely.**
   - The session used for sending is the most recently *adopted* one. The initiator adopts immediately; the
     responder only after it has decrypted a message under the session, so a replayed old request cannot hijack
     its outgoing traffic.
   - Adopting a session retires older sessions of the same role only, and retired ones keep decrypting for two
     minutes before being wiped. Every session also ends two hours after creation at the latest.
   - A peer that restarted lost its sessions and starts a new one; the same rule picks it up as soon as it proves
     live. A message that could not be read is simply sent again if no ACK comes back.
   - Requests from one peer are accepted at most every 2 s, with at most 6 sessions per peer.
6. **Replay.** Authenticated messages enter a seen-cache keyed by `(senderId, messageId)`; only authenticated
   packets are added, so a forged packet cannot suppress a real one. Sessions live in memory only, so a recorded
   packet cannot be decrypted at all after the app restarts.
7. **Radio stop.** Stopping the radio wipes every session key.

The wire format is documented in `app/src/main/java/com/nodewave/mesh/protocol/Packet.kt`, the handshake in
`app/src/main/java/com/nodewave/mesh/crypto/SessionManager.kt`.

## Verified contacts

Each phone can show a QR code with its full P-256 public key and name, `nodewave://c/1/<key>/<name>`. Scanning it
stores the key as **verified** and pins it in the session manager. Verifying is one-way: you confirm the other
person; they must scan your code to confirm you. The code contains no secret, so showing it to others is safe;
what matters is that you scan it from the real person's screen. A code whose key is not on the curve, or whose
node ID collides with a different key already stored, is refused.

## Groups

A group has a random 64-bit ID, a name (24 bytes at most), up to 8 members and one **admin** (its creator).
There is **no shared group key**: sending to a group means sending one separately sealed copy to every other
member over the existing pairwise sessions (packet type `GROUP`, plaintext `kind | groupId | body`). So
every copy is authenticated as coming from its real author, a relay cannot read it, and the forward secrecy and
rotation of the pairwise sessions apply unchanged. Rules, all enforced by the receiver:

- an **invitation** creates a pending group; messages are shown only after the user accepts, and at most 8
  invitations can be pending;
- a message counts only if the group is joined and its (authenticated) sender is in the member list;
- only the **admin** can change the name or member list; the same record from anyone else is ignored. A node left
  out of the admin's new list deletes the group;
- a **leave** notice removes its (authenticated) sender from the list.

## Positions

A node can send its position to nobody, to the contacts *it* verified (verification is one-way: the receiver need not have verified the sender), or to every node in range. The position
(`lat, lon, accuracy, time`, 14 bytes) travels as a sealed `POSITION` packet, one copy per recipient over the
pairwise session, so a relay cannot read it. Receivers keep only the newest position per node. Limits: whoever
receives a position can keep it and pass it on; with "everyone" any node in range, including a hostile one, gets
it; the time comes from the sender's clock and is not checked; positions are stored on the receiving phone in
clear in the app database (not in the encrypted message table).

## Voice messages and bulk transfers

A clip travels as `DATA` packets, one sealed chunk each (same pairwise session and authenticated data as chat, plus the byte that names the link class the transfer needs, so a relay cannot change it). The receiver's `DATA_ACK` is sealed too. Relays and eavesdroppers see packet sizes, timing, sender and recipient and that it is bulk data, not what it contains. Limits:

- **`DATA_REFUSE` is not authenticated**: a relay has no session with the author. Anyone in the mesh can forge one and make a transfer stop, which is no worse than jamming.
- A sender could put a lower class byte on a transfer to get it past slow relays; the receiver drops chunks whose byte does not match their content, and receivers cap the number, size and age of transfers they assemble.
- Clips are stored on the phone encrypted with the Keystore key, like message text; they are not forward secret on the device.

## Known limitations

- **Forward secrecy is best effort on the device.** Keys are zeroed when sessions end, but the JVM may leave
  copies in memory and ephemeral private keys are simply dropped, not wiped. It protects against later theft of
  *stored* keys (disk, backup, long-term key), not against someone who dumps the memory of a running app.
- **The chat history is not forward secret.** It is stored encrypted with a Keystore key so you can read it
  again; anyone who can use that key (an unlocked, compromised phone) can read it. Use *CLEAR* to delete it.
- **Metadata is visible.** Anyone in Bluetooth range can see node IDs, node names (they are advertised), packet
  type, sender and recipient IDs, sizes and timing. Only message text and neighbour lists are protected.
- **Node IDs are 64 bits.** To impersonate a specific node an attacker must find a different key pair whose hash
  starts with the same 8 bytes, roughly 2^64 work: out of reach for casual attackers, not for a well-funded one.
  For high-stakes conversations, compare node IDs out of band (the app shows the full ID, grouped by four digits).
- **First contact is trusted until you scan the contact's QR code.** The ID-to-key binding stops key
  substitution for an ID you already know, but nothing proves that an ID belongs to the person you think it does
  until you verify it in person.
- **Stealing the long-term key allows impersonation from then on**, not decrypting the past. There is no
  revocation: if a phone is lost, its node ID stays valid for anyone who holds the key.
- **A session cannot be started to a node that is unreachable**, and the first message to a node (or after a peer
  restarted) takes a round trip longer. Denial of service by a nearby attacker (jamming, flooding with valid-looking
  handshakes, which are rate limited) is not prevented.
- **Groups trust their admin and their members.** Any member sees the whole member list and every message sent
  while it is a member; a removed member keeps what it already received. A malicious member can send different
  texts to different members (copies are not cross-checked), and a malicious admin can add anyone. Messages are
  not stored for members that are out of reach, and there is no common ordering of messages.
- **A compromised phone** (malware, unlocked device, root) exposes everything the app can read.
- **Release builds are debug-signed** for now; verify what you install.

## Reporting a problem

Please open an issue, or contact the maintainers privately for anything that looks exploitable.
