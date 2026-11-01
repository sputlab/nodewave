package com.nodewave.mesh

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.nodewave.mesh.crypto.ContactCode
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.Group
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.protocol.NodeEntry
import com.nodewave.mesh.transport.BenchResult
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.transport.TransportState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val verifiedPeers: StateFlow<Set<String>> = messenger.verifiedPeers
    val groups: StateFlow<List<Group>> = messenger.groups
    val logLines: StateFlow<List<String>> = MeshLog.lines

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

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

    // ---- contacts

    /** The text of this node's contact QR code. */
    fun contactCodeText(): String = ContactCode(engine.identity.publicKey, localNodeName).encode()

    /** Verifies the contact in a scanned QR [text]; returns what to tell the user. */
    suspend fun verifyContact(text: String): String {
        val code = ContactCode.decode(text) ?: return "Not a NodeWave contact code"
        return when (val r = messenger.verifyContact(code)) {
            is MeshMessenger.VerifyResult.Verified -> "Verified: ${r.name} [${r.nodeId.take(6)}]"
            MeshMessenger.VerifyResult.OwnCode -> "That is your own code"
            MeshMessenger.VerifyResult.KeyMismatch -> "WARNING: ${code.name} [${code.nodeId.take(6)}] uses a different key than the one we already know. Do not trust it."
        }
    }

    // ---- groups

    fun nameOf(nodeId: String): String = nodes.value.firstOrNull { it.id == nodeId }?.name ?: nodeId.take(6)

    fun selectGroup(groupId: String) {
        _selectedGroupId.value = if (_selectedGroupId.value == groupId) null else groupId
    }

    fun createGroup(name: String, members: Set<String>): Boolean =
        messenger.createGroup(name, members)?.also { _selectedGroupId.value = it.id } != null

    fun updateGroupMembers(groupId: String, members: Set<String>) = messenger.updateGroupMembers(groupId, members)
    fun acceptInvite(groupId: String) = messenger.acceptInvite(groupId)

    fun leaveGroup(groupId: String) {
        if (_selectedGroupId.value == groupId) _selectedGroupId.value = null
        messenger.leaveGroup(groupId)
    }

    fun sendGroupText(groupId: String, text: String) = text.trim().takeIf { it.isNotEmpty() }?.let { messenger.sendGroupText(groupId, it) } ?: false

    // ---- link benchmark

    private val _benchResults = MutableStateFlow<List<BenchResult>>(emptyList())
    val benchResults: StateFlow<List<BenchResult>> = _benchResults.asStateFlow()
    private val _benchBusy = MutableStateFlow(false)
    val benchBusy: StateFlow<Boolean> = _benchBusy.asStateFlow()
    private val _benchRepeatPeer = MutableStateFlow<String?>(null)
    val benchRepeatPeer: StateFlow<String?> = _benchRepeatPeer.asStateFlow()
    private var repeatJob: Job? = null

    private suspend fun benchOnce(peerId: String) {
        if (!_benchBusy.compareAndSet(expect = false, update = true)) return
        try {
            val result = engine.benchmark.benchmark(peerId)
            _benchResults.update { (listOf(result) + it).take(40) }
        } finally {
            _benchBusy.value = false
        }
    }

    fun runBench(peerId: String) {
        engine.scope.launch { benchOnce(peerId) }
    }

    /** Repeats the benchmark against [peerId] every 20 s (walk away and watch the numbers); null stops it. */
    fun setBenchRepeat(peerId: String?) {
        repeatJob?.cancel()
        _benchRepeatPeer.value = peerId
        if (peerId == null) return
        repeatJob = engine.scope.launch {
            while (true) {
                benchOnce(peerId)
                delay(20_000)
            }
        }
    }
}
