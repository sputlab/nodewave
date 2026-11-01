package com.nodewave.mesh.transport

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Unified radio logger: writes to logcat (tag MESH_RADIO) and mirrors lines to an in-app console. */
object MeshLog {
    const val TAG = "MESH_RADIO"
    private const val MAX_LINES = 200

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun d(message: String) {
        Log.d(TAG, message)
        append("D", message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        append("E", if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message)
    }

    private fun append(level: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        _lines.update { (it + "$time $level $message").takeLast(MAX_LINES) }
    }
}
