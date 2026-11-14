// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.NwTextDim
import com.nodewave.mesh.ui.theme.NodeCyan
import com.nodewave.mesh.ui.theme.NwLine
import com.nodewave.mesh.ui.theme.NwSurface
import com.nodewave.mesh.ui.theme.NwText

@Composable
fun TacTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {}
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, singleLine = singleLine,
        shape = RoundedCornerShape(12.dp),
        textStyle = TextStyle(fontFamily = if (mono) Mono else Sans, fontSize = 16.sp, color = NwText),
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, fontFamily = Sans, fontSize = 16.sp, color = NwTextDim, maxLines = 1) },
        label = label?.let { { Text(it, fontFamily = Sans, fontSize = 12.sp) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NodeCyan, unfocusedBorderColor = NwLine, disabledBorderColor = NwLine,
            focusedContainerColor = NwSurface, unfocusedContainerColor = NwSurface, disabledContainerColor = NwSurface,
            cursorColor = NodeCyan, focusedLabelColor = NodeCyan, unfocusedLabelColor = NwTextDim,
            disabledTextColor = NwTextDim
        )
    )
}

/** A labelled on/off row. */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = if (enabled) NwText else NwTextDim, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = NwTextDim, fontFamily = Sans, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = NodeCyan, uncheckedTrackColor = NwLine, uncheckedThumbColor = NwTextDim, uncheckedBorderColor = NwLine)
        )
    }
}
