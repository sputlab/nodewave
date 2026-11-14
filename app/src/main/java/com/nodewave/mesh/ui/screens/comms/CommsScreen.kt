// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.comms

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.Recording
import com.nodewave.mesh.protocol.ChatMessage
import com.nodewave.mesh.protocol.Conversation
import com.nodewave.mesh.protocol.Group
import com.nodewave.mesh.protocol.GroupCodec
import com.nodewave.mesh.protocol.GroupLine
import com.nodewave.mesh.protocol.GroupState
import com.nodewave.mesh.protocol.KnownNode
import com.nodewave.mesh.protocol.MeshMessenger
import com.nodewave.mesh.protocol.MessageKind
import com.nodewave.mesh.protocol.MessageStatus
import com.nodewave.mesh.protocol.SosMessage
import com.nodewave.mesh.ui.components.Chip
import com.nodewave.mesh.ui.components.Hairline
import com.nodewave.mesh.ui.components.NodeAvatar
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacTextField
import com.nodewave.mesh.ui.components.TacticalCard
import com.nodewave.mesh.ui.components.ago
import com.nodewave.mesh.ui.components.avatarColor
import com.nodewave.mesh.ui.components.clock
import com.nodewave.mesh.ui.components.lastSeenText
import com.nodewave.mesh.ui.theme.EmergencyRed
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TouchTarget
import com.nodewave.mesh.ui.theme.WaveGreen
import com.nodewave.mesh.ui.theme.WaveNeon
import java.io.File

/** The open conversation: its messages and everything the composer shows. */
@Immutable
data class ThreadUiState(
    /** One-to-one chat rows; empty for a group. */
    val personal: List<ChatMessage> = emptyList(),
    /** Group chat lines; empty for a person. */
    val lines: List<GroupLine> = emptyList(),
    val recording: Recording = Recording.Idle,
    val holdToRecord: Boolean = true,
    /** A short line above the composer ("this link is too slow for that codec"). */
    val notice: String? = null,
    /** What is happening to a photo being prepared. */
    val photoStage: String? = null,
    /** Key of the voice message being played. */
    val playingKey: String? = null,
    /** Transfer progress 0..1 by message key. */
    val transfers: Map<String, Float> = emptyMap()
)

@Immutable
data class CommsUiState(
    val localId: String,
    val localName: String,
    val radioOn: Boolean,
    val conversations: List<Conversation>,
    val known: List<KnownNode>,
    val groups: List<Group>,
    /** Key of the open conversation, null for the list. */
    val openKey: String? = null,
    val thread: ThreadUiState = ThreadUiState()
) {
    fun nameOf(nodeId: String): String = known.firstOrNull { it.id == nodeId }?.name ?: nodeId.take(6)
}

sealed interface CommsAction {
    data class Open(val key: String?) : CommsAction
    data class MarkRead(val key: String) : CommsAction
    data class SendText(val key: String, val text: String) : CommsAction
    data class Ping(val peerId: String) : CommsAction
    data class CreateGroup(val name: String, val members: Set<String>) : CommsAction
    data class UpdateMembers(val groupId: String, val members: Set<String>) : CommsAction
    data class AcceptInvite(val groupId: String) : CommsAction
    data class LeaveGroup(val groupId: String) : CommsAction
    data class VoiceStart(val peerId: String, val sendWhenDone: Boolean) : CommsAction
    data object VoiceStop : CommsAction
    data object VoiceCancel : CommsAction
    data object VoiceSendReady : CommsAction
    data object VoiceDiscard : CommsAction
    data class TogglePlay(val message: ChatMessage) : CommsAction
    data class RetryBlob(val key: String) : CommsAction
    data class SendPhoto(val peerId: String, val uri: Uri, val deleteAfter: File?) : CommsAction
    data class Notice(val text: String) : CommsAction
}

