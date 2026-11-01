package com.nodewave.mesh

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.NodeEntry
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thin UI adapter: the radio lives in [MeshEngine] / [RadioService], not in the view model. */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = (application as NodeWaveApp).engine
    private val messenger = engine.messenger

    val localNodeId: String = engine.identity.nodeId
    val localNodeName: String = engine.localNodeName

    val transportState: StateFlow<TransportState> = engine.transport.state
    val nodes: StateFlow<List<NodeEntry>> = messenger.nodes
    val keyedPeers: StateFlow<Set<String>> = messenger.keyedPeers
    val ignoreDirect: StateFlow<Boolean> = messenger.ignoreDirect
    val messages: StateFlow<List<ChatMessage>> = messenger.messages
    val logLines: StateFlow<List<String>> = MeshLog.lines

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    /** Requires the Bluetooth runtime permissions to be granted already. */
    fun startRadio() {
        ContextCompat.startForegroundService(getApplication(), RadioService.startIntent(getApplication()))
    }

    fun stopRadio() {
        getApplication<Application>().startService(RadioService.stopIntent(getApplication()))
    }

    fun setIgnoreDirect(enabled: Boolean) = messenger.setIgnoreDirect(enabled)

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

    fun clearHistory() = messenger.clearHistory()
}
