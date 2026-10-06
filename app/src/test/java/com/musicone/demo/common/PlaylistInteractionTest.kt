package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlaylistInteractionTest {
    @Test
    fun onlyQqPlaylistReturnRequestsLibrarySync() {
        assertTrue(shouldSyncQqLibraryAfterPlaylistReturn(sourcePlaylist("qq-list", MusicSource.QQ)))
        assertFalse(shouldSyncQqLibraryAfterPlaylistReturn(sourcePlaylist("netease-list", MusicSource.NETEASE)))
    }

    private fun track(id: String, title: String = id) = MusicTrack("qq-$id", MusicSource.QQ, title,
        "歌手", "专辑", 1000L, 0L, 0L, id, "")
    private fun playlist(tracks: List<MusicTrack>) = MusicPlaylist(QQ_FAVORITES_PLAYLIST_ID,
        MusicSource.QQ, "我喜欢", "", "", tracks.size, 0L, 0L, "喜", tracks)
    private fun sourcePlaylist(id: String, source: MusicSource) = MusicPlaylist(
        id, source, "歌单", "", "", 0, 0L, 0L, "歌", emptyList(),
    )

    @Test fun sortingDoesNotOverwriteOriginalJoinOrder() {
        val original = listOf(track("2", "B"), track("1", "A"), track("3", "C"))
        assertEquals(listOf("A", "B", "C"), sortedPlaylistTracks(original, PlaylistSort.NAME).map { it.title })
        assertEquals(original.reversed(), sortedPlaylistTracks(original, PlaylistSort.ADDED_ASC))
        assertEquals(original, sortedPlaylistTracks(original, PlaylistSort.ADDED_DESC))
    }

    @Test fun removingAndUndoingFavoriteKeepsCountsAndDoesNotDuplicateTracks() {
        val first = track("1")
        val second = track("2")
        val initial = playlist(listOf(first, second))
        val removal = mapOf(first.id to (first to false))
        val removed = initial.withFavoriteChanges(removal)
        assertEquals(listOf(second), removed.tracks)
        assertEquals(1, removed.count)
        assertEquals(removed, removed.withFavoriteChanges(removal))
        val undo = mapOf(first.id to (first to true))
        val restored = removed.withFavoriteChanges(undo)
        assertEquals(initial, restored)
        assertEquals(restored, restored.withFavoriteChanges(undo))
    }

    @Test fun existingFavoriteUsesLocallyEnrichedArtwork() {
        val remote = track("1").copy(artworkUrl = null, album = "")
        val enriched = remote.copy(artworkUrl = "https://example.com/cover.jpg", album = "正式专辑")

        val updated = playlist(listOf(remote)).withFavoriteChanges(
            mapOf(remote.id to (enriched to true)),
        )

        assertEquals(enriched, updated.tracks.single())
    }

    @Test fun resolvedFirstTrackSuppliesMissingPlaylistArtwork() {
        val unresolved = track("1").copy(artworkUrl = null)
        val resolved = unresolved.copy(artworkUrl = "https://example.com/first-cover.jpg")

        val updated = playlist(listOf(unresolved)).withResolvedTrackArtwork(listOf(resolved))

        assertEquals(resolved.artworkUrl, updated.artworkUrl)
        assertEquals(resolved, updated.tracks.single())
    }

    @Test fun independentPlaylistArtworkIsNotOverwrittenByFirstTrack() {
        val first = track("1").copy(artworkUrl = "https://example.com/first-cover.jpg")
        val playlistCover = "https://example.com/playlist-cover.jpg"

        val updated = playlist(listOf(first)).copy(artworkUrl = playlistCover)
            .withResolvedTrackArtwork(listOf(first))

        assertEquals(playlistCover, updated.artworkUrl)
    }

    @Test fun onlyMissingQqPlaylistAndFirstTrackNeedArtworkAliasResolution() {
        val missing = playlist(listOf(track("1").copy(artworkUrl = null)))
        assertTrue(missing.needsQqFirstTrackArtworkAlias())
        assertFalse(missing.copy(artworkUrl = "https://example.com/playlist.jpg")
            .needsQqFirstTrackArtworkAlias())
        assertFalse(missing.copy(tracks = listOf(
            track("1").copy(artworkUrl = "https://example.com/first.jpg"),
        ))
            .needsQqFirstTrackArtworkAlias())
        assertFalse(missing.copy(source = MusicSource.NETEASE)
            .needsQqFirstTrackArtworkAlias())
    }

    @Test fun radioSwipeCannotWrapAtEitherEnd() {
        val queue = listOf(track("1"), track("2"))
        val state = MusicOneUiState(currentTrack = queue.first(), queue = queue, qqRadioActive = true)
        val history = PlaybackHistory()
        assertNull(miniPlayerNeighbor(state, history, false))
        assertEquals(queue.last(), miniPlayerNeighbor(state, history, true))
        assertNull(miniPlayerNeighbor(state.copy(currentTrack = queue.last()), history, true))
    }

    @Test fun previewingPreviousDoesNotConsumeShuffleHistory() {
        val queue = listOf(track("1"), track("2"), track("3"))
        val history = PlaybackHistory()
        history.remember(queue.first().id, queue.last().id)
        val state = MusicOneUiState(currentTrack = queue.last(), queue = queue, shuffle = true)
        repeat(3) { assertEquals(queue.first(), miniPlayerNeighbor(state, history, false)) }
        assertEquals(queue.first().id, history.takePrevious(queue.mapTo(hashSetOf()) { it.id }))
    }

    @Test fun releasedCoverContinuesFromFingerAndEndsAtOriginalCard() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280)
        val source = Rect(20f, 100f, 170f, 250f)
        val target = Rect(0f, 0f, 400f, 400f)
        val pull = PlaylistPullState().apply { active = true; displacement = Offset(30f, 160f) }
        motion.beginDrag()
        motion.dragTo(.65f)
        val releasedBounds = pulledPlaylistArtworkBounds(target, Offset(30f, 160f), .65f)
        assertEquals(releasedBounds, pull.artworkBounds(motion, source, target))
        assertTrue(releasedBounds.width < target.width)
        assertEquals(target.center.x + 30f, releasedBounds.center.x, .001f)
        pull.released = true
        pull.releaseProgress = motion.value
        motion.request(false)
        assertEquals(releasedBounds, pull.artworkBounds(motion, source, target))
        repeat(40) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertEquals(source, pull.artworkBounds(motion, source, target))
        assertFalse(motion.mounted)
        coroutineContext.cancelChildren()
    }

    @Test fun pulledPlaylistKeepsWholePageVisibleForContinuousFade() = runBlocking {
        val motion = PageMotion(CoroutineScope(coroutineContext), true, 320, 280)
        val host = Rect(10f, 20f, 410f, 820f)
        val source = Rect(30f, 120f, 180f, 270f)
        val target = Rect(10f, 20f, 410f, 420f)
        motion.updateHost(host)
        motion.sources[motion.coverKey] = MotionAnchor(source)
        motion.targets[motion.coverKey] = MotionAnchor(target)
        assertTrue(motion.beginDrag())
        motion.dragTo(.65f)
        val pull = PlaylistPullState().apply {
            active = true
            displacement = Offset(30f, 160f)
        }
        val expected = Rect(0f, 0f, host.width, host.height)

        val outline = PlaylistRevealShape(motion, pull).createOutline(
            Size(host.width, host.height),
            LayoutDirection.Ltr,
            Density(1f),
        ) as Outline.Rectangle

        assertEquals(expected, outline.rect)
        assertTrue(playlistDetailProgress(.99f, 1f) > .98f)
        assertTrue(playlistDetailProgress(.65f, 1f) in .5f.. .6f)
        coroutineContext.cancelChildren()
    }

    @Test fun miniSwipePreviewAndCancellationNeverCommitPlayback() = runBlocking {
        val clock = BroadcastFrameClock()
        val swipe = MiniPlayerSwipe(CoroutineScope(coroutineContext + clock))
        var commits = 0
        swipe.outgoing = track("1")
        swipe.incoming = track("2")
        swipe.amount = .4f
        assertEquals(0, commits)
        swipe.finish(false) { _, _ -> commits++ }
        repeat(30) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertEquals(0, commits)
        assertFalse(swipe.active)
        swipe.outgoing = track("1")
        swipe.incoming = track("2")
        swipe.amount = 1f
        swipe.finish(true) { selected, next ->
            assertEquals(track("2"), selected)
            assertTrue(next)
            commits++
        }
        assertEquals(1, commits)
        coroutineContext.cancelChildren()
    }

    @Test fun committedMiniSwipeKeepsOverlayUntilTargetArtworkHasBeenDrawn() = runBlocking {
        val clock = BroadcastFrameClock()
        val swipe = MiniPlayerSwipe(CoroutineScope(coroutineContext + clock))
        val first = track("1")
        val second = track("2")
        swipe.outgoing = first
        swipe.incoming = second
        swipe.amount = 1f
        swipe.finish(true) { _, _ -> }
        repeat(20) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertTrue(swipe.active)
        swipe.onTrackPresented(second)
        repeat(3) { yield(); clock.sendFrame((it + 20) * 16_000_000L) }
        yield()
        assertTrue(swipe.active)
        swipe.onTargetBaseArtworkDrawn(second, PlayerArtworkFrame(first.artworkIdentity(), null))
        assertTrue(swipe.active)
        swipe.onTargetBaseArtworkDrawn(second, PlayerArtworkFrame(second.artworkIdentity(), null))
        assertTrue(swipe.active)
        swipe.onTargetOverlayArtworkDrawn(second, PlayerArtworkFrame(second.artworkIdentity(), null))
        assertFalse(swipe.active)
        coroutineContext.cancelChildren()
    }

    @Test fun committedMiniSwipeAlsoHandsOffWhenArtworkIsDrawnBeforeSettleEnds() = runBlocking {
        val clock = BroadcastFrameClock()
        val swipe = MiniPlayerSwipe(CoroutineScope(coroutineContext + clock))
        val first = track("1")
        val second = track("2")
        swipe.outgoing = first
        swipe.incoming = second
        swipe.amount = .85f
        swipe.finish(true) { _, _ -> }
        swipe.onTrackPresented(second)
        swipe.onTargetBaseArtworkDrawn(second, PlayerArtworkFrame(second.artworkIdentity(), null))
        swipe.onTargetOverlayArtworkDrawn(second, PlayerArtworkFrame(second.artworkIdentity(), null))
        assertTrue(swipe.active)
        repeat(20) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertFalse(swipe.active)
        coroutineContext.cancelChildren()
    }

    @Test fun playlistReleaseVelocityFollowsFingerDirectionAndIsBounded() {
        assertEquals(-.5f, playlistProgressVelocity(500f, 1_000f), .0001f)
        assertEquals(.5f, playlistProgressVelocity(-500f, 1_000f), .0001f)
        assertEquals(-1.6f, playlistProgressVelocity(9_000f, 1_000f), .0001f)
    }
}
