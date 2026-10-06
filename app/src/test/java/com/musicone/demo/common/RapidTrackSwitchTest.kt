package com.musicone.demo

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RapidTrackSwitchTest {
    @Test fun visibleQqSingleClickWaitsForActualLyricsCompletion() = runBlocking {
        withFixture(waitSingle = true) {
            switch.request(true)
            val final = awaitSettling()
            assertEquals("b", final.track.id)
            delay(50)
            assertTrue(directTracks.isEmpty())
            assertTrue(commits.isEmpty())
            switch.lyricsSettled(owner, final.token)
            awaitCommit()
            assertEquals(listOf("b" to TrackTransitionDirection.NEXT), commits)
        }
    }

    @Test fun singleClickCannotLoadBeforeLocalLyricsLookupFinishes() = runBlocking {
        val lyrics = CompletableDeferred<List<TimedLyric>>()
        withFixture(cached = false, read = { lyrics.await() }, waitSingle = true) {
            switch.request(true)
            yield()
            assertEquals(RapidTrackSwitchPhase.SINGLE, switch.presentation.value?.phase)
            assertTrue(commits.isEmpty())
            lyrics.complete(emptyList())
            val final = awaitSettling()
            assertFalse(final.lyricsAvailable)
            switch.lyricsSettled(owner, final.token)
            awaitCommit()
        }
    }

    @Test fun secondClickInvalidatesSingleAnimationCompletion() = runBlocking {
        withFixture(waitSingle = true) {
            switch.request(true)
            val old = awaitSettling()
            switch.request(true)
            switch.lyricsSettled(owner, old.token)
            val final = awaitSettling()
            assertEquals("c", final.track.id)
            assertTrue(commits.isEmpty())
            switch.lyricsSettled(owner, final.token)
            awaitCommit()
            assertEquals(listOf("c" to TrackTransitionDirection.NEXT), commits)
        }
    }

    @Test fun singleClickWithoutVisibleLyricsKeepsImmediatePlayback() = runBlocking {
        withFixture(waitSingle = true) {
            switch.detachLyrics(owner)
            switch.request(true)
            assertEquals(listOf("b"), directTracks)
            assertNull(switch.presentation.value)
        }
    }

    @Test fun automaticTargetUsesTheSameWaitAndLeavingPageReleasesIt() = runBlocking {
        withFixture(waitSingle = true) {
            assertTrue(switch.handoff(tracks[2], TrackTransitionDirection.NEXT))
            awaitSettling()
            assertTrue(commits.isEmpty())
            switch.detachLyrics(owner)
            awaitCommit()
            assertEquals(listOf("c" to TrackTransitionDirection.NEXT), commits)
        }
    }

    @Test fun firstNextAndPreviousAreImmediateWithoutPreviewOrDelayedCommit() = runBlocking {
        for (next in listOf(true, false)) withFixture {
            switch.request(next)
            assertEquals(listOf(if (next) "b" else "d"), directTracks)
            assertNull(switch.presentation.value)
            delay(RAPID_TRACK_SETTLE_DELAY_MS + 30)
            assertTrue(commits.isEmpty())
            assertEquals(0, browsingStarts)
        }
    }

    @Test fun secondClickAtFourHundredMillisecondsEntersBrowsingButLaterClickIsDirect() = runBlocking {
        assertEquals(400L, RAPID_TRACK_SETTLE_DELAY_MS)
        for (interval in listOf(400L, 401L)) withFixture {
            switch.request(true)
            now = interval
            switch.request(true)
            assertEquals(if (interval == 400L) 1 else 2, directTracks.size)
            assertEquals(interval == 400L, switch.presentation.value != null)
        }
    }

    @Test fun finalPlaybackWaitsForLyricsInsteadOfCommittingAtTheInputTimeout() = runBlocking {
        withFixture {
            begin()
            val final = awaitSettling()
            assertEquals("c", final.track.id)
            delay(100)
            assertTrue(commits.isEmpty())
            switch.lyricsSettled(owner, final.token)
            awaitCommit()
            assertEquals(listOf("c" to TrackTransitionDirection.NEXT), commits)
            assertNull(switch.presentation.value)
            switch.request(false)
            assertEquals(listOf("b", "b"), directTracks)
            assertNull(switch.presentation.value)
        }
    }

    @Test fun reversedInputDuringSettlingKeepsBrowsingAndRejectsTheOldCompletion() = runBlocking {
        withFixture {
            begin()
            val old = awaitSettling()
            now = 2_000L
            switch.request(false)
            assertEquals(RapidTrackSwitchPhase.BROWSING, switch.presentation.value?.phase)
            assertEquals("b", switch.presentation.value?.track?.id)
            assertEquals(1, browsingStarts)
            switch.lyricsSettled(owner, old.token)
            val latest = awaitSettling()
            assertTrue(commits.isEmpty())
            switch.lyricsSettled(owner, latest.token)
            awaitCommit()
            assertEquals(listOf("b" to TrackTransitionDirection.PREVIOUS), commits)
        }
    }

    @Test fun cachedQueueWrapsAndReversesWithoutStartingIntermediateAudio() = runBlocking {
        withFixture {
            switch.request(false)
            var expected = 3
            val directions = List(40) { it % 5 < 3 }
            for (next in directions) {
                now += 100L
                expected = (expected + if (next) 1 else 3) % 4
                switch.request(next)
                assertEquals(tracks[expected].id, switch.presentation.value?.track?.id)
                assertTrue(switch.presentation.value?.lyricsAvailable == true)
                assertTrue(commits.isEmpty())
            }
            val final = awaitSettling()
            switch.lyricsSettled(owner, final.token)
            awaitCommit()
            assertEquals(listOf("d"), directTracks)
            assertEquals(listOf(tracks[expected].id to TrackTransitionDirection.PREVIOUS), commits)
        }
    }

    @Test fun leavingLyricsOrGoingToBackgroundReleasesTheAnimationWait() = runBlocking {
        withFixture {
            begin()
            awaitSettling()
            switch.detachLyrics(owner)
            awaitCommit()
            assertEquals("c", commits.single().first)
        }
    }

    @Test fun coverPageWithoutLyricsDoesNotRequireAnAnimationCallback() = runBlocking {
        withFixture {
            switch.detachLyrics(owner)
            begin()
            awaitCommit()
            assertEquals("c", commits.single().first)
        }
    }

    @Test fun aNewVisibleLyricsOwnerMustAlsoFinishBeforeCommit() = runBlocking {
        withFixture {
            begin()
            val final = awaitSettling()
            val secondOwner = Any()
            switch.attachLyrics(secondOwner)
            switch.lyricsSettled(owner, final.token)
            yield()
            assertTrue(commits.isEmpty())
            switch.detachLyrics(secondOwner)
            awaitCommit()
        }
    }

    @Test fun missingCachedLyricsRemainUnavailableWithoutCreatingAnEmptyPresentation() = runBlocking {
        withFixture(cached = false) {
            begin()
            val final = awaitSettling()
            assertFalse(final.lyricsAvailable)
            assertTrue(final.track.lyrics.isEmpty())
            assertEquals(LyricLoadState.LOADING,
                displayedLyricLoadState(true, final.lyricsAvailable, LyricLoadState.READY))
            switch.lyricsSettled(owner, final.token)
            awaitCommit()
            assertEquals(1, cacheReads)
        }
    }

    @Test fun lateCacheForAnAbandonedTargetCannotReplaceTheCurrentPreview() = runBlocking {
        val abandoned = CompletableDeferred<List<TimedLyric>>()
        withFixture(cached = false, read = { target ->
            if (target.id == "c") withContext(NonCancellable) { abandoned.await() }
            else listOf(TimedLyric(0L, "最新歌词"))
        }) {
            begin()
            yield()
            now += 100L
            switch.request(true)
            yield()
            abandoned.complete(listOf(TimedLyric(0L, "旧歌词")))
            val final = awaitSettling()
            assertEquals("d", final.track.id)
            assertEquals("最新歌词", final.track.lyrics.single().text)
            switch.lyricsSettled(owner, final.token)
            awaitCommit()
            assertEquals("d", commits.single().first)
        }
    }

    @Test fun explicitCancellationRejectsLateAnimationCallbacksAndStartsANewSingleClick() = runBlocking {
        withFixture {
            begin()
            val abandoned = awaitSettling()
            switch.cancel()
            switch.lyricsSettled(owner, abandoned.token)
            yield()
            assertTrue(commits.isEmpty())
            switch.request(false)
            assertEquals(listOf("b", "a"), directTracks)
            assertNull(switch.presentation.value)
        }
    }

    private suspend fun CoroutineScope.withFixture(cached: Boolean = true,
        read: suspend (MusicTrack) -> List<TimedLyric> = { emptyList() },
        waitSingle: Boolean = false,
        block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this, cached, read, waitSingle)
        try { fixture.block() } finally { fixture.switch.cancel() }
    }

    private class Fixture(scope: CoroutineScope, cached: Boolean, read: suspend (MusicTrack) -> List<TimedLyric>,
        waitSingle: Boolean) {
        val tracks = listOf("a", "b", "c", "d").map { id ->
            MusicTrack(id, MusicSource.QQ, id, "歌手", "专辑", 180_000L, 0, 0, id, "",
                lyrics = if (cached) listOf(TimedLyric(0L, id)) else emptyList())
        }
        var state = MusicOneUiState(currentTrack = tracks.first(), queue = tracks)
        var now = 0L
        var browsingStarts = 0
        var cacheReads = 0
        val owner = Any()
        val directTracks = mutableListOf<String>()
        val commits = mutableListOf<Pair<String, TrackTransitionDirection>>()
        val switch: RapidTrackSwitch = RapidTrackSwitch(
            scope, cachedLyrics = { cacheReads++; read(it) },
            neighbor = { anchor, next, _ -> rapidQueueNeighbor(state, anchor, next) },
            commit = { target, direction ->
                commits += target.id to direction
                state = state.copy(currentTrack = target)
            },
            direct = { next -> direct(next) },
            beginBrowsing = { browsingStarts++ },
            nowMs = { now },
            waitForSingleLyrics = { waitSingle },
        )

        init { switch.attachLyrics(owner) }
        private fun direct(next: Boolean) {
            switch.cancel()
            val target = requireNotNull(rapidQueueNeighbor(state, null, next))
            state = state.copy(currentTrack = target)
            directTracks += target.id
        }
        fun begin() {
            switch.request(true)
            now = 100L
            switch.request(true)
        }
        suspend fun awaitSettling(): RapidTrackSwitchPresentation = withTimeout(2_000L) {
            requireNotNull(switch.presentation.first { it?.phase == RapidTrackSwitchPhase.SETTLING })
        }
        suspend fun awaitCommit() = withTimeout(2_000L) {
            switch.presentation.first { it == null }
        }
    }
}
