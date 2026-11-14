// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nodewave.mesh.ui.navigation.AppRoot
import com.nodewave.mesh.ui.theme.NodeWaveTheme
import com.nodewave.mesh.ui.theme.NwBlack

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the app is dark in every system theme: light status and navigation bar icons, always
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            NodeWaveTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = NwBlack) { AppRoot() }
            }
        }
    }
}
