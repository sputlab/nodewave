package com.nodewave.mesh.ui.chats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodewave.mesh.MainViewModel
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.Conversation
import com.nodewave.mesh.protocol.Group
import com.nodewave.mesh.protocol.GroupCodec
import com.nodewave.mesh.protocol.GroupState
import com.nodewave.mesh.protocol.KnownNode
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.protocol.MessageStatus
import com.nodewave.mesh.protocol.groupTimeline
import com.nodewave.mesh.transport.TransportState
import com.nodewave.mesh.ui.components.Chip
import com.nodewave.mesh.ui.components.NodeAvatar
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacPanel
import com.nodewave.mesh.ui.components.TacTextField
import com.nodewave.mesh.ui.components.ago
import com.nodewave.mesh.ui.components.avatarColor
import com.nodewave.mesh.ui.components.clock
import com.nodewave.mesh.ui.components.day
import com.nodewave.mesh.ui.components.lastSeenText
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TacticalAmber
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalLine
import com.nodewave.mesh.ui.theme.TacticalRed
import com.nodewave.mesh.ui.theme.TacticalSurface
import com.nodewave.mesh.ui.theme.TacticalSurfaceHigh
import com.nodewave.mesh.ui.theme.TacticalText

@Composable
fun ChatsScreen(vm: MainViewModel, openKey: String?, onOpen: (String?) -> Unit) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val known by vm.knownNodes.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    if (openKey != null) {
        BackHandler { onOpen(null) }
        ConversationView(vm, openKey, conversations.firstOrNull { it.key == openKey }, onBack = { onOpen(null) })
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TacButton("New chat", { picking = true }, Modifier.weight(1f), TacKind.PRIMARY)
                TacButton("New group", { creating = true }, Modifier.weight(1f), TacKind.AMBER)
            }
            if (conversations.isEmpty()) {
                TacPanel(brackets = true) {
                    Text("No conversations yet", color = TacticalText, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Start a chat with a node in range, or create a group. Messages are end-to-end encrypted and cross the mesh through other phones when the destination is out of reach.",
                        color = TacticalDim, fontFamily = Sans, fontSize = 13.sp
                    )
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(conversations, key = { it.key }) { c ->
                    val node = c.peerId?.let { id -> known.firstOrNull { it.id == id } }
                    ConversationCard(c, node) { onOpen(c.key) }
                }
            }
        }
    }

    if (picking) {
        NodePickerDialog("Chat with", known, onDismiss = { picking = false }) { id -> picking = false; onOpen(Conversation.personalKey(id)) }
    }
    if (creating) {
        MemberDialog(
            title = "New group", nameEditable = true, initialName = "", initialMembers = emptySet(), candidates = known, selfId = vm.localNodeId,
            onDismiss = { creating = false },
            onConfirm = { name, members -> creating = false; vm.createGroupId(name, members)?.let { onOpen(Conversation.groupKey(it)) } }
        )
    }
}

