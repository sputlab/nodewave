package com.nodewave.mesh

import android.app.Application

class NodeWaveApp : Application() {
    /** Process-wide mesh runtime: it must outlive the activity so the radio keeps working with the screen off. */
    val engine: MeshEngine by lazy { MeshEngine(this) }
}
