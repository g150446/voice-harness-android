package com.g150446.voiceharness

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSilenceTrimmerTest {

    @Test
    fun trim_removesLongLeadingAndTrailingSilenceWithPadding() {
        val pcm = pcm16(
            ShortArray(16_000) +
                ShortArray(8_000) { if (it % 2 == 0) 2_000 else -2_000 } +
                ShortArray(16_000)
        )

        val trimmed = PcmSilenceTrimmer.trim(pcm, sampleRate = 16_000)

        assertTrue(trimmed.size < pcm.size)
        // 500 ms speech plus roughly 300 ms padding on each side.
        assertTrue(trimmed.size in 32_000..38_400)
    }

    @Test
    fun trim_keepsShortAudioUnchanged() {
        val pcm = pcm16(ShortArray(8_000) { 2_000 })

        assertEquals(pcm.size, PcmSilenceTrimmer.trim(pcm, 16_000).size)
    }

    @Test
    fun trim_keepsUncertainQuietAudioUnchanged() {
        val pcm = pcm16(ShortArray(32_000) { 20 })

        assertEquals(pcm.size, PcmSilenceTrimmer.trim(pcm, 16_000).size)
    }

    private fun pcm16(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach(::putShort) }
            .array()
}
