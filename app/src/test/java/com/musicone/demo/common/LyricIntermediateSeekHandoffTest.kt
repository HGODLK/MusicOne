package com.musicone.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricIntermediateSeekHandoffTest {
    @Test fun latePreviewCannotRearmAnAlreadyConfirmedCommit() {
        val handoff = LyricIntermediateSeekHandoff()
        val confirmed = PlaybackProgressSnapshot("song", 20_000L, 4L, 1_000L)
        val commit = ProgressLyricSeekCommit("song", 4L, 3L, 20_000L, confirmed)
        handoff.update(confirmed, null, confirmed, null, intermediate = true, enabled = true,
            observedCommit = commit, latestCommit = commit)
        assertNull(handoff.pendingCommit)

        val advanced = PlaybackProgressSnapshot("song", 25_000L, 4L, 6_000L)
        handoff.update(advanced, 20_000L, advanced, null, intermediate = true, enabled = true,
            observedCommit = commit, latestCommit = commit)
        assertNull("迟到预览不能重新发布旧定位目标", handoff.pendingCommit)
    }

    @Test fun confirmationStillUpdatesTheSamePendingCommit() {
        val handoff = LyricIntermediateSeekHandoff()
        val old = PlaybackProgressSnapshot("song", 90_000L, 4L, 900L)
        val pending = ProgressLyricSeekCommit("song", 4L, 3L, 20_000L)
        handoff.update(old, null, old, null, intermediate = true, enabled = true,
            observedCommit = pending, latestCommit = pending)
        assertNotNull(handoff.pendingCommit)
        val position = PlaybackProgressSnapshot("song", 20_000L, 4L, 1_000L)
        val confirmed = pending.copy(confirmed = position)
        handoff.update(position, null, position, null, intermediate = false, enabled = true,
            observedCommit = confirmed, latestCommit = confirmed)
        assertNull(handoff.pendingCommit)
        assertNotNull(handoff.acceptedCommit?.confirmed)
    }

    @Test fun backwardSeekDoesNotMistakeOldPositionForCommittedProgress() {
        val confirmed = PlaybackProgressSnapshot("song", 20_000L, 4L, 1_000L)
        val commit = ProgressLyricSeekCommit("song", 4L, 3L, 20_000L, confirmed)
        val old = PlaybackProgressSnapshot("song", 90_000L, 4L, 1_010L)
        assertFalse(lyricSeekCommitProgressCaughtUp(old, commit))
        assertFalse(lyricSeekCommitProgressCaughtUp(
            PlaybackProgressSnapshot("song", 20_000L, 4L, 999L), commit))
        assertTrue(lyricSeekCommitProgressCaughtUp(confirmed, commit))
        assertTrue(lyricSeekCommitProgressCaughtUp(
            PlaybackProgressSnapshot("song", 20_400L, 4L, 1_200L), commit))

        val handoff = LyricIntermediateSeekHandoff()
        handoff.update(old, null, confirmed, null, intermediate = true, enabled = true,
            observedCommit = commit, latestCommit = commit)
        assertNotNull(handoff.pendingCommit)
        handoff.update(confirmed, null, confirmed, null, intermediate = true, enabled = true,
            observedCommit = commit, latestCommit = commit)
        assertNull(handoff.pendingCommit)
    }
}
