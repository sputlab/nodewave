package com.nodewave.mesh.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.nodewave.mesh.transport.MeshLog
import java.io.ByteArrayOutputStream

/** Shrinks a photo to a JPEG of about [targetBytes]. The result has no EXIF at all, so no location or camera data leaves the phone. */
object ImageCompressor {
    fun compress(context: Context, uri: Uri, targetBytes: Int, maxSide: Int): ByteArray? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // with inJustDecodeBounds the decoder returns no bitmap: the size is in [bounds]
        val input = resolver.openInputStream(uri) ?: run { MeshLog.e("Photo $uri could not be opened"); return null }
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        MeshLog.d("Photo bounds ${bounds.outWidth}x${bounds.outHeight}, target $targetBytes bytes, max side $maxSide")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: run { MeshLog.e("Photo could not be decoded"); return null }
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return fit(rotate(decoded, rotation), targetBytes, maxSide)
    }

    /** Scales and compresses [source] until the JPEG is at most [targetBytes]; null if it cannot be made that small. */
    fun fit(source: Bitmap, targetBytes: Int, maxSide: Int): ByteArray? {
        val bitmap = source
        var side = maxSide
        repeat(6) {
            val scaled = scaleTo(bitmap, side)
            for (quality in intArrayOf(85, 72, 60, 48, 36)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= targetBytes) return out.toByteArray()
                MeshLog.d("Photo at ${scaled.width}x${scaled.height} quality $quality is ${out.size()} bytes, too big")
            }
            side = (side * 0.8).toInt()
            if (scaled !== bitmap) scaled.recycle()
        }
        return null
    }

    private fun scaleTo(b: Bitmap, side: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= side) return b
        val f = side.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** A bitmap no bigger than about [maxPx] on its longest side, for showing. */
    fun decodeForScreen(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
