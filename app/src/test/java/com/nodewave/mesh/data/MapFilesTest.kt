package com.nodewave.mesh.data

import com.nodewave.mesh.data.MapFiles.Kind
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class MapFilesTest {
    // ------------------------------------------------------------ names and kinds

    @Test
    fun onlyHttpsLinksToMapFilesAreAccepted() {
        assertEquals("italy.map", MapFiles.fileNameFor("https://download.mapsforge.org/maps/v5/europe/italy.map"))
        assertEquals("a.mbtiles", MapFiles.fileNameFor("  https://example.org/x/a.mbtiles  "))
        assertNull(MapFiles.fileNameFor("http://example.org/italy.map"))
        assertEquals("italy.map", MapFiles.fileNameFor("http://example.org/italy.map", allowHttp = true))
        assertNull(MapFiles.fileNameFor("https://example.org/page.html"))
        assertNull(MapFiles.fileNameFor("https://example.org/"))
        assertNull(MapFiles.fileNameFor("file:///sdcard/italy.map"))
        assertNull(MapFiles.fileNameFor("not a url"))
    }

    @Test
    fun hostileFileNamesAreMadeSafe() {
        val name = MapFiles.fileNameFor("https://example.org/a/..%2F..%2Fevil%20name.map")!!
        assertFalse(name.contains('/') || name.contains('%') || name.contains(' '))
        assertEquals(Kind.MAPSFORGE, MapFiles.kindOf(name))
    }

    @Test
    fun signatureCheck() {
        assertTrue(MapFiles.isMapsforge("mapsforge binary OSM and more".toByteArray()))
        assertFalse(MapFiles.isMapsforge("<html>not a map</html>".toByteArray()))
        assertFalse(MapFiles.isMapsforge(ByteArray(3)))
    }

    // ------------------------------------------------------------ downloader against a local server

    private lateinit var dir: File
    private lateinit var server: HttpServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val content = ("mapsforge binary OSM" + "x".repeat(300_000)).toByteArray()
    @Volatile private var served = 0
    @Volatile private var lastRange: String? = null
    @Volatile private var body: ByteArray = content

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("maps").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val data = body
            val range = ex.requestHeaders.getFirst("Range")
            lastRange = range
            if (range != null && range.startsWith("bytes=")) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                if (from >= data.size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                ex.sendResponseHeaders(206, (data.size - from).toLong())
                ex.responseBody.use { it.write(data, from, data.size - from); served += data.size - from }
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { it.write(data); served += data.size }
            }
        }
        server.start()
    }

    @After
    fun tearDown() { server.stop(0); dir.deleteRecursively() }

    private fun url(name: String) = "http://127.0.0.1:${server.address.port}/$name"

    private fun runDownload(name: String, done: MutableList<String> = mutableListOf()): MapDownloader.State = runBlocking {
        val d = MapDownloader(dir, scope, allowHttp = true) { done += it }
        assertTrue(d.start(url(name)))
        withTimeout(10_000) { while (d.isRunning) kotlinx.coroutines.delay(20) }
        d.state.value!!
    }

    @Test
    fun aMapIsDownloadedAndOnlyThenAppearsUnderItsName() {
        val done = mutableListOf<String>()
        val state = runDownload("test.map", done)
        assertTrue(state.error ?: "", state.finished)
        assertEquals(listOf("test.map"), done)
        assertEquals(content.size.toLong(), File(dir, "test.map").length())
        assertFalse(File(dir, "test.map.part").exists())
    }

    @Test
    fun aStoppedDownloadResumesWhereItLeftOff() {
        val half = content.size / 2
        File(dir, "test.map.part").writeBytes(content.copyOf(half))
        served = 0
        val state = runDownload("test.map")
        assertTrue(state.error ?: "", state.finished)
        assertEquals("bytes=$half-", lastRange)
        assertEquals(content.size - half, served)
        assertTrue(content.contentEquals(File(dir, "test.map").readBytes()))
    }

    @Test
    fun aPartThatIsAlreadyCompleteIsKeptNotDownloadedAgain() {
        // the finish of an earlier run was cut short: the server has nothing more to send (416)
        File(dir, "test.map.part").writeBytes(content)
        served = 0
        val done = mutableListOf<String>()
        val state = runDownload("test.map", done)
        assertTrue(state.error ?: "", state.finished)
        assertEquals(0, served)
        assertEquals(listOf("test.map"), done)
        assertTrue(content.contentEquals(File(dir, "test.map").readBytes()))
    }

    @Test
    fun aPartThatIsNotValidForTheFileIsDiscardedOnA416() {
        File(dir, "test.map.part").writeBytes("garbage".repeat(100_000).toByteArray()) // longer than the file
        val state = runDownload("test.map")
        assertTrue(state.error ?: "", state.finished)
        assertTrue(content.contentEquals(File(dir, "test.map").readBytes()))
    }

    @Test
    fun aFileThatIsNotAMapIsRefusedAndNotKept() {
        body = "<html>login required</html>".toByteArray()
        val done = mutableListOf<String>()
        val state = runDownload("test.map", done)
        assertFalse(state.finished)
        assertNotNull(state.error)
        assertTrue(done.isEmpty())
        assertFalse(File(dir, "test.map").exists())
        assertFalse(File(dir, "test.map.part").exists())
    }

    @Test
    fun anUnacceptableLinkIsRejectedWithoutTouchingTheNetwork() = runBlocking {
        val d = MapDownloader(dir, scope) { }
        assertFalse(d.start("http://127.0.0.1:${server.address.port}/test.map")) // http, not allowed here
        assertNotNull(d.state.value?.error)
        assertEquals(0, served)
    }
}