@Composable
private fun ConversationCard(c: Conversation, node: KnownNode?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().background(TacticalSurface, shape).border(1.dp, if (c.unread > 0 || c.invited) TacticalGreen.copy(alpha = 0.5f) else TacticalLine, shape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NodeAvatar(c.peerId ?: c.groupId ?: c.key, c.title, verified = node?.verified == true, dim = node != null && !node.visible)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.title, color = TacticalText, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.isGroup) Chip("group · ${c.memberCount}", TacticalAmber)
                if (node?.visible == true) Chip("in range", TacticalGreen)
            }
            Text(
                (if (c.lastOutgoing) "You: " else "") + c.lastText, color = if (c.unread > 0) TacticalText else TacticalDim,
                fontFamily = Sans, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (c.lastTime > 0) Text(ago(c.lastTime), color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
            if (c.invited) Chip("invite", TacticalAmber, filled = true)
            else if (c.unread > 0) Box(Modifier.background(TacticalGreen, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text("${c.unread}", color = Color.Black, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- conversation

@Composable
private fun ConversationView(vm: MainViewModel, key: String, summary: Conversation?, onBack: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val known by vm.knownNodes.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val state by vm.transportState.collectAsStateWithLifecycle()
    val isGroup = key.startsWith("g:")
    val id = key.substring(2)
    val group = if (isGroup) groups.firstOrNull { it.id == id } else null
    val node = if (!isGroup) known.firstOrNull { it.id == id } else null
    val title = group?.name ?: node?.name ?: summary?.title ?: id.take(6)
    var info by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    val personal = remember(messages, id) { if (isGroup) emptyList() else messages.filter { it.groupId == null && it.peerId == id } }
    val lines = remember(messages, id) { if (isGroup) groupTimeline(messages, id) else emptyList() }
    val count = if (isGroup) lines.size else personal.size
    LaunchedEffect(key, count) { vm.markRead(key) }

    if (isGroup && group == null) { // left or removed while open
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹ BACK", color = TacticalGreen, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            NodeAvatar(id, title, 34.dp, verified = node?.verified == true, dim = node != null && !node.visible)
            Column(Modifier.weight(1f)) {
                Text(title, color = TacticalText, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        isGroup -> "${group?.members?.size ?: 0} members"
                        node?.visible == true -> (if (node.direct) "in range · ${node.rssi ?: "--"} dBm" else "via ${node.viaName ?: "relay"}") + if (node.secure) " · encrypted" else ""
                        else -> "out of range"
                    },
                    color = TacticalDim, fontFamily = Mono, fontSize = 10.sp
                )
            }
            if (isGroup) TacButton("Group", { info = true }, small = true)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).background(TacticalLine).padding(0.5.dp))

        if (isGroup && group?.state == GroupState.INVITED) {
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.Center) {
                TacPanel(title = "Invitation", accent = TacticalAmber, brackets = true) {
                    Text("${vm.nameOf(group.adminId)} invited you to “${group.name}”.", color = TacticalText, fontFamily = Sans, fontSize = 14.sp)
                    Text("Members: " + group.members.joinToString { if (it == vm.localNodeId) "you" else vm.nameOf(it) }, color = TacticalDim, fontFamily = Sans, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TacButton("Accept", { vm.acceptInvite(id) }, Modifier.weight(1f), TacKind.PRIMARY)
                        TacButton("Decline", { vm.leaveGroup(id); onBack() }, Modifier.weight(1f), TacKind.DANGER)
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                if (isGroup) items(lines, key = { it.key }) { l ->
                    Bubble(l.outgoing, if (l.outgoing) null else l.senderName, l.senderId, l.text, l.time,
                        status = if (l.outgoing) "${l.delivered}/${l.total} delivered" + if (l.failed > 0) " · ${l.failed} failed" else "" else null,
                        statusColor = if (!l.outgoing) TacticalDim else if (l.failed > 0) TacticalRed else if (l.delivered == l.total) TacticalGreen else TacticalAmber)
                } else items(personal, key = { it.key }) { m -> PersonalLine(vm, m) }
            }
            val recording by vm.voice.recording.collectAsStateWithLifecycle()
            val voiceSettings by vm.settings.collectAsStateWithLifecycle()
            VoiceNotice(vm)
            if (recording !is com.nodewave.mesh.Recording.Idle) RecordingBar(vm, voiceSettings.voiceHoldToRecord)
            else Composer(
                enabled = state == TransportState.ACTIVE, placeholder = "Message $title",
                mic = if (isGroup) null else ({ MicButton(vm, id, state == TransportState.ACTIVE) }),
                maxBytes = if (isGroup) GroupCodec.MAX_TEXT_BYTES else MeshMessenger.MAX_TEXT_BYTES,
                onPing = if (isGroup) null else ({ vm.pingPeer(id) }),
                onSend = { text -> if (isGroup) vm.sendGroupText(id, text) else vm.sendToPeer(id, text) }
            )
        }
    }

    if (info && group != null) GroupInfo(vm, group, known, onClose = { info = false }, onEdit = { info = false; editing = true }, onLeave = { info = false; vm.leaveGroup(id); onBack() })
    if (editing && group != null) {
        MemberDialog(
            title = "Members of ${group.name}", nameEditable = false, initialName = group.name,
            initialMembers = group.members.toSet() - vm.localNodeId,
            candidates = known + group.members.filter { m -> known.none { it.id == m } && m != vm.localNodeId }.map { placeholderNode(it, vm.nameOf(it)) },
            selfId = vm.localNodeId, onDismiss = { editing = false },
            onConfirm = { _, members -> editing = false; vm.updateGroupMembers(group.id, members) }
        )
    }
}

private fun placeholderNode(id: String, name: String) = KnownNode(id, name, name, null, false, false, false, false, false, null, null, 0, 0, null)

@Composable
private fun PersonalLine(vm: MainViewModel, m: ChatMessage) {
    if (m.kind == com.nodewave.mesh.protocol.MessageKind.VOICE) {
        VoiceBubble(
            vm, m, statusText = if (m.outgoing) statusLabel(m.status) + if (m.hops > 0) " · ${m.hops} relay" else "" else if (m.hops > 0) "${m.hops} relay" else null,
            statusColor = when (m.status) { MessageStatus.DELIVERED -> TacticalGreen; MessageStatus.FAILED -> TacticalRed; MessageStatus.RECEIVED -> TacticalDim; else -> TacticalAmber }
        )
        return
    }
    if (m.isPing) {
        Text(
            (if (m.outgoing) "PING SENT" else "PING RECEIVED") + " · ${clock(m.time)}" + if (m.outgoing) " · ${statusLabel(m.status)}" else "",
            color = TacticalDim, fontFamily = Mono, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        return
    }
    val relays = if (m.hops > 0) " · ${m.hops} relay" else ""
    Bubble(
        m.outgoing, null, m.peerId, m.text, m.time,
        status = if (m.outgoing) statusLabel(m.status) + relays else relays.removePrefix(" · ").ifEmpty { null },
        statusColor = when (m.status) { MessageStatus.DELIVERED -> TacticalGreen; MessageStatus.FAILED -> TacticalRed; MessageStatus.RECEIVED -> TacticalDim; else -> TacticalAmber }
    )
}

private fun statusLabel(s: MessageStatus) = when (s) {
    MessageStatus.SENDING -> "sending…"; MessageStatus.SENT -> "sent"; MessageStatus.DELIVERED -> "delivered"; MessageStatus.FAILED -> "failed"; MessageStatus.RECEIVED -> ""
}

@Composable
private fun Bubble(outgoing: Boolean, sender: String?, senderId: String, text: String, time: Long, status: String?, statusColor: Color) {
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = if (outgoing) 14.dp else 3.dp, bottomEnd = if (outgoing) 3.dp else 14.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(if (outgoing) TacticalGreen.copy(alpha = 0.13f) else TacticalSurfaceHigh, shape)
                .border(1.dp, if (outgoing) TacticalGreen.copy(alpha = 0.45f) else TacticalLine, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (sender != null) Text(sender, color = avatarColor(senderId), fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(text, color = TacticalText, fontFamily = Sans, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                if (!status.isNullOrEmpty()) Text(status, color = statusColor, fontFamily = Mono, fontSize = 9.sp)
                Text(clock(time), color = TacticalDim, fontFamily = Mono, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun Composer(enabled: Boolean, placeholder: String, maxBytes: Int, onPing: (() -> Unit)?, onSend: (String) -> Boolean, mic: (@Composable () -> Unit)? = null) {
    var text by remember { mutableStateOf("") }
    fun send() { if (text.isNotBlank() && onSend(text)) text = "" }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TacTextField(
            text, { if (it.toByteArray(Charsets.UTF_8).size <= maxBytes) text = it }, Modifier.weight(1f),
            placeholder = if (enabled) placeholder else "Radio is off", enabled = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Send, onImeAction = { if (enabled) send() }
        )
        if (onPing != null) TacButton("Ping", onPing, enabled = enabled, kind = TacKind.AMBER, small = true)
        if (mic != null && text.isBlank()) mic()
        TacButton("Send", ::send, enabled = enabled && text.isNotBlank(), kind = TacKind.PRIMARY, small = true)
    }
}

// ---------------------------------------------------------------------------------------------- group info and dialogs

@Composable
private fun GroupInfo(vm: MainViewModel, group: Group, known: List<KnownNode>, onClose: () -> Unit, onEdit: () -> Unit, onLeave: () -> Unit) {
    val admin = group.adminId == vm.localNodeId
    AlertDialog(
        onDismissRequest = onClose, containerColor = TacticalSurface,
        title = { Text(group.name, fontFamily = Mono, fontWeight = FontWeight.Bold, color = TacticalText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (admin) "You manage this group." else "Managed by ${vm.nameOf(group.adminId)}.", color = TacticalDim, fontFamily = Sans, fontSize = 13.sp)
                group.members.forEach { m ->
                    val n = known.firstOrNull { it.id == m }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NodeAvatar(m, if (m == vm.localNodeId) vm.localNodeName else vm.nameOf(m), 34.dp, verified = n?.verified == true)
                        Column(Modifier.weight(1f)) {
                            Text(if (m == vm.localNodeId) "You" else vm.nameOf(m), color = TacticalText, fontFamily = Sans, fontSize = 14.sp)
                            Text(if (n?.visible == true) "in range" else "out of range", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
                        }
                        if (m == group.adminId) Chip("admin", TacticalAmber)
                    }
                }
                Text("Every message is encrypted separately for each member. Members out of reach receive nothing until they are back in range.", color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
            }
        },
        confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { if (admin) TacButton("Members", onEdit, small = true, kind = TacKind.AMBER); TacButton("Leave", onLeave, small = true, kind = TacKind.DANGER) } },
        dismissButton = { TextButton(onClick = onClose) { Text("CLOSE", fontFamily = Mono, color = TacticalDim) } }
    )
}

@Composable
internal fun NodePickerDialog(title: String, known: List<KnownNode>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = TacticalSurface,
        title = { Text(title, fontFamily = Mono, color = TacticalText) },
        text = {
            if (known.isEmpty()) Text("No nodes known yet. Start the radio and wait for a node to appear.", color = TacticalDim, fontFamily = Sans)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(known, key = { it.id }) { n ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(n.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NodeAvatar(n.id, n.name, 36.dp, verified = n.verified, dim = !n.visible)
                        Column(Modifier.weight(1f)) {
                            Text(n.name, color = TacticalText, fontFamily = Sans, fontSize = 15.sp)
                            Text(if (n.visible) "in range" else lastSeenText(n.lastSeen), color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", fontFamily = Mono, color = TacticalDim) } }
    )
}

@Composable
internal fun MemberDialog(
    title: String, nameEditable: Boolean, initialName: String, initialMembers: Set<String>, candidates: List<KnownNode>, selfId: String,
    onDismiss: () -> Unit, onConfirm: (String, Set<String>) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var chosen by remember { mutableStateOf(initialMembers) }
    val list = candidates.filter { it.id != selfId }.distinctBy { it.id }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = TacticalSurface,
        title = { Text(title, fontFamily = Mono, color = TacticalText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nameEditable) TacTextField(name, { if (it.toByteArray(Charsets.UTF_8).size <= GroupCodec.MAX_NAME_BYTES) name = it }, label = "Group name")
                if (list.isEmpty()) Text("No nodes known yet.", color = TacticalDim, fontFamily = Sans)
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(list, key = { it.id }) { n ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (n.id in chosen) chosen - n.id else chosen + n.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = n.id in chosen, onCheckedChange = { on -> chosen = if (on) chosen + n.id else chosen - n.id },
                                colors = CheckboxDefaults.colors(checkedColor = TacticalGreen, checkmarkColor = Color.Black, uncheckedColor = TacticalDim)
                            )
                            NodeAvatar(n.id, n.name, 30.dp, verified = n.verified, dim = !n.visible)
                            Text("  ${n.name}", color = TacticalText, fontFamily = Sans, fontSize = 14.sp)
                        }
                    }
                }
                Text("Up to ${GroupCodec.MAX_MEMBERS - 1} other members.", color = TacticalDim, fontFamily = Mono, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TacButton("OK", { onConfirm(name, chosen) }, kind = TacKind.PRIMARY, small = true, enabled = chosen.isNotEmpty() && chosen.size < GroupCodec.MAX_MEMBERS && name.isNotBlank())
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", fontFamily = Mono, color = TacticalDim) } }
    )
}
