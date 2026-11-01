package com.nodewave.mesh

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nodewave.mesh.transport.BleTransport
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.MeshTransport
import com.nodewave.mesh.transport.PeerNode
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReceivedMessage(val time: String, val sender: String, val text: String, val rssi: Int?)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val transport: MeshTransport = BleTransport(application)

    val localNodeName: String = Build.MODEL
    val transportState: StateFlow<TransportState> = transport.state
    val peers: StateFlow<List<PeerNode>> = transport.discoveredPeers
    val logLines: StateFlow<List<String>> = MeshLog.lines

    private val _received = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val received: StateFlow<List<ReceivedMessage>> = _received.asStateFlow()

    init {
        viewModelScope.launch {
            transport.incomingPayloads.collect { payload ->
                val text = payload.rawBytes.toString(Charsets.UTF_8)
                MeshLog.d("RECEIVED '$text' from ${payload.senderId}")
                val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                _received.update { it + ReceivedMessage(time, payload.senderId, text, payload.rssi) }
            }
        }
    }

    fun startRadio() {
        viewModelScope.launch { transport.start(localNodeName) }
    }

    fun stopRadio() {
        viewModelScope.launch { transport.stop() }
    }

    fun ping(peerId: String) {
        viewModelScope.launch { transport.send(peerId, PING_TEXT.toByteArray(Charsets.UTF_8)) }
    }

    fun broadcastPing() {
        viewModelScope.launch { transport.broadcast(PING_TEXT.toByteArray(Charsets.UTF_8)) }
    }

    override fun onCleared() {
        runBlocking { transport.stop() }
    }

    companion object {
        const val PING_TEXT = "CIAO - 73"
    }
}
