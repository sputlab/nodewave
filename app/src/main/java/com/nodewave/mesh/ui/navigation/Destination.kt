// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.ui.navigation

import com.nodewave.mesh.ui.components.NwIcon

/**
 * The sections of the app. A new module (a console for a LoRa board, a push-to-talk channel) is one more entry here
 * and one more branch in the shell: the header, the action bar and the tab bar do not change.
 */
enum class Destination(
    val label: String,
    val icon: NwIcon,
    /** Shown in the tab bar; the others are reached from inside a section. */
    val inTabBar: Boolean = true,
    /** The ping, message and SOS bar sits above the tabs here. */
    val showsActionBar: Boolean = true
) {
    NETWORK("Network", NwIcon.NETWORK),
    COMMS("Comms", NwIcon.COMMS),
    MAP("Map", NwIcon.MAP),
    SETTINGS("Settings", NwIcon.GEAR, showsActionBar = false),
    /** Opened from Settings: technical tools nobody needs while operating. */
    DIAGNOSTICS("Diagnostics", NwIcon.DIAGNOSTICS, inTabBar = false, showsActionBar = false);

    companion object {
        val tabs: List<Destination> = entries.filter { it.inTabBar }
    }
}
