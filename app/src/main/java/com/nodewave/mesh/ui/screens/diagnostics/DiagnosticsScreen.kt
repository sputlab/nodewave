// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.protocol.ActivityEvent
import com.nodewave.mesh.protocol.MeshStats
import com.nodewave.mesh.protocol.NodeEntry
import com.nodewave.mesh.transport.BenchResult
import com.nodewave.mesh.transport.MeshLog
import com.nodewave.mesh.ui.components.Segmented
import com.nodewave.mesh.ui.components.SignalRssiIndicator
import com.nodewave.mesh.ui.components.StatTile
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacticalCard
import com.nodewave.mesh.ui.components.ToggleRow
import com.nodewave.mesh.ui.components.ago
import com.nodewave.mesh.ui.components.clockSeconds
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NodeDeepBlue
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.WaveGreen

@Immutable
data class DiagnosticsUiState(
    val radioOn: Boolean,
    /** The in-app mirror of `adb logcat -s MESH_RADIO`. */
    val logLines: List<String>,
    /** Test switch: pretend no node is in direct range, so every packet has to find a relay. */
    val ignoreDirect: Boolean,
    val stats: MeshStats,
    val activity: List<ActivityEvent>,
    /** Nodes in direct range, the only ones a link can be measured against. */
    val directNodes: List<NodeEntry>,
    val benchResults: List<BenchResult>,
    val benchBusy: Boolean,
    val benchRepeatPeer: String?,
    /** How the last Wi-Fi Direct test went. */
    val fastLinkReport: String? = null
)

sealed interface DiagnosticsAction {
    data class SetIgnoreDirect(val enabled: Boolean) : DiagnosticsAction
    data class RunBench(val peerId: String) : DiagnosticsAction
    data class SetBenchRepeat(val peerId: String?) : DiagnosticsAction
    data class SendTestPhoto(val peerId: String) : DiagnosticsAction
    data class FastLinkTest(val peerId: String) : DiagnosticsAction
}

private enum class Section(val label: String) { CONSOLE("Console"), ACTIVITY("Activity"), BENCH("Link test") }

/**
 * Everything technical, kept out of the operator's way: the raw [MeshLog] console, the mesh activity feed with its
 * counters, and the link benchmark.
 */
@Composable
fun DiagnosticsScreen(state: DiagnosticsUiState, onAction: (DiagnosticsAction) -> Unit, modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableStateOf(Section.CONSOLE) }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(Section.entries.map { it to it.label }, section, { section = it }, Modifier.padding(top = 12.dp))
        when (section) {
            Section.CONSOLE -> Console(state, onAction)
            Section.ACTIVITY -> Activity(state)
            Section.BENCH -> Bench(state, onAction)
        }
    }
}

@Composable
private fun Console(state: DiagnosticsUiState, onAction: (DiagnosticsAction) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.logLines.size) { if (state.logLines.isNotEmpty()) listState.scrollToItem(state.logLines.lastIndex) }
    TacticalCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        ToggleRow(
            "Skip direct links", "Test relaying on a desk: treat every node as out of direct range.",
            state.ignoreDirect, { onAction(DiagnosticsAction.SetIgnoreDirect(it)) }
        )
    }
    TacticalCard(Modifier.fillMaxSize().padding(bottom = 12.dp), title = "${MeshLog.TAG} · ${state.logLines.size} lines", accent = NodeCyan, contentPadding = PaddingValues(12.dp)) {
        if (state.logLines.isEmpty()) Text("Nothing logged yet. Start the radio.", color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
        LazyColumn(state = listState) {
            items(state.logLines) { line ->
                Text(line, color = if (line.contains(" E ")) EmergencyRed else NwTextDim, fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun Activity(state: DiagnosticsUiState) {
    val stats = state.stats
    val rate = if (stats.delivered + stats.failed == 0) "--" else "${100 * stats.delivered / (stats.delivered + stats.failed)}%"
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Received", "${stats.received}", NwText, Modifier.weight(1f))
                StatTile("Sent", "${stats.sent}", NwText, Modifier.weight(1f))
                StatTile("Delivered", rate, WaveGreen, Modifier.weight(1f))
                StatTile("Relayed", "${stats.relayed}", MeshTeal, Modifier.weight(1f))
            }
        }
        if (state.activity.isEmpty()) item {
            Text("Nothing yet. Events from the mesh show up here as they happen.", color = NwTextDim, fontFamily = Sans, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        }
        items(state.activity.take(80)) { e -> EventRow(e) }
    }
}

@Composable
private fun EventRow(e: ActivityEvent) {
    val (label, color) = when (e.kind) {
        ActivityEvent.Kind.NODE -> "NODE" to NodeDeepBlue
        ActivityEvent.Kind.MESSAGE -> "MSG" to NwText
        ActivityEvent.Kind.RELAY -> "RELAY" to MeshTeal
        ActivityEvent.Kind.SECURITY -> "KEY" to NodeCyan
        ActivityEvent.Kind.GROUP -> "GROUP" to MeshTeal
        ActivityEvent.Kind.POSITION -> "POS" to NodeDeepBlue
        ActivityEvent.Kind.RADIO -> "RADIO" to WaveGreen
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 3.dp).size(width = 3.dp, height = 30.dp).background(color.copy(alpha = 0.85f), RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(e.text, color = NwText, fontFamily = Sans, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$label · ${ago(e.time)}", color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
        }
    }
}

/** Measures one direct link: connection time, throughput with and without write acknowledgements, RSSI. */
@Composable
private fun Bench(state: DiagnosticsUiState, onAction: (DiagnosticsAction) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Text("24 KB in each mode over one direct link: connection time, throughput, RSSI.", color = NwTextDim, fontFamily = Sans, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (state.directNodes.isEmpty()) item {
            Text("No direct node in range.", color = NwTextDim, fontFamily = Sans, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        }
        state.fastLinkReport?.let { item { Text(it, color = MeshTeal, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp)) } }
        items(state.directNodes, key = { it.id }) { n ->
            TacticalCard(contentPadding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.name, color = NwText, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    SignalRssiIndicator(n.rssi)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TacButton(if (state.benchBusy) "Running…" else "Run", { onAction(DiagnosticsAction.RunBench(n.id)) }, Modifier.weight(1f), TacKind.PRIMARY, enabled = state.radioOn && !state.benchBusy, compact = true)
                    TacButton("Photo", { onAction(DiagnosticsAction.SendTestPhoto(n.id)) }, Modifier.weight(1f), enabled = state.radioOn, compact = true)
                    TacButton("Wi-Fi", { onAction(DiagnosticsAction.FastLinkTest(n.id)) }, Modifier.weight(1f), enabled = state.radioOn, compact = true)
                }
                ToggleRow(
                    "Repeat every 20 s", "Walk away and watch the numbers.", state.benchRepeatPeer == n.id,
                    { on -> onAction(DiagnosticsAction.SetBenchRepeat(if (on) n.id else null)) }, enabled = state.radioOn
                )
            }
        }
        if (state.benchResults.isNotEmpty()) item {
            TacticalCard(title = "Results (${state.benchResults.size})", accent = NodeCyan) {
                state.benchResults.forEach { r ->
                    Text("[${clockSeconds(r.time)}] ${r.summary()}", color = if (r.ok) NwText else EmergencyRed, fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}
