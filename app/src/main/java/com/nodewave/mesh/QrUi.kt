// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nodewave.mesh.transport.MeshLog
import java.util.concurrent.Executors

/** Black-on-white QR code (a white margin keeps it readable on a dark screen). */
internal fun qrBitmap(text: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** Reads QR codes from the camera's luminance plane with ZXing; calls [onCode] on the camera thread. */
private class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer.also { it.rewind() }
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val rowStride = plane.rowStride
            val source = PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false)
            onCode(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text)
        } catch (_: com.google.zxing.NotFoundException) {
            // no code in this frame
        } catch (e: Exception) {
            MeshLog.e("QR frame skipped", e)
        } finally {
            reader.reset()
            image.close()
        }
    }
}

/** Full-screen camera view that reports the first QR code it reads; asks for the camera permission itself. */
@Composable
internal fun QrScannerDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(0.dp), contentAlignment = Alignment.Center) {
            if (granted) CameraPreview(onCode) else Text("Camera permission is needed to scan a code", color = Color.White, fontFamily = Mono)
            Column(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                Text("Point the camera at the other phone's contact code", color = Color.White, fontFamily = Mono, fontSize = 12.sp)
                OutlinedButton(onClick = onDismiss) { Text("CANCEL", fontFamily = Mono) }
            }
        }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    var delivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(executor, QrAnalyzer { text ->
                                    if (!delivered) {
                                        delivered = true
                                        view.post { onCode(text) }
                                    }
                                })
                            }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { MeshLog.e("Camera could not start", it) }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }
    )
}
