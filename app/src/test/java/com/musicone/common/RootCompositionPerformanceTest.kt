package com.musicone

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RootCompositionPerformanceTest {
    @get:org.junit.Rule val sources = InstalledSourceContractsRule()
    @Test fun playerFrameRateGuardsReadLiveConditionsAndReleaseOnDisposal() = withComposition {
        val policy = PlayerFrameRatePolicy()
        policy.registerIdle(policy) { true }
        var mounted by mutableStateOf(true)
        var busy by mutableStateOf(false)
        composition.setContent {
            CompositionLocalProvider(LocalPlayerFrameRatePolicy provides policy) {
                if (mounted) PlayerHighFrameRateWhile { busy }
            }
        }
        frames()
        assertEquals(AnimationFrameRate.FPS_90, policy.resolve(AnimationFrameRate.FPS_90))
        busy = true
        assertEquals(AnimationFrameRate.FPS_90, policy.resolve(AnimationFrameRate.FPS_90))
        mounted = false
        frames()
        assertEquals(AnimationFrameRate.FPS_90, policy.resolve(AnimationFrameRate.FPS_90))
    }

    @Test fun lyricMotionOnlyRecomposesStageWhenPresentationChanges() = withComposition {
        val motion = PhoneLyricsMotion(false)
        var requested by mutableStateOf(true)
        var renders = 0
        var presentation: PhonePlayerStagePresentation? = null
        composition.setContent {
            val value = rememberPhonePlayerStagePresentation(motion, requested, true, MusicSource.QQ)
            SideEffect { presentation = value; renders++ }
        }
        motion.lyrics.snapTo(.01f)
        motion.header.snapTo(.01f)
        frames()
        val opening = renders
        for (i in 2..99) {
            motion.lyrics.snapTo(i / 100f)
            motion.header.snapTo(i / 100f)
            frames()
        }
        assertEquals(opening, renders)
        motion.lyrics.snapTo(1f)
        motion.header.snapTo(1f)
        frames()
        assertTrue(checkNotNull(presentation).fullyOpen)
        assertFalse(checkNotNull(presentation).intermediate)
        requested = false
        motion.lyrics.snapTo(.99f)
        motion.header.snapTo(.99f)
        frames()
        val closing = renders
        for (i in 98 downTo 1) {
            motion.lyrics.snapTo(i / 100f)
            motion.header.snapTo(i / 100f)
            frames()
        }
        assertEquals(closing, renders)
        motion.lyrics.snapTo(0f)
        motion.header.snapTo(0f)
        frames()
        assertFalse(checkNotNull(presentation).lyricsActive)
        assertTrue(checkNotNull(presentation).informationMenu)
    }

    @Test fun cacheSettingsApplyOnlyAfterLeavingEntireSettingsAndClosingAnimationFinishes() = withComposition {
        var destination by mutableStateOf(SettingsDestination.CACHED_MUSIC)
        var mounted by mutableStateOf(true)
        var progressText by mutableStateOf("未缓存")
        var applies = 0
        val draft = CacheSettingsDraft()
        val applied = CacheLimits()
        composition.setContent {
            progressText
            ApplyCacheSettingsOnExit(destination, mounted) { if (draft.consume() != null) applies++ }
        }
        frames()
        draft.select(applied, false, CacheCapacity.GB20)
        repeat(8) { progressText = "正在缓存 $it"; frames() }
        destination = SettingsDestination.CACHED_PLAYLIST
        frames()
        destination = SettingsDestination.CACHED_MUSIC
        frames()
        destination = SettingsDestination.SETTINGS
        frames()
        assertEquals(0, applies)
        destination = SettingsDestination.CLOSED
        frames()
        assertEquals(0, applies)
        mounted = false
        frames()
        assertEquals(1, applies)
        progressText = "已缓存"
        frames()
        assertEquals(1, applies)
    }

    @Test fun sourceLibraryRowsStayVisibleWhileTheRootPlaylistIsClosedOrMoving() = withComposition {
        val rootMotion = PageMotion(CoroutineScope(kotlin.coroutines.coroutineContext), initialOpen = false,
            enterMillis = 360, exitMillis = 280)
        var inside = -1f
        var outside = -1f
        composition.setContent {
            CompositionLocalProvider(LocalPlaylistMotion provides rootMotion, LocalPlaylistContentEntrance provides { 0f }) {
                SourceLibraryContentScope {
                    val page = LocalPlaylistMotion.current
                    val entrance = LocalPlaylistContentEntrance.current
                    SideEffect { inside = playlistDetailProgress(page?.value ?: 1f, entrance()) }
                }
                val page = LocalPlaylistMotion.current
                val entrance = LocalPlaylistContentEntrance.current
                SideEffect { outside = playlistDetailProgress(page?.value ?: 1f, entrance()) }
            }
        }
        frames()
        assertEquals(0f, outside, 0f)
        assertEquals(1f, inside, 0f)
        rootMotion.progress.snapTo(.5f)
        frames()
        assertEquals(0f, outside, 0f)
        assertEquals(1f, inside, 0f)
    }

    @Test fun searchProgressOnlyRecomposesTheRootWhenMountingChanges() = withComposition {
        val motion = MainSourceSearchMotion()
        var renders = 0
        var mounted = false
        composition.setContent {
            val visible = rememberMainSourceSearchMounted(motion).value
            SideEffect { mounted = visible; renders++ }
        }
        frames()
        assertFalse(mounted)
        val initial = renders
        motion.menu.snapTo(.1f)
        frames()
        assertTrue(mounted)
        assertEquals(initial + 1, renders)
        motion.menu.snapTo(.7f)
        frames()
        assertEquals(initial + 1, renders)
        motion.full.snapTo(.5f)
        motion.menu.snapTo(0f)
        frames()
        assertTrue(mounted)
        assertEquals(initial + 1, renders)
        motion.full.snapTo(0f)
        frames()
        assertFalse(mounted)
        assertEquals(initial + 2, renders)
    }

    @Test fun unrelatedRootUpdatesKeepFavoritesStableWithoutKeepingOldCallbacks() = withComposition {
        var revision by mutableIntStateOf(0)
        var remoteState by mutableStateOf(MusicFavoriteUiState(ids = setOf("qq-one")))
        var localIds by mutableStateOf(setOf("local-one"))
        val calls = mutableListOf<String>()
        var actions: MusicFavoriteActions? = null
        composition.setContent {
            val version = revision
            val remote = MusicFavoriteActions(remoteState,
                toggle = { calls += "remote-$version" },
                toggleDeferredRemoval = { calls += "deferred-$version" },
                flushDeferredRemovals = { complete -> calls += "flush-$version"; complete() },
                currentState = { remoteState })
            val combined = rememberLibraryFavoriteActions(remote, localIds) { calls += "local-$version" }
            SideEffect { actions = combined }
        }
        frames()
        val first = checkNotNull(actions)
        val ids = first.state.ids
        revision++
        frames()
        assertSame(first, actions)
        assertSame(ids, checkNotNull(actions).state.ids)
        first.toggle(PlayerWarmupTrack)
        first.toggle(PlayerWarmupTrack.copy(source = MusicSource.LOCAL))
        first.toggleDeferredRemoval(PlayerWarmupTrack)
        first.toggleDeferredRemoval(PlayerWarmupTrack.copy(source = MusicSource.BILIBILI))
        first.flushDeferredRemovals { calls += "complete" }
        assertEquals(listOf("remote-1", "local-1", "deferred-1", "local-1", "flush-1", "complete"), calls)

        remoteState = remoteState.copy(ids = setOf("qq-two"), deferredRemovalIds = setOf("qq-one"))
        localIds = setOf("bilibili-two")
        frames()
        val updated = checkNotNull(actions)
        assertEquals(setOf("qq-two", "bilibili-two"), updated.state.ids)
        assertEquals(setOf("qq-one"), updated.state.deferredRemovalIds)
        assertSame(first.toggle, updated.toggle)
        assertSame(first.toggleDeferredRemoval, updated.toggleDeferredRemoval)
        assertSame(remoteState, first.currentState())
        localIds = emptySet()
        frames()
        assertSame(remoteState, checkNotNull(actions).state)
    }

    private fun withComposition(block: suspend RuntimeComposition.() -> Unit) = runBlocking {
        val runtime = RuntimeComposition(this)
        try { runtime.block() } finally { runtime.close() }
    }

    private class RuntimeComposition(scope: CoroutineScope) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        private val job = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        private var time = 0L

        suspend fun frames() {
            repeat(4) {
                Snapshot.sendApplyNotifications()
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                yield()
            }
        }

        fun close() { composition.dispose(); recomposer.cancel(); job.cancel() }
    }
}
