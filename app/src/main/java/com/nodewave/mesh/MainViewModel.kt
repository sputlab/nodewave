package com.nodewave.mesh

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.protocol.NodeIdentity
import com.nodewave.mesh.transport.BleTransport
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.MeshTransport
import com.nodewave.mesh.transport.PeerNode
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val localNodeId: String = NodeIdentity.getOrCreate(application)
    val localNodeName: String = Build.MODEL

    private val transport: MeshTransport = BleTransport(application, localNodeId)
    private val messenger = MeshMessenger(transport, localNodeId, viewModelScope)

    val transportState: StateFlow<TransportState> = transport.state
    val peers: StateFlow<List<PeerNode>> = transport.discoveredPeers
    val messages: StateFlow<List<ChatMessage>> = messenger.messages
    val logLines: StateFlow<List<String>> = MeshLog.lines

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    init {
        MeshLog.d("Local node ${localNodeId.take(6)} ($localNodeName)")
    }

    fun startRadio() {
        viewModelScope.launch { transport.start(localNodeName) }
    }

    fun stopRadio() {
        viewModelScope.launch { transport.stop() }
    }

    fun selectPeer(peerId: String) {
        _selectedPeerId.value = if (_selectedPeerId.value == peerId) null else peerId
    }

    fun ping(peerId: String) = messenger.sendPing(peerId)

    fun broadcastPing() = messenger.broadcastPing()

    fun sendText(text: String) {
        val trimmed = text.trim()
        val target = _selectedPeerId.value
        if (trimmed.isEmpty() || target == null) return
        messenger.sendText(target, trimmed)
    }

    fun broadcastText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) messenger.broadcastText(trimmed)
    }

    override fun onCleared() {
        runBlocking { transport.stop() }
    }
}
