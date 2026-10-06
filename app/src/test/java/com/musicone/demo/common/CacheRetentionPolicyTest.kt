package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class CacheRetentionPolicyTest {
    @Test fun ordinaryClearPreservesFavoritesAndPlayback() {
        assertEquals(AudioCacheClearAction.KEEP, audioCacheClearAction(true, false, false))
        assertEquals(AudioCacheClearAction.KEEP, audioCacheClearAction(false, true, false))
        assertEquals(AudioCacheClearAction.REMOVE, audioCacheClearAction(false, false, false))
    }
    @Test fun clearAllIncludesFavoritesButDefersActiveFile() {
        assertEquals(AudioCacheClearAction.REMOVE, audioCacheClearAction(true, false, true))
        assertEquals(AudioCacheClearAction.REMOVE, audioCacheClearAction(false, false, true))
        assertEquals(AudioCacheClearAction.AFTER_PLAYBACK, audioCacheClearAction(true, true, true))
        assertEquals(AudioCacheClearAction.AFTER_PLAYBACK, audioCacheClearAction(false, true, true))
    }
    @Test fun favoritesAreExcludedFromQuotaAndNeverEvicted() {
        val entries = listOf(CacheCandidate("favorite", 10_000, 0, protected = true),
            CacheCandidate("old", 60, 1), CacheCandidate("new", 60, 2))
        assertEquals(listOf("old"), cacheEvictions(entries, 100))
    }
    @Test fun activePlaybackCountsButCannotBeRemoved() {
        val entries = listOf(CacheCandidate("playing", 120, 0, active = true), CacheCandidate("other", 10, 1))
        assertEquals(listOf("other"), cacheEvictions(entries, 100))
    }
    @Test fun unlimitedAndUnderBudgetDoNotRemoveAnything() {
        val entries = listOf(CacheCandidate("song", 100, 1))
        assertTrue(cacheEvictions(entries, 0).isEmpty())
        assertTrue(cacheEvictions(entries, 100).isEmpty())
    }
    @Test fun artworkAndAudioShareLeastRecentlyUsedQuota() {
        val entries = listOf(CacheCandidate("audio", 90, 100), CacheCandidate("image", 20, 10))
        assertEquals(listOf("image"), cacheEvictions(entries, 100))
    }
    @Test fun protectionIncludesAllQualitiesButDoesNotLeakAcrossAccounts() {
        val favorites = setOf("QQ:123|qq-mid")
        assertTrue(isProtectedAudioKey("QQ:123|qq-mid|/M500.mid.mp3", favorites))
        assertTrue(isProtectedAudioKey("QQ:123|qq-mid|/F000.mid.flac", favorites))
        assertFalse(isProtectedAudioKey("QQ:456|qq-mid|/F000.mid.flac", favorites))
        assertFalse(isProtectedAudioKey("NETEASE:123|qq-mid|/F000.mid.flac", favorites))
    }
}
