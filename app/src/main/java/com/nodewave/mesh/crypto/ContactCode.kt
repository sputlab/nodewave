// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.crypto

import java.util.Base64

/**
 * What a contact QR code carries: a node's full public identity key and its display name. Showing it to
 * someone in person and having them scan it is how a node ID gets tied to a real person: the scanner keeps
 * the whole key, so a verified contact cannot be impersonated even by someone who found a different key
 * with the same 64-bit node ID. Text form: `nodewave://c/1/<base64url key>/<base64url UTF-8 name>`.
 */
class ContactCode(val publicKey: ByteArray, val name: String) {
    val nodeId: String = Crypto.nodeIdOf(publicKey)

    fun encode(): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "$PREFIX${enc.encodeToString(publicKey)}/${enc.encodeToString(name.take(MAX_NAME).toByteArray(Charsets.UTF_8))}"
    }

    companion object {
        private const val PREFIX = "nodewave://c/1/"
        const val MAX_NAME = 40

        /** Null for anything that is not a well-formed contact code with a valid P-256 key. */
        fun decode(text: String): ContactCode? {
            val body = text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
            val parts = body.split('/')
            if (parts.size != 2) return null
            return runCatching {
                val dec = Base64.getUrlDecoder()
                val key = dec.decode(parts[0])
                if (Crypto.decodePublic(key) == null) return null
                val name = String(dec.decode(parts[1]), Charsets.UTF_8).filter { !it.isISOControl() }.trim().take(MAX_NAME)
                ContactCode(key, name.ifEmpty { Crypto.nodeIdOf(key).take(6) })
            }.getOrNull()
        }
    }
}
