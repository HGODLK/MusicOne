package com.musicone

import org.junit.Assert.*
import org.junit.Test

class SourceQualityPresentationTest {
    private val source = MusicSource.fromId("org.example.source")
    @Test fun preferredLosslessDisplaysActualHiresFile() {
        assertEquals(AudioQuality.HI_RES, playerDisplayedQuality(AudioQuality.LOSSLESS, AudioQuality.HI_RES))
        assertEquals(AudioQuality.HI_RES, playerDisplayedQuality(AudioQuality.HI_RES, AudioQuality.HI_RES))
        assertEquals(AudioQuality.EXHIGH, playerDisplayedQuality(AudioQuality.EXHIGH, AudioQuality.EXHIGH))
        assertEquals(AudioQuality.STANDARD, playerDisplayedQuality(AudioQuality.STANDARD, AudioQuality.STANDARD))
    }
    @Test fun fourQualityTiersRetainTheirDisplayOrderAndLabels() {
        val visible = visiblePlayerQualities(listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH, AudioQuality.LOSSLESS, AudioQuality.HI_RES))
        assertEquals(listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD), visible)
        assertEquals(listOf("HiRes", "FLAC", "MP3H", "MP3L"), visible.map { it.displayCompactLabel(source) })
        assertEquals(listOf("HiRes无损", "FLAC无损", "MP3高品质", "MP3标准"), visible.map { it.displayLabel(source) })
    }
}
