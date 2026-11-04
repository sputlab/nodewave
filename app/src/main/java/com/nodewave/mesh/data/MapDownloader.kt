package com.nodewave.mesh.data

import com.nodewave.mesh.transport.MeshLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one map file into [dir]. The bytes go to `<name>.part` and the file is renamed only when it is complete
 * and, for a Mapsforge map, starts with the format's signature, so a map is never half there. A cancelled or failed
 * download keeps its `.part` and the next [start] of the same link resumes it (HTTP `Range`).
 * This is the only thing in the app that talks to a server, and only when the user asks.
 */
class MapDownloader(
    private val dir: File,
    private val scope: CoroutineScope,
    private val allowHttp: Boolean = false,
    private val onDone: (fileName: String) -> Unit
) {
    data class State(
        val url: String,
        val fileName: String,
        val bytes: Long = 0,
        /** 0 while the server has not said how big it is. */
        val total: Long = 0,
        val running: Boolean = true,
        val error: String? = null,
        val finished: Boolean = false
    ) {
        val percent: Int get() = if (total <= 0) 0 else (100 * bytes / total).toInt().coerceIn(0, 100)
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    private var job: Job? = null

    val isRunning: Boolean get() = _state.value?.running == true

    /** Starts (or resumes) the download of [url]; false if it is not an acceptable link or one is already running. */
    fun start(url: String): Boolean {
        if (isRunning) return false
        val name = MapFiles.fileNameFor(url, allowHttp)
        if (name == null) {
            _state.value = State(url, "", running = false, error = "Use an https link to a .map or .mbtiles file")
            return false
        }
        val initial = State(url.trim(), name)
        _state.value = initial
        job = scope.launch(Dispatchers.IO) {
            try {
                download(initial)
            } catch (e: CancellationException) {
                _state.update { it?.copy(running = false, error = "Stopped: starting again resumes it") }
                throw e
            } catch (e: Exception) {
                MeshLog.e("Map download failed", e)
                _state.update { it?.copy(running = false, error = e.message ?: "Download failed") }
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    private fun MutableStateFlow<State?>.update(change: (State?) -> State?) { value = change(value) }

    private fun download(initial: State) {
        dir.mkdirs()
        val part = File(dir, initial.fileName + ".part")
        val dest = File(dir, initial.fileName)
        var attempt = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            val conn = URL(initial.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "NodeWave")
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code == 416 && have > 0 && attempt == 0) {
                    // Nothing left to send: the .part is either already the whole file (a finish that was cut short)
                    // or not valid for this file. Keep it if it checks out, otherwise start again.
                    attempt++
                    if (looksComplete(initial.fileName, part)) {
                        finish(initial, part, dest, have)
                        return
                    }
                    part.delete()
                    continue
                }
                if (code != 200 && code != 206) throw IOException("The server answered $code")
                val resumed = code == 206
                val remaining = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
                val total = if (remaining > 0) remaining + if (resumed) have else 0L else 0L
                if (total > 0) {
                    val needed = total - if (resumed) have else 0L
                    if (dir.usableSpace < needed + FREE_MARGIN) throw IOException("Not enough free space (needs ${needed / 1_000_000} MB)")
                }
                var written = if (resumed) have else 0L
                _state.update { it?.copy(bytes = written, total = total) }
                var lastReport = 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, resumed).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            val now = System.nanoTime()
                            if (now - lastReport > 250_000_000L) {
                                lastReport = now
                                _state.update { it?.copy(bytes = written) }
                                if (job?.isActive == false) throw CancellationException()
                            }
                        }
                    }
                }
                if (total > 0 && written != total) throw IOException("The connection closed early ($written of $total bytes)")
                if (!looksComplete(initial.fileName, part)) {
                    part.delete()
                    throw IOException("That file is not a Mapsforge map")
                }
                finish(initial, part, dest, written)
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun looksComplete(fileName: String, part: File): Boolean {
        if (MapFiles.kindOf(fileName) != MapFiles.Kind.MAPSFORGE) return true
        // InputStream.readNBytes needs API 33, and the app runs on Android 10
        val header = ByteArray(32)
        val n = part.inputStream().use { it.read(header) }
        return n > 0 && MapFiles.isMapsforge(header.copyOf(n))
    }

    private fun finish(initial: State, part: File, dest: File, written: Long) {
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not save the map")
        _state.update { it?.copy(bytes = written, total = written, running = false, finished = true) }
        onDone(initial.fileName)
    }

    private companion object {
        const val FREE_MARGIN = 50L * 1024 * 1024
    }
}
