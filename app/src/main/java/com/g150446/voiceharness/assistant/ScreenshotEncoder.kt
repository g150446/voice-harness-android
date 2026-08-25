package com.g150446.voiceharness.assistant

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object ScreenshotEncoder {
    private const val JPEG_QUALITY = 75

    fun toJpeg(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null || bitmap.isRecycled) return null
        return try {
            ByteArrayOutputStream().use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (!ok) null else out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
