package com.g150446.voiceharness

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.security.MessageDigest

internal data class ScreenContextFingerprint(
    val assistText: String?,
    val imageHash: String?,
) {
    fun changedFrom(previous: ScreenContextFingerprint): Boolean {
        val textComparable = assistText != null && previous.assistText != null
        val imageComparable = imageHash != null && previous.imageHash != null
        return (textComparable && assistText != previous.assistText) ||
            (imageComparable && imageHash != previous.imageHash)
    }

    companion object {
        fun from(context: ScreenContext): ScreenContextFingerprint =
            ScreenContextFingerprint(
                assistText = context.assistText?.trim()?.replace(Regex("\\s+"), " ")
                    ?.takeIf(String::isNotEmpty),
                imageHash = context.jpegBytes?.let(::centralImageHash),
            )

        private fun centralImageHash(bytes: ByteArray): String? {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return runCatching {
                val left = (decoded.width * 0.10f).toInt()
                val top = (decoded.height * 0.15f).toInt()
                val width = (decoded.width * 0.80f).toInt().coerceAtLeast(1)
                val height = (decoded.height * 0.70f).toInt().coerceAtLeast(1)
                val crop = Bitmap.createBitmap(decoded, left, top, width, height)
                val scaled = Bitmap.createScaledBitmap(crop, 16, 16, true)
                val digest = MessageDigest.getInstance("SHA-256")
                val pixels = IntArray(16 * 16)
                scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
                pixels.forEach { pixel ->
                    digest.update(((pixel shr 16) and 0xff).toByte())
                    digest.update(((pixel shr 8) and 0xff).toByte())
                    digest.update((pixel and 0xff).toByte())
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull().also {
                decoded.recycle()
            }
        }
    }
}
