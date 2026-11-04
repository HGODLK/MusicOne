package com.musicone

import org.junit.Assert.*
import org.junit.Test

class OfflinePlaybackSelectionTest {
    private val source = MusicSource.fromId("org.example.mixed-cache")
    private val track = MusicTrack("song", source, "歌曲", "作者", "专辑", 180_000, 0, 0, "", "")
    private fun playback(quality: AudioQuality, dash: Boolean = false) = ResolvedPlayback(track,
        PlaybackSource(if (dash) "data:application/dash+xml;base64,manifest" else "musicone-cache://audio/song",
            quality, quality, quality.bitRate, "", false, mimeType = if (dash) "application/dash+xml" else null))
    private fun custom(id: String) = AudioQuality.custom(source.id, id, standard = AudioQuality.LOSSLESS)

    @Test fun incompleteDashDoesNotHideCompleteOrdinaryCache() {
        val cached = playback(AudioQuality.LOSSLESS)
        assertSame(cached, selectOfflinePlayback(AudioQuality.LOSSLESS, listOf(cached), null) { error("精确档不应回退") })
    }

    @Test fun exactOrdinaryQualityWinsOverDashFallback() {
        val exact = playback(AudioQuality.HI_RES)
        val dash = playback(AudioQuality.STANDARD, true)
        assertSame(exact, selectOfflinePlayback(AudioQuality.HI_RES, listOf(exact), dash) { emptyList() })
    }

    @Test fun exactDashStillRetainsItsManifest() {
        val dash = playback(AudioQuality.LOSSLESS, true)
        assertSame(dash, selectOfflinePlayback(AudioQuality.LOSSLESS, listOf(playback(AudioQuality.EXHIGH)), dash) { emptyList() })
    }

    @Test fun customFallbackOrderSpansBothCacheKindsWithoutMergingMappedIds() {
        val preferred = custom("studio")
        val first = playback(custom("clear"))
        val second = playback(custom("warm"), true)
        assertSame(first, selectOfflinePlayback(preferred, listOf(first), second) {
            listOf(preferred, first.source.actualQuality, second.source.actualQuality)
        })
        assertSame(second, selectOfflinePlayback(preferred, listOf(first), second) {
            listOf(preferred, second.source.actualQuality, first.source.actualQuality)
        })
    }

    @Test fun undeclaredCustomFallbackIsNotSelectedBySharedStandardMapping() {
        val preferred = custom("studio")
        assertNull(selectOfflinePlayback(preferred, listOf(playback(custom("other"))), null) { listOf(preferred) })
    }

    @Test fun legacyDashOnlyAndOrdinaryOnlyFallbackRemainUnchanged() {
        val dash = playback(AudioQuality.STANDARD, true)
        assertSame(dash, selectOfflinePlayback(AudioQuality.HI_RES, emptyList(), dash) { emptyList() })
        val ordinary = playback(AudioQuality.LOSSLESS)
        assertSame(ordinary, selectOfflinePlayback(AudioQuality.HI_RES,
            listOf(playback(AudioQuality.STANDARD), ordinary), null) { emptyList() })
    }
}