/** Messages: the list of conversations, or the open one. [loadImage] reads a stored photo for a bubble. */
@Composable
fun CommsScreen(state: CommsUiState, onAction: (CommsAction) -> Unit, loadImage: suspend (String, Int) -> Bitmap?, modifier: Modifier = Modifier) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }

    val key = state.openKey
    if (key != null) {
        BackHandler { onAction(CommsAction.Open(null)) }
        ConversationView(state, key, onAction, loadImage, modifier)
    } else {
        Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TacButton("New chat", { picking = true }, Modifier.weight(1f), TacKind.PRIMARY)
                TacButton("New group", { creating = true }, Modifier.weight(1f), TacKind.SECONDARY)
            }
            if (state.conversations.isEmpty()) {
                TacticalCard(emphasized = true) {
                    Text("No conversations yet", color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(
                        "Start a chat with a node in range, or create a group. Messages are end-to-end encrypted and cross the mesh through other phones when the destination is out of reach.",
                        color = NwTextDim, fontFamily = Sans, fontSize = 14.sp
                    )
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(state.conversations, key = { it.key }) { c ->
                    val node = c.peerId?.let { id -> state.known.firstOrNull { it.id == id } }
                    ConversationCard(c, node) { onAction(CommsAction.Open(c.key)) }
                }
            }
        }
    }

    if (picking) {
        NodePickerDialog("Chat with", state.known, onDismiss = { picking = false }) { id -> picking = false; onAction(CommsAction.Open(Conversation.personalKey(id))) }
    }
    if (creating) {
        MemberDialog(
            title = "New group", nameEditable = true, initialName = "", initialMembers = emptySet(), candidates = state.known, selfId = state.localId,
            onDismiss = { creating = false },
            onConfirm = { name, members -> creating = false; onAction(CommsAction.CreateGroup(name, members)) }
        )
    }
}

