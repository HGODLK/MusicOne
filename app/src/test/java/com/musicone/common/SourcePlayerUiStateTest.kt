package com.musicone

import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import org.junit.Assert.*
import org.junit.Test

class SourcePlayerUiStateTest {
    @Test fun deviceSupportUsesExplicitPhoneLandscapeDeclaration() {
        val phone = SourcePlayerUiDeclaration(true, false, false, SourcePlayerOpening(240, false, emptySet()))
        assertTrue(phone.supports(SourcePlayerForm.PHONE_PORTRAIT))
        assertFalse(phone.supports(SourcePlayerForm.PHONE_LANDSCAPE))
        assertFalse(phone.supports(SourcePlayerForm.TABLET))
        val tablet = phone.copy(supportsPhone = false, supportsTablet = true)
        assertFalse(tablet.supports(SourcePlayerForm.PHONE_PORTRAIT))
        assertTrue(tablet.supports(SourcePlayerForm.TABLET))
    }

    @Test fun delayedSeekRejectsPreviousTrackOrPlaybackGeneration() {
        val progress = PlaybackProgressSnapshot("current", 1500, 8)
        assertTrue(sourcePlayerSeekMatches("current", 8, "current", progress))
        assertFalse(sourcePlayerSeekMatches("previous", 8, "current", progress))
        assertFalse(sourcePlayerSeekMatches("current", 7, "current", progress))
        assertFalse(sourcePlayerSeekMatches("current", 8, "next", progress))
        assertFalse(sourcePlayerSeekMatches("", 8, "", progress.copy(trackId = "")))
    }

    @Test fun timelineDoesNotReusePreviousSongsPositionOrAdvancingState() {
        val track = PlayerWarmupTrack.copy(id = "current", durationMs = 10000)
        val state = MusicOneUiState(currentTrack = track, isPlaying = true)
        val previous = sourcePlayerTimeline(state, PlaybackProgressSnapshot("previous", 9000, 4),
            PlaybackActivity("previous", true))
        assertEquals(0L, previous.positionMs)
        assertFalse(previous.advancing)
        val paused = sourcePlayerTimeline(state, PlaybackProgressSnapshot("current", 1500, 5),
            PlaybackActivity("current", false))
        assertEquals(1500L, paused.positionMs)
        assertFalse(paused.advancing)
    }
}
