package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class CacheRetentionPolicyTest {
    private val gb = 1_073_741_824L
    private fun primary(key: String, gigabytes: Long, order: Long, entered: Long = order, active: Boolean = false) =
        CacheResource(key, gigabytes * gb, entered, order, active)
    private fun ordinary(key: String, gigabytes: Long, entered: Long = 0, active: Boolean = false) =
        CacheResource(key, gigabytes * gb, entered, active = active)

    @Test fun allThirtySixConfigurationsUseMaximumRatherThanSumAndGateWrites() {
        for (total in CacheCapacity.entries) for (independent in CacheCapacity.entries) {
            val limits = CacheLimits(total, independent)
            assertEquals(maxOf(total.bytes, independent.bytes), limits.maximum)
            assertEquals(total != CacheCapacity.OFF, cacheWritePlan(emptyList(), CacheResource("normal", 1, 1), limits).allowed)
            assertEquals(total != CacheCapacity.OFF || independent != CacheCapacity.OFF,
                cacheWritePlan(emptyList(), CacheResource("primary", 1, 1, 1), limits).allowed)
        }
    }

    @Test fun twentyTotalFiveIndependentProtectsLatestFiveOnly() {
        val entries = listOf(primary("old", 10, 1), primary("middle", 3, 2), primary("new", 2, 3))
        assertEquals(setOf("middle", "new"), protectedCacheKeys(entries, CacheLimits(CacheCapacity.GB20, CacheCapacity.GB5)))
    }

    @Test fun largerOrEqualIndependentProtectsEveryCachedPrimarySong() {
        val entries = listOf(primary("old", 3, 1), primary("new", 2, 2), ordinary("normal", 1))
        for (limits in listOf(CacheLimits(CacheCapacity.GB5, CacheCapacity.GB20),
            CacheLimits(CacheCapacity.GB5, CacheCapacity.GB5), CacheLimits(CacheCapacity.OFF, CacheCapacity.GB10)))
            assertEquals(setOf("old", "new"), protectedCacheKeys(entries, limits))
    }

    @Test fun disablingIndependentRetainsPrimaryEligibilityThroughTotal() {
        val limits = CacheLimits(CacheCapacity.GB5)
        assertTrue(protectedCacheKeys(listOf(primary("old", 3, 1)), limits).isEmpty())
        assertTrue(cacheWritePlan(emptyList(), primary("new", 1, 2), limits).allowed)
    }

    @Test fun primaryUseIsCountedAgainstOrdinaryTotalEvenWithLargerMaximum() {
        val entries = listOf(primary("primary", 6, 1))
        val plan = cacheWritePlan(entries, ordinary("normal", 1), CacheLimits(CacheCapacity.GB5, CacheCapacity.GB20))
        assertFalse(plan.allowed)
        assertEquals("一级歌曲已占满总缓存额度", plan.reason)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun ordinaryMayUseOnlyRemainingTotalAndNeverRotateProtectedSongs() {
        val entries = listOf(primary("protected", 3, 1), ordinary("older", 2, 2))
        val plan = cacheWritePlan(entries, ordinary("new", 2, 3), CacheLimits(CacheCapacity.GB5, CacheCapacity.GB20))
        assertTrue(plan.allowed)
        assertEquals(listOf("older"), plan.remove)
    }

    @Test fun unprotectedPrimaryMayBeEvictedByFifoWhenTotalHasRoomForOrdinary() {
        val entries = listOf(primary("old", 10, 1), primary("new", 5, 2))
        val plan = cacheWritePlan(entries, ordinary("normal", 10, 3), CacheLimits(CacheCapacity.GB20, CacheCapacity.GB5))
        assertTrue(plan.allowed)
        assertEquals(listOf("old"), plan.remove)
    }

    @Test fun ordinaryCannotDisplacePrimaryWhenPrimaryAlreadyFillsTotal() {
        val plan = cacheWritePlan(listOf(primary("old", 15, 1), primary("new", 5, 2)), ordinary("normal", 1),
            CacheLimits(CacheCapacity.GB20, CacheCapacity.GB5))
        assertFalse(plan.allowed)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun onlyPrimaryAdmissionCanRotateOldestProtectedResource() {
        val entries = listOf(primary("first", 3, 1), primary("second", 2, 2))
        val limits = CacheLimits(CacheCapacity.OFF, CacheCapacity.GB5)
        val plan = cacheWritePlan(entries, primary("new", 1, 3), limits)
        assertTrue(plan.allowed)
        assertEquals(listOf("first"), plan.remove)
        assertFalse(cacheWritePlan(entries, ordinary("normal", 1), limits).allowed)
    }

    @Test fun emptyMediaMetadataDoesNotPreventProtectedRotation() {
        val entries = listOf(primary("old", 5, 1), CacheResource("metadata-only", 0, 0))
        val plan = cacheWritePlan(entries, primary("new", 1, 2), CacheLimits(CacheCapacity.OFF, CacheCapacity.GB5))
        assertTrue(plan.allowed)
        assertEquals(listOf("metadata-only", "old"), plan.remove)
    }

    @Test fun downloadingAnOlderMemberDoesNotEvictNewerProtectedMembers() {
        val plan = cacheWritePlan(listOf(primary("newer", 5, 10)), primary("old-download", 1, 1),
            CacheLimits(CacheCapacity.OFF, CacheCapacity.GB5))
        assertFalse(plan.allowed)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun activeProtectedResourcesBlockAdmissionWithoutSpeculativeDeletion() {
        val plan = cacheWritePlan(listOf(primary("playing", 5, 1, active = true)), primary("new", 1, 2),
            CacheLimits(CacheCapacity.GB5, CacheCapacity.GB5))
        assertFalse(plan.allowed)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun ordinaryFifoUsesCacheEntryOrderAndSkipsActiveFiles() {
        val entries = listOf(ordinary("old-active", 2, 1, true), ordinary("old", 2, 2), ordinary("recent", 1, 3))
        val plan = cacheWritePlan(entries, ordinary("new", 2, 4), CacheLimits())
        assertEquals(listOf("old"), plan.remove)
    }

    @Test fun failedAdmissionReturnsNoPartialEvictionPlan() {
        val entries = listOf(primary("playing", 4, 1, active = true), ordinary("tiny", 1, 2))
        val plan = cacheWritePlan(entries, ordinary("new", 2), CacheLimits(CacheCapacity.GB5, CacheCapacity.GB5))
        assertFalse(plan.allowed)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun sharedSongReplacementCountsExistingResourceOnce() {
        val entry = ordinary("same", 4, 1)
        val plan = cacheWritePlan(listOf(entry), entry.copy(bytes = 5 * gb), CacheLimits())
        assertTrue(plan.allowed)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun oversizeSongDoesNotRemoveExistingCache() {
        val plan = cacheWritePlan(listOf(ordinary("old", 1)), ordinary("too-big", 6), CacheLimits())
        assertFalse(plan.allowed)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun protectionBoundaryNeverSplitsASongOrSkipsNewerForOlder() {
        val entries = listOf(primary("small-old", 1, 1), primary("large-new", 6, 2))
        assertTrue(protectedCacheKeys(entries, CacheLimits(CacheCapacity.GB20, CacheCapacity.GB5)).isEmpty())
    }

    @Test fun finiteShrinkCanRotateOldPrimaryButClosedConfigurationKeepsFiles() {
        val entries = listOf(primary("old", 8, 1), primary("new", 4, 2))
        assertEquals(listOf("old"), cacheShrinkPlan(entries, CacheLimits(CacheCapacity.GB5, CacheCapacity.GB5)))
        assertTrue(cacheShrinkPlan(entries, CacheLimits(CacheCapacity.OFF, CacheCapacity.OFF)).isEmpty())
    }

    @Test fun disablingEitherQuotaCancelsAutomaticShrinkWhileFiniteReductionRequestsIt() {
        assertFalse(shouldShrinkCache(CacheLimits(CacheCapacity.GB5, CacheCapacity.GB20), CacheLimits(CacheCapacity.GB5, CacheCapacity.OFF)))
        assertFalse(shouldShrinkCache(CacheLimits(CacheCapacity.GB20, CacheCapacity.GB5), CacheLimits(CacheCapacity.OFF, CacheCapacity.GB5)))
        assertFalse(shouldShrinkCache(CacheLimits(CacheCapacity.GB20, CacheCapacity.GB20), CacheLimits(CacheCapacity.OFF, CacheCapacity.OFF)))
        assertTrue(shouldShrinkCache(CacheLimits(CacheCapacity.GB5, CacheCapacity.GB20), CacheLimits(CacheCapacity.GB5, CacheCapacity.GB5)))
    }

    @Test fun reducingTotalAlsoRemovesOrdinaryOverflowUnderUnlimitedIndependent() {
        val entries = listOf(primary("protected", 6, 1), ordinary("normal", 3, 2))
        assertEquals(listOf("normal"), cacheShrinkPlan(entries, CacheLimits(CacheCapacity.GB5, CacheCapacity.UNLIMITED)))
    }

    @Test fun unlimitedTotalStillHasFiniteProtectionAndUnlimitedIndependentProtectsAll() {
        val entries = listOf(primary("old", 20, 1), primary("new", 5, 2))
        assertEquals(setOf("new"), protectedCacheKeys(entries, CacheLimits(CacheCapacity.UNLIMITED, CacheCapacity.GB5)))
        assertEquals(setOf("old", "new"), protectedCacheKeys(entries, CacheLimits(CacheCapacity.UNLIMITED, CacheCapacity.UNLIMITED)))
    }

    @Test fun ordinaryClearPreservesProtectionAndPlaybackWhileExplicitAllDefersActive() {
        assertEquals(AudioCacheClearAction.KEEP, audioCacheClearAction(true, false, false))
        assertEquals(AudioCacheClearAction.KEEP, audioCacheClearAction(false, true, false))
        assertEquals(AudioCacheClearAction.REMOVE, audioCacheClearAction(false, false, false))
        assertEquals(AudioCacheClearAction.REMOVE, audioCacheClearAction(true, false, true))
        assertEquals(AudioCacheClearAction.AFTER_PLAYBACK, audioCacheClearAction(true, true, true))
    }

    @Test fun oldTwoGigabyteSettingMigratesToFiveAndOldZeroRemainsUnlimited() {
        assertEquals(CacheCapacity.GB5, CacheCapacity.fromLegacy(2))
        assertEquals(CacheCapacity.UNLIMITED, CacheCapacity.fromLegacy(0))
        assertEquals(CacheCapacity.OFF, CacheLimits().independent)
    }
}
