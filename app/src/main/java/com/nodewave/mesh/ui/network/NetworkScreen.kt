package com.nodewave.mesh.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.protocol.ActivityEvent
import com.nodewave.mesh.ui.components.NodeAvatar
import com.nodewave.mesh.ui.components.RadarBlip
import com.nodewave.mesh.ui.components.RadarView
import com.nodewave.mesh.ui.components.SignalBars
import com.nodewave.mesh.ui.components.TacPanel
import com.nodewave.mesh.ui.components.ago
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalCyan
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalLine
import com.nodewave.mesh.ui.theme.TacticalRed
import com.nodewave.mesh.ui.theme.TacticalSurface
import com.nodewave.mesh.ui.theme.TacticalText

@Composable
fun NetworkScreen(vm: MainViewModel, radioOn: Boolean, onChat: (String) -> Unit) {
    val known by vm.knownNodes.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val visible = known.filter { it.visible }
    val direct = visible.count { it.direct }
    val rate = if (stats.delivered + stats.failed == 0) "--" else "${100 * stats.delivered / (stats.delivered + stats.failed)}%"

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("In range", "$direct", TacticalGreen, Modifier.weight(1f))
                Tile("Via relay", "${visible.size - direct}", TacticalAmber, Modifier.weight(1f))
                Tile("Encrypted", "${visible.count { it.secure }}", TacticalCyan, Modifier.weight(1f))
            }
        }
        item {
            TacPanel(title = "Radar", brackets = true) {
                if (!radioOn) Text("Radio is off. Start it to see the nodes around you.", color = TacticalDim, fontFamily = Sans, fontSize = 13.sp)
                RadarView(
                    visible.map { RadarBlip(it.id, it.name, it.rssi, it.direct, it.verified) }, radioOn,
                    Modifier.fillMaxWidth().height(260.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(TacticalGreen, "direct"); Legend(TacticalAmber, "via relay"); Legend(TacticalCyan, "verified")
                    Text("closer = stronger", color = TacticalDim, fontFamily = Mono, fontSize = 9.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Received", "${stats.received}", TacticalText, Modifier.weight(1f))
                Tile("Sent", "${stats.sent}", TacticalText, Modifier.weight(1f))
                Tile("Delivered", rate, if (stats.failed > 0) TacticalAmber else TacticalGreen, Modifier.weight(1f))
                Tile("Relayed", "${stats.relayed}", TacticalAmber, Modifier.weight(1f))
            }
        }
        if (visible.isNotEmpty()) item {
            TacPanel(title = "Nodes around you (${visible.size})") {
                visible.forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable { onChat(n.id) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NodeAvatar(n.id, n.name, 34.dp, verified = n.verified)
                        Column(Modifier.weight(1f)) {
                            Text(n.name, color = TacticalText, fontFamily = Sans, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (n.direct) "direct · ${n.rssi ?: "--"} dBm" else "via ${n.viaName ?: "relay"}", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
                        }
                        if (n.direct) SignalBars(n.rssi)
                    }
                }
            }
        }
        item {
            TacPanel(title = "Activity (${activity.size})") {
                if (activity.isEmpty()) Text("Nothing yet. Events from the mesh show up here as they happen.", color = TacticalDim, fontFamily = Sans, fontSize = 13.sp)
            }
        }
        items(activity.take(80)) { e -> EventRow(e) }
    }
}

@Composable
private fun Tile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.background(TacticalSurface, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(value, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(label.uppercase(), color = TacticalDim, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Text(text, color = TacticalDim, fontFamily = Mono, fontSize = 9.sp)
    }
}

@Composable
private fun EventRow(e: ActivityEvent) {
    val (label, color) = when (e.kind) {
        ActivityEvent.Kind.NODE -> "NODE" to TacticalGreen
        ActivityEvent.Kind.MESSAGE -> "MSG" to TacticalText
        ActivityEvent.Kind.RELAY -> "RELAY" to TacticalAmber
        ActivityEvent.Kind.SECURITY -> "KEY" to TacticalCyan
        ActivityEvent.Kind.GROUP -> "GROUP" to TacticalAmber
        ActivityEvent.Kind.POSITION -> "POS" to TacticalCyan
        ActivityEvent.Kind.RADIO -> "RADIO" to TacticalRed
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 3.dp).size(width = 3.dp, height = 28.dp).background(color.copy(alpha = 0.8f), RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(e.text, color = TacticalText, fontFamily = Sans, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$label · ${ago(e.time)}", color = TacticalDim, fontFamily = Mono, fontSize = 9.sp)
        }
    }
}
