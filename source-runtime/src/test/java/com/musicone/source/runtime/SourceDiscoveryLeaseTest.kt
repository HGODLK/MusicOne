package com.musicone.source.runtime

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceDiscoveryLeaseTest {
    @Test fun mySectionDeclarationSurvivesGuardAndChecksLease() {
        var active = true
        var show = false
        val source = object : SourceDiscovery, SourceMySectionPresentation {
            override suspend fun home(request: SourceCursorRequest) = SourcePage<SourceHomeSection>(emptyList(), null)
            override val showRecentInMy get() = show
        }
        val guarded = guardSourceDiscovery(source, SourceOperationLease { check(active) })
        assertTrue(guarded is SourceMySectionPresentation)
        val declaration = guarded as SourceMySectionPresentation
        assertFalse(declaration.showRecentInMy)
        show = true
        assertTrue(declaration.showRecentInMy)
        active = false
        try { declaration.showRecentInMy; fail("失效租约不得读取旧包声明") }
        catch (_: IllegalStateException) { }
    }

    @Test fun myDeclarationKeepsExistingOptionalCapabilities() = runBlocking {
        val source = object : SourceDiscovery, SourceMySectionPresentation, SourceDailyRecommendations,
            SourceHomeSectionPresentation, SourceHomeContinuation {
            override val showRecentInMy = false
            override suspend fun home(request: SourceCursorRequest) = SourcePage<SourceHomeSection>(emptyList(), null)
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = emptyList<SourceTrackDetails>()
            override fun homePresentation(section: SourceHomeSection) = SourceHomePresentation(section.id)
            override fun hasBufferedHomeItems(cursor: String) = cursor == "buffered"
        }
        val guarded = guardSourceDiscovery(source, SourceOperationLease {})
        assertFalse((guarded as SourceMySectionPresentation).showRecentInMy)
        assertTrue(guarded is SourceDailyRecommendations)
        assertTrue(guarded is SourceHomeSectionPresentation)
        assertTrue((guarded as SourceHomeContinuation).hasBufferedHomeItems("buffered"))
    }

    @Test fun legacyDiscoveryDoesNotPretendToHaveOptionalCapabilities() {
        val old = object : SourceDiscovery {
            override suspend fun home(request: SourceCursorRequest) = SourcePage<SourceHomeSection>(emptyList(), null)
        }
        val guarded = guardSourceDiscovery(old, SourceOperationLease {})
        assertFalse(guarded is SourceDailyRecommendations)
        assertFalse(guarded is SourceHomeSectionPresentation)
        assertFalse(guarded is SourceHomeContinuation)
        assertFalse(guarded is SourceMySectionPresentation)
    }

    @Test fun independentDailyAndShelfSemanticsSurviveGuardAndStillCheckLease() = runBlocking {
        var active = true
        var calls = 0
        val source = object : SourceDiscovery, SourceDailyRecommendations, SourceHomeSectionPresentation {
            override suspend fun home(request: SourceCursorRequest) = error("每日推荐不能请求普通信息流")
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> {
                calls++
                return (1..30).map { SourceTrackDetails(SourceTrack(TrackIdentity("org.example.source", null, "$it"), "$it"), "$it") }
            }
            override fun homePresentation(section: SourceHomeSection) = SourceHomePresentation(section.id,
                mergePages = true, recommendation = true, groupId = "official", groupLimit = 3)
        }
        val guarded = guardSourceDiscovery(source, SourceOperationLease { check(active) })
        assertEquals(30, (guarded as SourceDailyRecommendations).dailyRecommendations(SourceFetchPolicy.REFRESH).size)
        assertEquals(1, calls)
        val layout = guarded as SourceHomeSectionPresentation
        assertEquals(3, layout.homePresentation(SourceHomeSection("shelf", "推荐")).groupLimit)
        active = false
        try { layout.homePresentation(SourceHomeSection("shelf", "推荐")); fail("失效租约必须拒绝读取") }
        catch (_: IllegalStateException) { }
        try { guarded.dailyRecommendations(SourceFetchPolicy.REFRESH); fail("失效租约必须拒绝请求") }
        catch (_: IllegalStateException) { }
        assertEquals(1, calls)
    }

    @Test fun bufferedHintsSurviveEveryCapabilityCombinationAndRejectExpiredLease() {
        val discovery = object : SourceDiscovery {
            override suspend fun home(request: SourceCursorRequest) = error("查询暂存提示不能加载首页")
        }
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = emptyList<SourceTrackDetails>()
        }
        val layout = object : SourceHomeSectionPresentation {
            override fun homePresentation(section: SourceHomeSection) = SourceHomePresentation(section.id)
        }
        var calls = 0
        val continuation = object : SourceHomeContinuation {
            override fun hasBufferedHomeItems(cursor: String): Boolean { calls++; return cursor == "buffered" }
        }
        val sources = listOf(
            discovery,
            object : SourceDiscovery by discovery, SourceDailyRecommendations by daily {},
            object : SourceDiscovery by discovery, SourceHomeSectionPresentation by layout {},
            object : SourceDiscovery by discovery, SourceDailyRecommendations by daily, SourceHomeSectionPresentation by layout {},
            object : SourceDiscovery by discovery, SourceHomeContinuation by continuation {},
            object : SourceDiscovery by discovery, SourceDailyRecommendations by daily, SourceHomeContinuation by continuation {},
            object : SourceDiscovery by discovery, SourceHomeSectionPresentation by layout, SourceHomeContinuation by continuation {},
            object : SourceDiscovery by discovery, SourceDailyRecommendations by daily, SourceHomeSectionPresentation by layout, SourceHomeContinuation by continuation {},
        )
        sources.forEach { source ->
            var active = true
            val guarded = guardSourceDiscovery(source, SourceOperationLease { check(active) })
            assertEquals(source is SourceDailyRecommendations, guarded is SourceDailyRecommendations)
            assertEquals(source is SourceHomeSectionPresentation, guarded is SourceHomeSectionPresentation)
            assertEquals(source is SourceHomeContinuation, guarded is SourceHomeContinuation)
            if (guarded is SourceHomeContinuation) {
                assertTrue(guarded.hasBufferedHomeItems("buffered"))
                assertFalse(guarded.hasBufferedHomeItems("remote-only"))
                active = false
                val previous = calls
                try { guarded.hasBufferedHomeItems("buffered"); fail("失效租约不得读取旧包暂存池") }
                catch (_: IllegalStateException) { }
                assertEquals(previous, calls)
            }
        }
        assertEquals(8, calls)
    }
}
