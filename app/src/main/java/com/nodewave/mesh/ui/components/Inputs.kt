package com.nodewave.mesh.ui.components

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodewave.mesh.ui.theme.Mono
import com.nodewave.mesh.ui.theme.Sans
import com.nodewave.mesh.ui.theme.TacticalDim
import com.nodewave.mesh.ui.theme.TacticalGreen
import com.nodewave.mesh.ui.theme.TacticalLine
import com.nodewave.mesh.ui.theme.TacticalSurface
import com.nodewave.mesh.ui.theme.TacticalText

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
        shape = RoundedCornerShape(10.dp),
        textStyle = TextStyle(fontFamily = if (mono) Mono else Sans, fontSize = 14.sp, color = TacticalText),
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, fontFamily = Sans, fontSize = 14.sp, color = TacticalDim) },
        label = label?.let { { Text(it, fontFamily = Mono, fontSize = 11.sp) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TacticalGreen, unfocusedBorderColor = TacticalLine, disabledBorderColor = TacticalLine,
            focusedContainerColor = TacticalSurface, unfocusedContainerColor = TacticalSurface, disabledContainerColor = TacticalSurface,
            cursorColor = TacticalGreen, focusedLabelColor = TacticalGreen, unfocusedLabelColor = TacticalDim,
            disabledTextColor = TacticalDim
        )
    )
}

/** A labelled on/off row. */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = if (enabled) TacticalText else TacticalDim, fontFamily = Sans, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = TacticalDim, fontFamily = Sans, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = TacticalGreen, uncheckedTrackColor = TacticalLine, uncheckedThumbColor = TacticalDim, uncheckedBorderColor = TacticalLine)
        )
    }
}
