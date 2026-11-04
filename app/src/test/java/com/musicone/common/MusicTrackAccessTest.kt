package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class MusicTrackAccessTest {
    @get:org.junit.Rule val sources = InstalledSourceContractsRule()
    @Test fun playerUsesIndependentHiResAndDolbyQualityLevels() {
        assertEquals(listOf(AudioQuality.DOLBY, AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH,
            AudioQuality.STANDARD), PLAYER_AUDIO_QUALITIES)
        assertEquals("MP3标准", AudioQuality.STANDARD.displayLabel(MusicSource.QQ))
        assertEquals("MP3高品质", AudioQuality.EXHIGH.displayLabel(MusicSource.QQ))
        assertEquals("FLAC无损", AudioQuality.LOSSLESS.displayLabel(MusicSource.QQ))
        assertEquals("HiRes无损", AudioQuality.HI_RES.displayLabel(MusicSource.QQ))
        assertEquals("MP3L", AudioQuality.STANDARD.displayCompactLabel(MusicSource.NETEASE))
        assertEquals("MP3H", AudioQuality.EXHIGH.displayCompactLabel(MusicSource.KUGOU))
        assertEquals("最高24bit/192KHz", AudioQuality.HI_RES.displayDescription())
        assertEquals("最高24bit/48KHz", AudioQuality.LOSSLESS.displayDescription())
        assertEquals("最高320kbps", AudioQuality.EXHIGH.displayDescription())
        assertEquals("普通音质", AudioQuality.STANDARD.displayDescription())
        assertEquals(AudioQuality.HI_RES, AudioQuality.HI_RES.playbackEquivalent())
        assertFalse(AudioQuality.HI_RES.isAvailableInPlayer(listOf(AudioQuality.LOSSLESS)))
        assertEquals(
            listOf(AudioQuality.EXHIGH, AudioQuality.STANDARD),
            AudioQuality.EXHIGH.fallbackCandidates(MusicSource.QQ),
        )
        assertEquals(
            listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD),
            AudioQuality.HI_RES.fallbackCandidates(MusicSource.QQ),
        )
        assertEquals(
            listOf(AudioQuality.EXHIGH, AudioQuality.STANDARD),
            visiblePlayerQualities(listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH)),
        )
        assertEquals(
            listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH, AudioQuality.LOSSLESS),
            verifiedPlayerQualities(AudioQuality.LOSSLESS, listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH)),
        )
        assertEquals(listOf(AudioQuality.LOSSLESS), verifiedPlayerQualities(AudioQuality.LOSSLESS, emptyList()))
        assertTrue(verifiedPlayerQualities(null, emptyList()).isEmpty())
    }

    @Test fun customQualityMenuKeepsOnlySongContextAndDelegatesSelection() {
        val menu = PlayerQualityMenuState()
        var selected: AudioQuality? = null
        val touch = androidx.compose.ui.geometry.Offset(24f, 36f)
        menu.open("song-1", androidx.compose.ui.geometry.Rect.Zero, touch) { selected = it }
        menu.select(AudioQuality.HI_RES)
        assertEquals(AudioQuality.HI_RES, selected)
        assertTrue(menu.expanded)
        assertEquals("song-1", menu.trackId)
        assertEquals(touch, menu.triggerOrigin)
        menu.dismiss()
        assertFalse(menu.expanded)
    }

}
