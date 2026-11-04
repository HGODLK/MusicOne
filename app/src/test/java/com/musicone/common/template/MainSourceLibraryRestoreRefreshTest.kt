package com.musicone

import com.musicone.source.sdk.SourceFetchPolicy
import org.junit.Assert.*
import org.junit.Test

class MainSourceLibraryRestoreRefreshTest {
    @Test fun firstVisibleRefreshWaitsForCacheAndReplacesInitialCheck() {
        val refresh = MainSourceLibraryRestoreRefresh()
        val token = refresh.begin()
        assertTrue(refresh.defer(SourceFetchPolicy.REFRESH, emptySet()))

        // 对应页面发布缓存之后，只消费一次首次可见刷新。
        assertEquals(MainSourceLibraryRefreshRequest(SourceFetchPolicy.REFRESH), refresh.complete(token))
        assertNull(refresh.complete(token))
        assertFalse(refresh.defer(SourceFetchPolicy.REFRESH, emptySet()))
    }

    @Test fun multipleRefreshesKeepStrongestPolicyAndAllChangedArtworkIds() {
        val refresh = MainSourceLibraryRestoreRefresh()
        val token = refresh.begin()
        assertTrue(refresh.defer(SourceFetchPolicy.REFRESH, setOf("changed-a")))
        assertTrue(refresh.defer(SourceFetchPolicy.CACHE_FIRST, emptySet()))
        assertTrue(refresh.defer(SourceFetchPolicy.REFRESH, setOf("changed-b")))
        assertEquals(MainSourceLibraryRefreshRequest(SourceFetchPolicy.REFRESH,
            setOf("changed-a", "changed-b")), refresh.complete(token))
    }

    @Test fun restoreWithoutVisibleRefreshRetainsOriginalCacheFirstCheck() {
        val refresh = MainSourceLibraryRestoreRefresh()
        assertEquals(MainSourceLibraryRefreshRequest(), refresh.complete(refresh.begin()))
    }

    @Test fun unchangedPlaylistReturnDoesNotBecomeForcedRefresh() {
        val refresh = MainSourceLibraryRestoreRefresh()
        val token = refresh.begin()
        assertTrue(refresh.defer(libraryReturnFetchPolicy(false), forcedArtworkRefreshIds("unchanged", false)))
        assertEquals(MainSourceLibraryRefreshRequest(), refresh.complete(token))
    }

    @Test fun oldAccountCompletionCannotConsumeNewAccountsPendingRefresh() {
        val refresh = MainSourceLibraryRestoreRefresh()
        val old = refresh.begin()
        refresh.defer(SourceFetchPolicy.REFRESH, setOf("old"))
        val current = refresh.begin()
        refresh.defer(SourceFetchPolicy.REFRESH, setOf("current"))
        assertNull(refresh.complete(old))
        assertEquals(MainSourceLibraryRefreshRequest(SourceFetchPolicy.REFRESH, setOf("current")),
            refresh.complete(current))
    }

    @Test fun signingOutDiscardsPendingRefreshAndOldCompletion() {
        val refresh = MainSourceLibraryRestoreRefresh()
        val token = refresh.begin()
        refresh.defer(SourceFetchPolicy.REFRESH, setOf("old"))
        refresh.cancel()
        assertNull(refresh.complete(token))
        assertEquals(MainSourceLibraryRefreshRequest(), refresh.complete(refresh.begin()))
    }
}
