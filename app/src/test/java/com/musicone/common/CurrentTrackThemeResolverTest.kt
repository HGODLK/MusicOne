package com.musicone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CurrentTrackThemeResolverTest {
    @Test fun switchingTracksRetainsResolvedColorUntilNewThemeArrives() = runBlocking {
        val response = CompletableDeferred<Long?>()
        val colors = mutableListOf<Long?>()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            CurrentTrackThemeResolver().resolve(false, { true }, { response.await() },
                pendingArgb = 0xFF12AB34L) { colors += it }
        }
        assertEquals(listOf(0xFF12AB34L), colors)
        response.complete(0xFF445566L)
        pending.join()
        assertEquals(listOf(0xFF12AB34L, 0xFF445566L), colors)
    }

    @Test fun pendingThemeFollowsPlatformAcrossAccountsAndMissingResultClearsIt() = runBlocking {
        val identity = TrackThemeIdentity("qq", "one", "account", "remote")
        assertTrue(identity.sharesThemeOwner(identity.copy(stableId = "two", remoteId = "other")))
        assertFalse(identity.sharesThemeOwner(identity.copy(sourceId = "other")))
        assertTrue(identity.sharesThemeOwner(identity.copy(accountId = "other")))
        assertTrue(identity.sharesThemeOwner(identity.copy(accountId = null)))
        assertFalse(identity.sharesThemeOwner(null))
        val colors = mutableListOf<Long?>()
        CurrentTrackThemeResolver().resolve(false, { true }, { null },
            pendingArgb = 0xFF12AB34L) { colors += it }
        assertEquals(listOf(0xFF12AB34L, null), colors)
    }

    @Test fun latePreviousTrackCannotOverwriteNewTrackColor() = runBlocking {
        val resolver = CurrentTrackThemeResolver()
        val oldResponse = CompletableDeferred<Long?>()
        var visible: Long? = null
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            resolver.resolve(false, { true }, { oldResponse.await() }) { visible = it }
        }
        resolver.resolve(false, { true }, { 0xFF12AB34L }) { visible = it }
        oldResponse.complete(0xFFFF0000L)
        old.join()
        assertEquals(0xFF12AB34L, visible)
    }

    @Test fun removedOrReplacedInstanceRejectsInFlightResult() = runBlocking {
        for (replacement in listOf("removed", "other-instance")) {
            val resolver = CurrentTrackThemeResolver()
            var owner = "original-instance"
            val captured = owner
            val response = CompletableDeferred<Long?>()
            var visible: Long? = 0xFF123456L
            val pending = launch(start = CoroutineStart.UNDISPATCHED) {
                resolver.resolve(false, { owner == captured }, { response.await() }) { visible = it }
            }
            owner = replacement
            response.complete(0xFF445566L)
            pending.join()
            assertNull(visible)
        }
    }

    @Test fun missingInvalidAndFailedThemeStayNeutralOnEitherBackground() = runBlocking {
        val resolver = CurrentTrackThemeResolver()
        for (color in listOf(null, 0L, 0x00123456L, -1L, 0x1FF123456L)) {
            var visible: Long? = 0xFF123456L
            resolver.resolve(false, { true }, { color }) { visible = it }
            assertNull(visible)
            assertEquals(0xFF000000L, visible ?: fallbackTrackThemeArgb(false, false))
            assertEquals(0xFFFFFFFFL, visible ?: fallbackTrackThemeArgb(false, true))
        }
        var visible: Long? = 0xFF123456L
        resolver.resolve(false, { true }, { error("缺包或服务失效") }) { visible = it }
        assertNull(visible)
    }

    @Test fun localAlwaysKeepsPurpleWithoutCallingExternalService() = runBlocking {
        var calls = 0
        var visible: Long? = null
        CurrentTrackThemeResolver().resolve(true, { true }, { calls++; null }) { visible = it }
        assertEquals(0, calls)
        assertEquals(LOCAL_TRACK_COLOR_ARGB, visible)
        assertEquals(LOCAL_TRACK_COLOR_ARGB, fallbackTrackThemeArgb(true, false))
        assertEquals(LOCAL_TRACK_COLOR_ARGB, fallbackTrackThemeArgb(true, true))
    }

    @Test fun pageColorOnlyBackfillsSourcesWithoutTrackThemeService() {
        // 没有曲目主题服务：页面色作为全部曲目色。
        assertEquals(0xFF2E7D6BL, pageColorTrackThemeArgb(false, 0xFF2E7D6BL))
        // 提供了服务：完全由服务决定，返回 null 即保持黑／白，不借用页面色。
        assertEquals(null, pageColorTrackThemeArgb(true, 0xFF2E7D6BL))
        // 未声明或透明的页面色仍按未声明处理。
        assertEquals(null, pageColorTrackThemeArgb(false, null))
        assertEquals(null, pageColorTrackThemeArgb(false, 0x002E7D6BL))
    }
}