@Composable
private fun ConversationCard(c: Conversation, node: KnownNode?, onClick: () -> Unit) {
    val sos = !c.lastOutgoing && SosMessage.isSos(c.lastText)
    val fresh = c.unread > 0 || c.invited
    TacticalCard(onClick = onClick, emphasized = fresh, accent = if (sos && fresh) EmergencyRed else NodeCyan, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NodeAvatar(c.peerId ?: c.groupId ?: c.key, c.title, 46.dp, verified = node?.verified == true, dim = node != null && !node.visible)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.title, color = NwText, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (c.isGroup) Chip("group · ${c.memberCount}", MeshTeal)
                    if (node?.visible == true) Chip("in range", WaveGreen)
                }
                Text(
                    (if (c.lastOutgoing) "You: " else "") + c.lastText, color = if (sos && fresh) EmergencyRed else if (c.unread > 0) NwText else NwTextDim,
                    fontFamily = Sans, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (c.lastTime > 0) Text(ago(c.lastTime), color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
                if (c.invited) Chip("invite", MeshTeal, filled = true)
                else if (c.unread > 0) Box(Modifier.background(if (sos) EmergencyRed else WaveNeon, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("${c.unread}", color = if (sos) Color.White else Color.Black, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- conversation

@Composable
private fun ConversationView(state: CommsUiState, key: String, onAction: (CommsAction) -> Unit, loadImage: suspend (String, Int) -> Bitmap?, modifier: Modifier = Modifier) {
    val thread = state.thread
    val isGroup = key.startsWith("g:")
    val id = key.substring(2)
    val group = if (isGroup) state.groups.firstOrNull { it.id == id } else null
    val node = if (!isGroup) state.known.firstOrNull { it.id == id } else null
    val title = group?.name ?: node?.name ?: state.conversations.firstOrNull { it.key == key }?.title ?: id.take(6)
    var info by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val photoSources = rememberPhotoSources(
        onPhoto = { uri, file -> if (!isGroup) onAction(CommsAction.SendPhoto(id, uri, file)) },
        onNotice = { onAction(CommsAction.Notice(it)) }
    )
    val back = { onAction(CommsAction.Open(null)) }

    val count = if (isGroup) thread.lines.size else thread.personal.size
    LaunchedEffect(key, count) { onAction(CommsAction.MarkRead(key)) }

    if (isGroup && group == null) { // left or removed while open
        LaunchedEffect(Unit) { back() }
        return
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(TouchTarget.Standard).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Back", onClick = back), contentAlignment = Alignment.Center) {
                Text("‹", color = NodeCyan, fontFamily = Sans, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            NodeAvatar(id, title, 38.dp, verified = node?.verified == true, dim = node != null && !node.visible)
            Column(Modifier.weight(1f)) {
                Text(title, color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        isGroup -> "${group?.members?.size ?: 0} members"
                        node?.visible == true -> (if (node.direct) "in range · ${node.rssi ?: "--"} dBm" else "via ${node.viaName ?: "relay"}") + if (node.secure) " · encrypted" else ""
                        else -> "out of range"
                    },
                    color = if (node?.visible == true) WaveGreen else NwTextDim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1
                )
            }
            if (isGroup) TacButton("Group", { info = true }, compact = true)
            else TacButton("Ping", { onAction(CommsAction.Ping(id)) }, enabled = state.radioOn, compact = true)
        }
        Hairline()

        if (isGroup && group?.state == GroupState.INVITED) {
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.Center) {
                TacticalCard(title = "Invitation", accent = MeshTeal, emphasized = true) {
                    Text("${state.nameOf(group.adminId)} invited you to “${group.name}”.", color = NwText, fontFamily = Sans, fontSize = 16.sp)
                    Text("Members: " + group.members.joinToString { if (it == state.localId) "you" else state.nameOf(it) }, color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TacButton("Accept", { onAction(CommsAction.AcceptInvite(id)) }, Modifier.weight(1f), TacKind.PRIMARY)
                        TacButton("Decline", { onAction(CommsAction.LeaveGroup(id)); back() }, Modifier.weight(1f), TacKind.DANGER)
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                if (isGroup) items(thread.lines, key = { it.key }) { l ->
                    Bubble(
                        l.outgoing, if (l.outgoing) null else l.senderName, l.senderId, l.text, l.time,
                        status = if (l.outgoing) "${l.delivered}/${l.total} delivered" + if (l.failed > 0) " · ${l.failed} failed" else "" else null,
                        statusColor = if (!l.outgoing) NwTextDim else if (l.failed > 0) EmergencyRed else if (l.delivered == l.total) WaveGreen else MeshTeal
                    )
                } else items(thread.personal, key = { it.key }) { m -> PersonalLine(m, thread, onAction, loadImage) }
            }
            LaunchedEffect(thread.notice) { if (thread.notice != null) { kotlinx.coroutines.delay(4_000); onAction(CommsAction.Notice("")) } }
            thread.notice?.takeIf { it.isNotEmpty() }?.let { ComposerNote(it) }
            thread.photoStage?.let { ComposerNote("◌ $it") }
            val recording = thread.recording !is Recording.Idle
            Composer(
                enabled = state.radioOn, placeholder = "Message $title",
                maxBytes = if (isGroup) GroupCodec.MAX_TEXT_BYTES else MeshMessenger.MAX_TEXT_BYTES,
                onSend = { text -> onAction(CommsAction.SendText(key, text)) },
                replacement = if (!recording) null else ({ mod ->
                    RecordingBar(
                        thread.recording, thread.holdToRecord, modifier = mod,
                        onCancel = { onAction(CommsAction.VoiceCancel) }, onStop = { onAction(CommsAction.VoiceStop) },
                        onDiscard = { onAction(CommsAction.VoiceDiscard) }, onSend = { onAction(CommsAction.VoiceSendReady) }
                    )
                }),
                // one-to-one only for now: a blob to a group would be one transfer per member
                tools = if (isGroup) null else ({ typing ->
                    if (!recording && !typing) PhotoButton(photoSources, state.radioOn)
                    if (recording || !typing) MicButton(
                        thread.holdToRecord, state.radioOn,
                        onStart = { sendWhenDone -> onAction(CommsAction.VoiceStart(id, sendWhenDone)) },
                        onStop = { onAction(CommsAction.VoiceStop) }, onCancel = { onAction(CommsAction.VoiceCancel) }
                    )
                })
            )
        }
    }

    if (info && group != null) {
        GroupInfo(state, group, onClose = { info = false }, onEdit = { info = false; editing = true }, onLeave = { info = false; onAction(CommsAction.LeaveGroup(id)); back() })
    }
    if (editing && group != null) {
        MemberDialog(
            title = "Members of ${group.name}", nameEditable = false, initialName = group.name,
            initialMembers = group.members.toSet() - state.localId,
            candidates = state.known + group.members.filter { m -> state.known.none { it.id == m } && m != state.localId }.map { placeholderNode(it, state.nameOf(it)) },
            selfId = state.localId, onDismiss = { editing = false },
            onConfirm = { _, members -> editing = false; onAction(CommsAction.UpdateMembers(group.id, members)) }
        )
    }
}

private fun placeholderNode(id: String, name: String) = KnownNode(id, name, name, null, false, false, false, false, false, null, null, 0, 0, null)

@Composable
private fun ComposerNote(text: String) {
    Text(text, color = MeshTeal, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
}

private fun statusColor(s: MessageStatus) = when (s) {
    MessageStatus.DELIVERED -> WaveGreen; MessageStatus.FAILED -> EmergencyRed; MessageStatus.RECEIVED -> NwTextDim; else -> MeshTeal
}

private fun statusLabel(s: MessageStatus) = when (s) {
    MessageStatus.SENDING -> "sending…"; MessageStatus.SENT -> "sent"; MessageStatus.DELIVERED -> "delivered"; MessageStatus.FAILED -> "failed"; MessageStatus.RECEIVED -> ""
}

@Composable
private fun PersonalLine(m: ChatMessage, thread: ThreadUiState, onAction: (CommsAction) -> Unit, loadImage: suspend (String, Int) -> Bitmap?) {
    val relays = if (m.hops > 0) "${m.hops} relay" else null
    val status = listOfNotNull(if (m.outgoing) statusLabel(m.status) else null, relays).joinToString(" · ").ifEmpty { null }
    when {
        m.kind == MessageKind.IMAGE || m.kind == MessageKind.VOICE -> {
            val progress = thread.transfers[m.key] ?: thread.transfers["in:${m.peerId}:${m.messageId}"]
            val retry = { onAction(CommsAction.RetryBlob(m.key)) }
            if (m.kind == MessageKind.IMAGE) ImageBubble(m, progress, status, statusColor(m.status), loadImage, retry)
            else VoiceBubble(m, thread.playingKey == m.key, progress, status, statusColor(m.status), onTogglePlay = { onAction(CommsAction.TogglePlay(m)) }, onRetry = retry)
        }
        m.isPing -> Text(
            (if (m.outgoing) "PING SENT" else "PING RECEIVED") + " · ${clock(m.time)}" + if (m.outgoing) " · ${statusLabel(m.status)}" else "",
            color = if (m.outgoing && m.status == MessageStatus.DELIVERED) WaveGreen else NwTextDim,
            fontFamily = Mono, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        else -> Bubble(m.outgoing, null, m.peerId, m.text, m.time, status, statusColor(m.status))
    }
}

@Composable
private fun Bubble(outgoing: Boolean, sender: String?, senderId: String, text: String, time: Long, status: String?, statusColor: Color) {
    val sos = SosMessage.isSos(text)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 310.dp).bubble(outgoing, alarm = sos).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (sender != null) Text(sender, color = avatarColor(senderId), fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text(text, color = NwText, fontFamily = Sans, fontWeight = if (sos) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                if (!status.isNullOrEmpty()) Text(status, color = statusColor, fontFamily = Mono, fontSize = 10.sp)
                Text(clock(time), color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
            }
        }
    }
}

/**
 * The bottom row of a conversation: the text field, then the [tools] slot, then Send.
 *
 * [tools] is where input methods other than typing live (today the photo and voice-message buttons; a push-to-talk
 * voice burst or a "send my coordinates" button goes here too). It is told whether the user is typing, so a tool can
 * make room for the text. [replacement] takes the text field's place while a tool is in use (a recording in progress).
 */
@Composable
private fun Composer(
    enabled: Boolean, placeholder: String, maxBytes: Int, onSend: (String) -> Unit,
    replacement: (@Composable (Modifier) -> Unit)? = null,
    tools: (@Composable RowScope.(typing: Boolean) -> Unit)? = null
) {
    var text by remember { mutableStateOf("") }
    fun send() { if (enabled && text.isNotBlank()) { onSend(text); text = "" } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (replacement != null) replacement(Modifier.weight(1f)) else TacTextField(
            text, { if (it.toByteArray(Charsets.UTF_8).size <= maxBytes) text = it }, Modifier.weight(1f),
            placeholder = if (enabled) placeholder else "Radio is off", imeAction = ImeAction.Send, onImeAction = ::send
        )
        tools?.invoke(this, text.isNotBlank())
        if (replacement == null && (tools == null || text.isNotBlank())) TacButton("Send", ::send, enabled = enabled && text.isNotBlank(), kind = TacKind.TRANSMIT)
    }
}

// ---------------------------------------------------------------------------------------------- group info and dialogs

@Composable
private fun GroupInfo(state: CommsUiState, group: Group, onClose: () -> Unit, onEdit: () -> Unit, onLeave: () -> Unit) {
    val admin = group.adminId == state.localId
    AlertDialog(
        onDismissRequest = onClose, containerColor = NwSurface,
        title = { Text(group.name, fontFamily = Sans, fontWeight = FontWeight.Black, color = NwText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (admin) "You manage this group." else "Managed by ${state.nameOf(group.adminId)}.", color = NwTextDim, fontFamily = Sans, fontSize = 13.sp)
                group.members.forEach { m ->
                    val n = state.known.firstOrNull { it.id == m }
                    val self = m == state.localId
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NodeAvatar(m, if (self) state.localName else state.nameOf(m), 34.dp, verified = n?.verified == true)
                        Column(Modifier.weight(1f)) {
                            Text(if (self) "You" else state.nameOf(m), color = NwText, fontFamily = Sans, fontSize = 15.sp)
                            if (!self) Text(if (n?.visible == true) "in range" else "out of range", color = if (n?.visible == true) WaveGreen else NwTextDim, fontFamily = Mono, fontSize = 10.sp)
                        }
                        if (m == group.adminId) Chip("admin", MeshTeal)
                    }
                }
                Text("Every message is encrypted separately for each member. Members out of reach receive nothing until they are back in range.", color = NwTextDim, fontFamily = Sans, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (admin) TacButton("Members", onEdit, compact = true, kind = TacKind.SECONDARY)
                TacButton("Leave", onLeave, compact = true, kind = TacKind.DANGER)
            }
        },
        dismissButton = { TacButton("Close", onClose, compact = true, kind = TacKind.SECONDARY) }
    )
}

@Composable
private fun NodePickerDialog(title: String, known: List<KnownNode>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = NwSurface,
        title = { Text(title, fontFamily = Sans, fontWeight = FontWeight.Black, color = NwText) },
        text = {
            if (known.isEmpty()) Text("No nodes known yet. Start the radio and wait for a node to appear.", color = NwTextDim, fontFamily = Sans)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(known, key = { it.id }) { n ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(n.id) }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NodeAvatar(n.id, n.name, 44.dp, verified = n.verified, dim = !n.visible)
                        Column(Modifier.weight(1f)) {
                            Text(n.name, color = NwText, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(if (n.visible) "in range" else lastSeenText(n.lastSeen), color = if (n.visible) WaveGreen else NwTextDim, fontFamily = Mono, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TacButton("Cancel", onDismiss, compact = true, kind = TacKind.SECONDARY) }
    )
}

@Composable
private fun MemberDialog(
    title: String, nameEditable: Boolean, initialName: String, initialMembers: Set<String>, candidates: List<KnownNode>, selfId: String,
    onDismiss: () -> Unit, onConfirm: (String, Set<String>) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var chosen by remember { mutableStateOf(initialMembers) }
    val list = candidates.filter { it.id != selfId }.distinctBy { it.id }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = NwSurface,
        title = { Text(title, fontFamily = Sans, fontWeight = FontWeight.Black, color = NwText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nameEditable) TacTextField(name, { if (it.toByteArray(Charsets.UTF_8).size <= GroupCodec.MAX_NAME_BYTES) name = it }, label = "Group name")
                if (list.isEmpty()) Text("No nodes known yet.", color = NwTextDim, fontFamily = Sans)
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(list, key = { it.id }) { n ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { chosen = if (n.id in chosen) chosen - n.id else chosen + n.id },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = n.id in chosen, onCheckedChange = { on -> chosen = if (on) chosen + n.id else chosen - n.id },
                                colors = CheckboxDefaults.colors(checkedColor = NodeCyan, checkmarkColor = Color.Black, uncheckedColor = NwTextDim)
                            )
                            NodeAvatar(n.id, n.name, 32.dp, verified = n.verified, dim = !n.visible)
                            Text("  ${n.name}", color = NwText, fontFamily = Sans, fontSize = 15.sp)
                        }
                    }
                }
                Text("Up to ${GroupCodec.MAX_MEMBERS - 1} other members.", color = NwTextDim, fontFamily = Mono, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TacButton("OK", { onConfirm(name, chosen) }, kind = TacKind.PRIMARY, compact = true, enabled = chosen.isNotEmpty() && chosen.size < GroupCodec.MAX_MEMBERS && name.isNotBlank())
        },
        dismissButton = { TacButton("Cancel", onDismiss, compact = true, kind = TacKind.SECONDARY) }
    )
}
