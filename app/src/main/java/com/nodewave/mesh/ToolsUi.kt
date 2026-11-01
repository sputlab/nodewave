package com.nodewave.mesh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.Group
import com.nodewave.mesh.protocol.GroupCodec
import com.nodewave.mesh.protocol.GroupState
import com.nodewave.mesh.protocol.NodeEntry
import com.nodewave.mesh.protocol.groupTimeline
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalRed
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Test-grade screens for verifying contacts and group chat; the real interface will replace them. */
@Composable
internal fun ContactsTab(vm: MainViewModel, nodes: List<NodeEntry>, verified: Set<String>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val qr = remember { qrBitmap(vm.contactCodeText(), 600) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "MY CONTACT CODE") {
            Text("Let the other person scan this, and scan theirs: you each confirm who the other is.", color = TacticalDim, fontFamily = Mono, fontSize = 11.sp)
            Image(qr.asImageBitmap(), "Contact code of ${vm.localNodeName}", Modifier.size(240.dp).align(Alignment.CenterHorizontally))
            Text("${vm.localNodeName}  ${vm.localNodeId.chunked(4).joinToString(" ")}", color = Color.White, fontFamily = Mono, fontSize = 11.sp)
        }
        Panel(title = "VERIFY A CONTACT") {
            Button(onClick = { result = null; scanning = true }, modifier = Modifier.fillMaxWidth()) {
                Text("SCAN CONTACT CODE", fontFamily = Mono, fontWeight = FontWeight.Bold)
            }
            result?.let { Text(it, color = if (it.startsWith("Verified")) TacticalGreen else TacticalRed, fontFamily = Mono, fontSize = 12.sp) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(nodes) { n ->
                    val ok = n.id in verified
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.name, color = Color.White, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(if (ok) "VERIFIED" else "not verified", color = if (ok) TacticalGreen else TacticalAmber, fontFamily = Mono, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if (scanning) {
        QrScannerDialog(
            onCode = { text -> scanning = false; scope.launch { result = vm.verifyContact(text) } },
            onDismiss = { scanning = false }
        )
    }
}

@Composable
internal fun GroupsTab(
    vm: MainViewModel,
    groups: List<Group>,
    nodes: List<NodeEntry>,
    messages: List<ChatMessage>,
    selectedGroupId: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf<Group?>(null) }
    var creating by remember { mutableStateOf(false) }
    val selected = groups.firstOrNull { it.id == selectedGroupId && it.state == GroupState.JOINED }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(title = "GROUPS (${groups.size})", action = {
            TextButton(onClick = { creating = true }, enabled = nodes.isNotEmpty()) { Text("NEW", color = TacticalAmber, fontFamily = Mono, fontSize = 11.sp) }
        }) {
            if (groups.isEmpty()) Text("No groups yet.", color = TacticalDim, fontFamily = Mono, fontSize = 12.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups) { g ->
                    val admin = g.adminId == vm.localNodeId
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xFF1B241E), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(g.name + if (g.id == selected?.id) "  ◀" else "", color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            g.members.joinToString(", ") { if (it == vm.localNodeId) "you" else vm.nameOf(it) } + if (g.state == GroupState.INVITED) "  · invited by ${vm.nameOf(g.adminId)}" else "",
                            color = TacticalDim, fontFamily = Mono, fontSize = 10.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (g.state == GroupState.INVITED) {
                                Button(onClick = { vm.acceptInvite(g.id) }) { Text("ACCEPT", fontFamily = Mono, fontSize = 11.sp) }
                                OutlinedButton(onClick = { vm.leaveGroup(g.id) }, border = BorderStroke(1.dp, TacticalRed)) { Text("DECLINE", color = TacticalRed, fontFamily = Mono, fontSize = 11.sp) }
                            } else {
                                OutlinedButton(onClick = { vm.selectGroup(g.id) }, border = BorderStroke(1.dp, TacticalGreen)) { Text("OPEN", color = TacticalGreen, fontFamily = Mono, fontSize = 11.sp) }
                                if (admin) OutlinedButton(onClick = { editing = g }, border = BorderStroke(1.dp, TacticalAmber)) { Text("MEMBERS", color = TacticalAmber, fontFamily = Mono, fontSize = 11.sp) }
                                OutlinedButton(onClick = { vm.leaveGroup(g.id) }, border = BorderStroke(1.dp, TacticalRed)) { Text("LEAVE", color = TacticalRed, fontFamily = Mono, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        }
        if (selected != null) {
            val timeline = groupTimeline(messages, selected.id)
            Panel(title = "GROUP: ${selected.name}", modifier = Modifier.weight(1f, fill = false)) {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(timeline, key = { it.key }) { line ->
                        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(line.time))
                        Text(
                            "[$time] ${if (line.outgoing) "→ you" else "← ${line.senderName}"}", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp
                        )
                        Row {
                            Text("» ${line.text}", color = if (line.outgoing) Color.White else TacticalGreen, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
                            if (line.outgoing) {
                                val label = "  ${line.delivered}/${line.total}" + if (line.failed > 0) " (${line.failed} failed)" else ""
                                Text(label, color = if (line.delivered == line.total) TacticalGreen else if (line.failed > 0) TacticalRed else TacticalAmber, fontFamily = Mono, fontSize = 10.sp)
                            }
                        }
                    }
                }
                var text by remember(selected.id) { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= GroupCodec.MAX_TEXT_BYTES) text = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp),
                        placeholder = { Text("To ${selected.name}…", fontFamily = Mono, fontSize = 12.sp) }
                    )
                    Button(onClick = { if (vm.sendGroupText(selected.id, text)) text = "" }, enabled = enabled && text.isNotBlank()) {
                        Text("SEND", fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (creating) {
        MemberDialog(
            title = "New group", initialName = "", initialMembers = emptySet(), candidates = nodes.map { it.id to it.name },
            onDismiss = { creating = false },
            onConfirm = { name, members -> creating = false; vm.createGroup(name, members) }
        )
    }
    editing?.let { g ->
        val candidates = (nodes.map { it.id to it.name } + g.members.map { it to vm.nameOf(it) }).distinctBy { it.first }.filter { it.first != vm.localNodeId }
        MemberDialog(
            title = "Members of ${g.name}", initialName = g.name, initialMembers = g.members.toSet() - vm.localNodeId, candidates = candidates,
            nameEditable = false,
            onDismiss = { editing = null },
            onConfirm = { _, members -> editing = null; vm.updateGroupMembers(g.id, members) }
        )
    }
}

@Composable
private fun MemberDialog(
    title: String,
    initialName: String,
    initialMembers: Set<String>,
    candidates: List<Pair<String, String>>,
    nameEditable: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String, Set<String>) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var chosen by remember { mutableStateOf(initialMembers) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = Mono) },
        text = {
            Column {
                if (nameEditable) {
                    OutlinedTextField(
                        value = name, onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= GroupCodec.MAX_NAME_BYTES) name = it },
                        singleLine = true, label = { Text("Group name", fontFamily = Mono) }
                    )
                }
                candidates.forEach { (id, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = id in chosen, onCheckedChange = { on -> chosen = if (on) chosen + id else chosen - id })
                        Text(label, fontFamily = Mono, fontSize = 13.sp)
                    }
                }
                Text("Up to ${GroupCodec.MAX_MEMBERS - 1} other members.", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, chosen) }, enabled = chosen.isNotEmpty() && chosen.size < GroupCodec.MAX_MEMBERS && name.isNotBlank()) {
                Text("OK", fontFamily = Mono)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", fontFamily = Mono) } }
    )
}
