// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.screens.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.protocol.DeviceReport
import com.nodewave.mesh.ui.components.NwGlyph
import com.nodewave.mesh.ui.components.NwIcon
import com.nodewave.mesh.ui.components.SectionLabel
import com.nodewave.mesh.ui.components.TacButton
import com.nodewave.mesh.ui.components.TacKind
import com.nodewave.mesh.ui.components.TacticalCard
import com.nodewave.mesh.ui.theme.MeshTeal
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.WaveGreen

@Immutable
data class DeviceReportUiState(
    /** The exact text that the email or the web form carries; null while it is being read. */
    val text: String?,
    /** What the last button did, or why it could not. */
    val notice: String? = null
)

sealed interface DeviceReportAction {
    data object Back : DeviceReportAction
    data object Refresh : DeviceReportAction
    data object CopyAndOpenForm : DeviceReportAction
    data object SendEmail : DeviceReportAction
}

private val Contains = listOf(
    "Brand, model, chipset, memory and screen of the phone",
    "Android version, API level and security patch date",
    "What Bluetooth and Wi-Fi can do (PHYs, advertising, Wi-Fi Direct)",
    "NodeWave version, radio state, how many nodes are in reach",
    "Which permissions NodeWave holds, battery optimisation on or off"
)

private val NeverContains = listOf(
    "Your name, the node name, the node ID or any key",
    "Messages, contacts, groups, positions or the map",
    "IMEI, serial numbers, Android ID or advertising ID",
    "MAC or IP addresses, phone number, accounts",
    "Wi-Fi network names, installed apps, language or time zone"
)

/**
 * The device report: why it exists, what it contains and what it never does, every word of it, and the two ways to
 * send it. Nothing leaves the phone until one of the two buttons is pressed.
 */
@Composable
fun DeviceReportScreen(state: DeviceReportUiState, onAction: (DeviceReportAction) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TacButton("‹ Settings", { onAction(DeviceReportAction.Back) }, kind = TacKind.SECONDARY, compact = true)
                Text("DEVICE REPORT", color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
            }
        }
        item {
            Text(
                "Bluetooth behaves differently on every phone. Sending this report tells the developer which phone NodeWave " +
                    "runs on and what its radios can do, so problems can be matched to hardware and fixed.",
                color = NwTextDim, fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        item {
            TacticalCard(title = "Nothing personal", accent = MeshTeal, emphasized = true) {
                Text(
                    "This report describes the phone, never you. It holds no personal data, no identifiers and no codes of any kind.",
                    color = NwText, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 19.sp
                )
                SectionLabel("What it contains")
                Contains.forEach { Bullet(it, "+", WaveGreen) }
                SectionLabel("What it never contains")
                NeverContains.forEach { Bullet(it, "×", NwTextDim) }
                Text(
                    "Nothing is sent by itself. Below is every word of the report, exactly as it would be sent: it leaves this phone " +
                        "only if you press one of the two buttons at the end, and you can always decide not to.",
                    color = NwTextDim, fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }
        item {
            TacticalCard(
                title = "Exactly what is sent", accent = NodeCyan,
                actions = { TacButton("Refresh", { onAction(DeviceReportAction.Refresh) }, kind = TacKind.SECONDARY, compact = true) }
            ) {
                SelectionContainer {
                    Text(state.text ?: "Reading the phone's details…", color = NwText, fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
        item {
            TacticalCard(title = "Send it", accent = NodeCyan) {
                val ready = state.text != null
                TacButton("Copy and open the web form", { onAction(DeviceReportAction.CopyAndOpenForm) }, Modifier.fillMaxWidth(), TacKind.PRIMARY, enabled = ready)
                Explain(
                    "The most private way. Copies the report and opens ${DeviceReport.FORM_URL.removePrefix("https://")} in your browser: " +
                        "paste it there and press send. No email address of yours is involved. Needs an internet connection."
                )
                TacButton("Send by email", { onAction(DeviceReportAction.SendEmail) }, Modifier.fillMaxWidth(), TacKind.OUTLINE, enabled = ready)
                Explain(
                    "Opens your email app with the report, the subject \"${DeviceReport.TITLE}\" and the address " +
                        "${DeviceReport.ADDRESS} already filled in: you press send there. The developer will see the address you send from."
                )
                state.notice?.let { Text(it, color = WaveGreen, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 18.sp) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NwGlyph(NwIcon.DEVICE, NwTextDim, size = 16.dp)
                Text("Thank you for helping NodeWave run on more phones.", color = NwTextDim, fontFamily = Sans, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Bullet(text: String, mark: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(text, color = NwText, fontFamily = Sans, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Explain(text: String) {
    Column { Text(text, color = NwTextDim, fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp) }
}
