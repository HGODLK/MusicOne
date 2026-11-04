package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyPlaybackMetadataTest {
    @Test fun oldOrphanedCacheRetainsExactQuality() {
        assertEquals(AudioQuality.STANDARD, legacyCachedAudioQuality("/M500old.mp3"))
        assertEquals(AudioQuality.EXHIGH, legacyCachedAudioQuality("M800old.mp3"))
        assertEquals(AudioQuality.LOSSLESS, legacyCachedAudioQuality("F000old.flac"))
        assertEquals(AudioQuality.HI_RES, legacyCachedAudioQuality("//cache.example/rs01old.FLAC?ticket=example"))
        listOf("D004", "D008", "D009").forEach { prefix ->
            assertEquals(AudioQuality.DOLBY, legacyCachedAudioQuality("$prefix-old.mp4"))
        }
    }
    @Test fun extensionOrUnrelatedPrefixNeverClaimsAQuality() {
        assertNull(legacyCachedAudioQuality("RS01old.mp3"))
        assertNull(legacyCachedAudioQuality("song.flac"))
        assertNull(legacyCachedAudioQuality("C600old.m4a"))
    }
}
