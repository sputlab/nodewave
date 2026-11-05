// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.data

import java.net.URI

/** What kinds of offline map files the app understands, and where to find them. */
object MapFiles {
    enum class Kind {
        /** Vector map (Mapsforge): small files, one per region, drawn on the phone. */
        MAPSFORGE,
        /** Raster tiles in an SQLite file. */
        MBTILES,
        UNKNOWN
    }

    data class Preset(val title: String, val url: String, val size: String)

    val PRESETS = listOf(
        Preset("Italy", "https://download.mapsforge.org/maps/v5/europe/italy.map", "about 1.7 GB"),
        Preset("Monaco (small test)", "https://download.mapsforge.org/maps/v5/europe/monaco.map", "about 0.4 MB")
    )

    private const val MAGIC = "mapsforge binary OSM"

    fun kindOf(fileName: String): Kind = when (fileName.substringAfterLast('.', "").lowercase()) {
        "map" -> Kind.MAPSFORGE
        "mbtiles", "sqlite", "db" -> Kind.MBTILES
        else -> Kind.UNKNOWN
    }

    /** True if [header] starts like a Mapsforge map file. */
    fun isMapsforge(header: ByteArray): Boolean =
        header.size >= MAGIC.length && String(header, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC

    /**
     * The file name to save a download under, from the last part of an `https` link: only safe characters,
     * and only a name of a kind we can open. Null for anything else.
     */
    fun fileNameFor(url: String, allowHttp: Boolean = false): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" && !(allowHttp && uri.scheme == "http")) return null
        if (uri.host.isNullOrBlank()) return null
        val last = uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        val safe = last.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return safe.takeIf { kindOf(it) != Kind.UNKNOWN && !it.startsWith(".") }
    }
}
