// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.R
import com.nodewave.mesh.transport.TransportState
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.NodeWaveBrandGradient
import com.nodewave.mesh.ui.theme.NwBlack
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwText
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.Sans

@Immutable
data class TopBarUiState(val nodeName: String, val nodeId: String, val transport: TransportUiState)

/**
 * The fixed head-up display, one line: who this node is (NW badge, name, ID) on the left, the radio's state with its
 * media and its power switch on the right. Its lower edge carries the brand gradient while the radio is on air.
 */
@Composable
fun NodeWaveTopBar(state: TopBarUiState, onToggleRadio: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(NwBlack).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val badge = RoundedCornerShape(11.dp)
            Image(
                painterResource(R.drawable.app_icon), contentDescription = "NodeWave",
                modifier = Modifier.size(40.dp).border(1.5.dp, NodeWaveBrandGradient, badge).padding(2.5.dp).clip(RoundedCornerShape(9.dp))
            )
            Column(Modifier.weight(1f)) {
                Text(state.nodeName, color = NwText, fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("ID ${groupedId(state.nodeId.take(8))}", color = NwTextDim, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
            }
            TransportStatusPill(state.transport, onToggleRadio)
        }
        if (state.transport.state == TransportState.ACTIVE) Box(Modifier.fillMaxWidth().height(2.dp).background(NodeWaveBrandGradient))
        else Box(Modifier.fillMaxWidth().height(1.dp).background(NwLine))
    }
}
