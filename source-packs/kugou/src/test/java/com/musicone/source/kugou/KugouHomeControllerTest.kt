package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicReference

class KugouHomeControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun track(id: String) = SourceTrackDetails(SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, "42", id), id), "kugou-$id")
    private class Discovery : SourceDiscovery {
        var home: suspend (SourceCursorRequest) -> SourcePage<SourceHomeSection> = { SourcePage(listOf(SourceHomeSection("real", "真实推荐")), null) }
        var recent: suspend () -> SourceRecentSnapshot = { SourceRecentSnapshot() }
        override suspend fun home(request: SourceCursorRequest) = home.invoke(request)
        override suspend fun recentSnapshot() = recent.invoke()
    }

    @Test fun accountMemoryClearDropsOldTracksImmediatelyAndRestoresNewOwnerOnlyOnDemand() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = KugouHomeSnapshotStore(temporary.newFolder()) { "20261024" }
        store.save(KugouHomeState(owner = KugouHomeOwner("42", 0), daily = List(2507) { track("old-$it") },
            dailyLoaded = true, dailyDate = "20261024"))
        val owner = AtomicReference(KugouHomeOwner("42", 1))
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = emptyList<SourceTrackDetails>()
        }
        val controller = KugouHomeController(scope, owner::get, Discovery(), daily, store)
        try {
            controller.restoreSnapshot()
            assertEquals(2507, controller.state.value.daily.size)
            owner.set(KugouHomeOwner("77", 2))
            controller.clearMemory()
            assertTrue(controller.state.value.daily.isEmpty())
            assertTrue(controller.state.value.recent.tracks.isEmpty())
            controller.restoreSnapshot()
            assertEquals(owner.get(), controller.state.value.owner)
            assertTrue(controller.state.value.daily.isEmpty())
            owner.set(KugouHomeOwner("42", 3))
            controller.clearMemory()
            controller.restoreSnapshot()
            assertEquals(2507, controller.state.value.daily.size)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun startupRestoresContentWithoutWaitingForArtworkOrStartingNetwork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = KugouHomeSnapshotStore(temporary.newFolder()) { "20261024" }
        store.save(KugouHomeState(owner = KugouHomeOwner("42", 0), daily = listOf(track("saved")),
            dailyLoaded = true, dailyDate = "20261024", homeLoaded = true, homeDate = "20261024"))
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val networkCalls = java.util.concurrent.atomic.AtomicInteger()
        val discovery = Discovery().apply {
            home = { networkCalls.incrementAndGet(); SourcePage(emptyList(), null) }
            recent = { networkCalls.incrementAndGet(); SourceRecentSnapshot() }
        }
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> {
                networkCalls.incrementAndGet(); return emptyList()
            }
        }
        val controller = KugouHomeController(scope, { KugouHomeOwner("42", 1) }, discovery, daily, store) { snapshot ->
            assertEquals("kugou-saved", snapshot.daily.single().stableId)
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        try {
            controller.restoreSnapshot()
            assertEquals("kugou-saved", controller.state.value.daily.single().stableId)
            withTimeout(5_000) { entered.await() }
            assertEquals(0, networkCalls.get())
            // 可见入口仍可正常完成原网络核对，不等待封面准备。
            withTimeout(5_000) { controller.load().join() }
            assertEquals(1, networkCalls.get())
            assertEquals("kugou-saved", controller.state.value.daily.single().stableId)
            controller.close()
            withTimeout(5_000) { cancelled.await() }
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun artworkFailureKeepsRestoredSnapshotAndDoesNotPublishPageError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = KugouHomeSnapshotStore(temporary.newFolder()) { "20261024" }
        store.save(KugouHomeState(owner = KugouHomeOwner("42", 0), daily = listOf(track("saved")),
            dailyLoaded = true, dailyDate = "20261024"))
        val finished = CompletableDeferred<Unit>()
        val controller = KugouHomeController(scope, { KugouHomeOwner("42", 1) }, Discovery(),
            object : SourceDailyRecommendations {
                override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = emptyList<SourceTrackDetails>()
            }, store) {
            try { error("缓存损坏") } finally { finished.complete(Unit) }
        }
        try {
            controller.restoreSnapshot()
            withTimeout(5_000) { finished.await() }
            assertEquals("kugou-saved", controller.state.value.daily.single().stableId)
            assertNull(controller.state.value.homeError)
            assertNull(controller.state.value.dailyError)
            assertNull(controller.state.value.recentError)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun accountSwitchCancelsPreviousArtworkAndPreparesNewSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = AtomicReference(KugouHomeOwner("42", 0))
        val store = KugouHomeSnapshotStore(temporary.newFolder()) { "20261024" }
        for (account in listOf("42", "77")) store.save(KugouHomeState(
            owner = KugouHomeOwner(account, 0), daily = listOf(track(account)),
            dailyLoaded = true, dailyDate = "20261024"))
        val oldEntered = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val controller = KugouHomeController(scope, owner::get, Discovery(),
            object : SourceDailyRecommendations {
                override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = emptyList<SourceTrackDetails>()
            }, store) { snapshot ->
            if (snapshot.owner?.accountId == "42") {
                oldEntered.complete(Unit)
                try { awaitCancellation() } finally { oldCancelled.complete(Unit) }
            } else {
                assertEquals("kugou-77", snapshot.daily.single().stableId)
                newEntered.complete(Unit)
            }
        }
        try {
            controller.restoreSnapshot()
            withTimeout(5_000) { oldEntered.await() }
            owner.set(KugouHomeOwner("77", 1))
            controller.restoreSnapshot()
            withTimeout(5_000) { oldCancelled.await(); newEntered.await() }
            assertEquals("77", controller.state.value.owner!!.accountId)
            assertEquals("kugou-77", controller.state.value.daily.single().stableId)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun hidingDuringInterruptedNetworkReadDoesNotPublishAnError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = java.util.concurrent.CountDownLatch(1)
        val discovery = Discovery().apply {
            recent = {
                runInterruptible(Dispatchers.IO) {
                    entered.countDown()
                    try { java.util.concurrent.CountDownLatch(1).await() }
                    catch (error: InterruptedException) { throw java.io.InterruptedIOException("thread interrupted") }
                }
                SourceRecentSnapshot()
            }
        }
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = emptyList<SourceTrackDetails>()
        }
        val controller = KugouHomeController(scope, { KugouHomeOwner("42", 0) }, discovery, daily,
            KugouHomeSnapshotStore(temporary.newFolder()))
        try {
            val reading = controller.ensureRecent()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertSame(reading, controller.ensureRecent())
            controller.setVisible(false)
            withTimeout(5_000) { reading.join() }
            assertTrue(reading.isCancelled)
            assertNull(controller.state.value.recentError)
            assertFalse(controller.state.value.loadingRecent)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun sameControllerAcrossMidnightRefreshesDailyAndHomeOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val day = AtomicReference("20261023")
        val policies = mutableListOf<SourceFetchPolicy>()
        var homes = 0
        var recents = 0
        val discovery = Discovery().apply {
            home = { homes++; SourcePage(emptyList(), null) }
            recent = { recents++; SourceRecentSnapshot() }
        }
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> {
                policies += policy
                return listOf(track(day.get()))
            }
        }
        val controller = KugouHomeController(scope, { KugouHomeOwner("42", 0) }, discovery, daily,
            KugouHomeSnapshotStore(temporary.newFolder(), day::get))
        try {
            controller.load().join()
            controller.load().join()
            assertEquals(listOf(SourceFetchPolicy.CACHE_FIRST), policies)
            day.set("20261024")
            controller.load().join()
            controller.load().join()
            assertEquals(listOf(SourceFetchPolicy.CACHE_FIRST, SourceFetchPolicy.REFRESH), policies)
            assertEquals("20261024", controller.state.value.dailyDate)
            assertEquals("kugou-20261024", controller.state.value.daily.single().stableId)
            assertEquals(2, homes)
            assertEquals(1, recents)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun failedNewDayKeepsOldDailyWithoutLoopAndManualRefreshRecordsAttemptDay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val day = AtomicReference("20261023")
        var calls = 0
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> {
                calls++
                if (day.get() == "20261024") error("日推暂时失败")
                return listOf(track(day.get()))
            }
        }
        val store = KugouHomeSnapshotStore(temporary.newFolder(), day::get)
        val controller = KugouHomeController(scope, { KugouHomeOwner("42", 0) }, Discovery(), daily, store)
        try {
            controller.load().join()
            day.set("20261024")
            controller.load().join()
            controller.load().join()
            assertEquals(2, calls)
            assertEquals("日推暂时失败", controller.state.value.dailyError)
            assertEquals("20261023", controller.state.value.dailyDate)
            assertEquals("kugou-20261023", controller.state.value.daily.single().stableId)
            day.set("20261025")
            controller.refreshDaily().join()
            controller.load().join()
            assertEquals(3, calls)
            assertEquals("20261025", controller.state.value.dailyDate)
            assertNull(controller.state.value.dailyError)
            assertEquals("kugou-20261025", store.read("42")!!.daily.single().stableId)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun firstLoadAndExplicitRefreshKeepRealCountsAndReturningDoesNotReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val discovery = Discovery()
        var homes = 0
        var dailies = 0
        var recents = 0
        discovery.home = { homes++; SourcePage(listOf(SourceHomeSection("real", "推荐", tracks = listOf(track("home")))), null) }
        discovery.recent = { recents++; SourceRecentSnapshot(List(43) { track("recent$it") }, totalTracks = 81) }
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> {
                dailies++; return List(37) { track("daily$it") }
            }
        }
        val controller = KugouHomeController(scope, { KugouHomeOwner("42", 0) }, discovery, daily,
            KugouHomeSnapshotStore(temporary.newFolder()) { "20261023" })
        try {
            assertEquals(0, homes)
            controller.load().join()
            controller.load().join()
            assertEquals(1, homes); assertEquals(1, dailies); assertEquals(1, recents)
            assertEquals(37, controller.state.value.daily.size)
            assertEquals(81, controller.state.value.recent.totalTracks)
            assertEquals(43, controller.state.value.recent.tracks.size)
            controller.refreshHome().join()
            assertEquals(2, homes); assertEquals(2, dailies); assertEquals(2, recents)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun restartShowsSnapshotWhileVerificationWaitsAndFailureKeepsIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = KugouHomeSnapshotStore(temporary.newFolder()) { "20261023" }
        store.save(KugouHomeState(owner = KugouHomeOwner("42", 0), daily = listOf(track("saved")), dailyLoaded = true,
            dailyDate = "20261023", homeLoaded = true, recentLoaded = true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> {
                entered.complete(Unit); release.await(); error("真实网络失败")
            }
        }
        val controller = KugouHomeController(scope, { KugouHomeOwner("42", 1) }, Discovery(), daily, store)
        try {
            // 同日重启复用成功快照；显式刷新才进入网络核验。
            controller.load().join()
            val job = controller.refreshDaily()
            withTimeout(5_000) { entered.await() }
            assertEquals("kugou-saved", controller.state.value.daily.single().stableId)
            assertTrue(controller.state.value.loadingDaily)
            release.complete(Unit); job.join()
            assertEquals("kugou-saved", controller.state.value.daily.single().stableId)
            assertEquals("真实网络失败", controller.state.value.dailyError)
            assertEquals("kugou-saved", store.read("42")!!.daily.single().stableId)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun switchedAccountRejectsLateUncooperativeOldResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = AtomicReference(KugouHomeOwner("42", 0))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val daily = object : SourceDailyRecommendations {
            override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> {
                if (owner.get().accountId == "42") {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    return listOf(track("old"))
                }
                return listOf(track("new"))
            }
        }
        val store = KugouHomeSnapshotStore(temporary.newFolder()) { "20261023" }
        val controller = KugouHomeController(scope, owner::get, Discovery(), daily, store)
        try {
            val old = controller.load()
            entered.await()
            owner.set(KugouHomeOwner("77", 1))
            controller.load().join()
            release.complete(Unit); old.join()
            assertEquals("77", controller.state.value.owner!!.accountId)
            assertEquals("kugou-new", controller.state.value.daily.single().stableId)
            assertEquals("kugou-new", store.read("77")!!.daily.single().stableId)
            assertTrue(store.read("42")?.daily.orEmpty().isEmpty())
        } finally { release.complete(Unit); controller.close(); scope.cancel() }
    }

    @Test fun homePaginationConsumesEveryPageAndRepeatedCursorFailsWithoutLosingSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val discovery = Discovery()
        discovery.home = { request -> SourcePage(listOf(SourceHomeSection(request.cursor ?: "first", "推荐")), if (request.cursor == null) "next" else null) }
        val controller = KugouHomeController(scope, { KugouHomeOwner(null, 0) }, discovery,
            object : SourceDailyRecommendations { override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = emptyList<SourceTrackDetails>() },
            KugouHomeSnapshotStore(temporary.newFolder()))
        try {
            controller.load().join()
            assertEquals(listOf("first", "next"), controller.state.value.homeSections.map { it.id })
            discovery.home = { SourcePage(emptyList(), "repeat") }
            controller.refreshRecommendations().join()
            assertEquals(2, controller.state.value.homeSections.size)
            assertTrue(controller.state.value.homeError!!.contains("分页没有前进"))
        } finally { controller.close(); scope.cancel() }
    }
    @Test fun slowSnapshotSaveDoesNotBlockVisibilityOrAccountClear() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val owner = AtomicReference(KugouHomeOwner(null, 0))
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val controller = KugouHomeController(scope, owner::get, Discovery(),
            object : SourceDailyRecommendations { override suspend fun dailyRecommendations(policy: SourceFetchPolicy) = emptyList<SourceTrackDetails>() },
            KugouHomeSnapshotStore(temporary.newFolder()), persist = { _, guard ->
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                guard()
                writes.incrementAndGet()
            })
        try {
            val load = controller.load()
            withTimeout(5_000) { entered.await() }
            assertTrue(controller.state.value.homeLoaded)
            withTimeout(2_000) { withContext(Dispatchers.Default) {
                controller.setVisible(false)
                owner.set(KugouHomeOwner("77", 1))
                controller.clearMemory()
            } }
            release.countDown()
            withTimeout(5_000) { load.join() }
            assertEquals(0, writes.get())
            assertNull(controller.state.value.owner)
        } finally { release.countDown(); controller.close(); scope.cancel() }
    }

}
