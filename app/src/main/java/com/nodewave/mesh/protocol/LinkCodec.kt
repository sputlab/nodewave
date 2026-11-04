package com.nodewave.mesh.protocol

import com.nodewave.mesh.transport.LinkOffer
import java.nio.ByteBuffer

/** Who may ask this phone to join a Wi-Fi Direct group. Joining changes the phone's Wi-Fi state, so it is not open by default. */
enum class FastLinkMode { OFF, VERIFIED, EVERYONE }

/**
 * `LINK` packet plaintext (sealed like chat): `[kind u8][linkId i32][body]`.
 * OFFER carries `[ssidLen u8][ssid][passLen u8][pass][port u16]`; READY and DECLINE have no body.
 */
object LinkCodec {
    const val OFFER = 1
    const val READY = 2
    const val DECLINE = 3

    class Decoded(val kind: Int, val linkId: Int, val offer: LinkOffer?)

    fun offer(linkId: Int, o: LinkOffer): ByteArray {
        val ssid = o.ssid.toByteArray(Charsets.UTF_8)
        val pass = o.passphrase.toByteArray(Charsets.UTF_8)
        require(ssid.size in 1..32 && pass.size in 8..63) { "ssid/passphrase length" }
        return ByteBuffer.allocate(5 + 1 + ssid.size + 1 + pass.size + 2)
            .put(OFFER.toByte()).putInt(linkId).put(ssid.size.toByte()).put(ssid).put(pass.size.toByte()).put(pass).putShort(o.port.toShort()).array()
    }

    fun answer(kind: Int, linkId: Int): ByteArray = ByteBuffer.allocate(5).put(kind.toByte()).putInt(linkId).array()

    fun decode(plain: ByteArray): Decoded? {
        if (plain.size < 5) return null
        val buf = ByteBuffer.wrap(plain)
        val kind = buf.get().toInt()
        val id = buf.int
        return when (kind) {
            READY, DECLINE -> if (buf.hasRemaining()) null else Decoded(kind, id, null)
            OFFER -> {
                if (!buf.hasRemaining()) return null
                val sl = buf.get().toInt() and 0xFF
                if (sl !in 1..32 || buf.remaining() < sl + 1) return null
                val ssid = ByteArray(sl).also { buf.get(it) }.toString(Charsets.UTF_8)
                val pl = buf.get().toInt() and 0xFF
                if (pl !in 8..63 || buf.remaining() != pl + 2) return null
                val pass = ByteArray(pl).also { buf.get(it) }.toString(Charsets.UTF_8)
                val port = buf.short.toInt() and 0xFFFF
                if (port == 0) null else Decoded(kind, id, LinkOffer(ssid, pass, port))
            }
            else -> null
        }
    }
}
