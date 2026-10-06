package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class ImmersivePlayerTest {
    @Test fun playbackModeCyclesListShuffleSingleAndBack() {
        var mode = playerPlaybackMode(false, RepeatMode.ALL)
        assertEquals(PlayerPlaybackMode.LIST, mode)
        mode = mode.next()
        assertEquals(PlayerPlaybackMode.SHUFFLE, mode)
        mode = mode.next()
        assertEquals(PlayerPlaybackMode.SINGLE, mode)
        assertEquals(PlayerPlaybackMode.LIST, mode.next())
        assertEquals(PlayerPlaybackMode.SINGLE, playerPlaybackMode(false, RepeatMode.ONE))
        assertEquals(PlayerPlaybackMode.SHUFFLE, playerPlaybackMode(true, RepeatMode.ALL))
    }

    @Test fun playedAndCurrentLyricsRemainSharpWhileFutureLyricsBlur() {
        assertEquals(0f, lyricBlurDp(2, 3))
        assertEquals(0f, lyricBlurDp(3, 3))
        assertTrue(lyricBlurDp(4, 3) > 0f)
        assertEquals(0f, lyricBlurDp(4, 4))
    }
    @Test fun lyricSelectionUsesTimestampBoundaries() {
        val lines = listOf(TimedLyric(0, "第一句"), TimedLyric(20_000, "第二句"), TimedLyric(240_000, "第三句"))
        assertEquals(0, activeLyric(lines, 19_999))
        assertEquals(1, activeLyric(lines, 20_000))
        assertEquals(2, activeLyric(lines, 240_000))
    }

    @Test fun lyricDisplayLeadsPlaybackAndTapSeeksSlightlyEarlier() {
        assertEquals(21_000L, lyricDisplayPosition(20_000L))
        assertEquals(0f, lyricSeekFraction(200L, 10_000L))
        assertEquals(.165f, lyricSeekFraction(2_000L, 10_000L), .0001f)
    }

    @Test fun lrcParserSupportsMultipleTimestampsAndFractions() {
        val lines = parseLrc("[00:01.20][00:03.004]第一句\n[01:02]第二句\n[ar:歌手]")
        assertEquals(listOf(1_200L, 3_004L, 62_000L), lines.map(TimedLyric::timeMs))
        assertEquals(listOf("第一句", "第一句", "第二句"), lines.map(TimedLyric::text))
    }

    @Test fun translatedLyricsMergeWithSmallTimestampDifferences() {
        val original = listOf(TimedLyric(1_000, "Hello"), TimedLyric(3_000, "World"))
        val translated = listOf(TimedLyric(1_080, "你好"), TimedLyric(3_400, "世界"))

        assertEquals(listOf("你好", "世界"), mergeLyricTranslation(original, translated).map { it.translation })
    }

    @Test fun miniPlayerAccessoriesFadeAcrossPlayerTransition() {
        assertEquals(1f, miniPlayerAccessoryAlpha(0f), .0001f)
        assertEquals(.5f, miniPlayerAccessoryAlpha(.3f), .0001f)
        assertEquals(0f, miniPlayerAccessoryAlpha(.6f), .0001f)
        assertEquals(0f, miniPlayerAccessoryAlpha(1f), .0001f)
    }

    @Test fun playbackControlsSettleByDistanceOrDownwardVelocity() {
        assertFalse(playerControlsShouldHide(.35f, 0f))
        assertTrue(playerControlsShouldHide(.36f, 0f))
        assertTrue(playerControlsShouldHide(.1f, 901f))
        assertFalse(playerControlsShouldHide(.8f, -901f))
        assertEquals(120f, playerControlsTranslation(.5f, 240f), .0001f)
    }

    @Test fun lyricScrollAnimatesNearbyLinesAndSnapsLongSeeks() {
        assertTrue(shouldAnimateLyricScroll(10, 12))
        assertTrue(shouldAnimateLyricScroll(12, 10))
        assertFalse(shouldAnimateLyricScroll(10, 13))
        assertFalse(shouldAnimateLyricScroll(20, 3))
        assertEquals(LyricScrollMode.SNAP, lyricScrollMode(true, 10, 11))
        assertEquals(LyricScrollMode.SNAP, lyricScrollMode(true, 10, 30))
        assertEquals(LyricScrollMode.ANIMATE, lyricScrollMode(false, 10, 12))
        assertEquals(LyricScrollMode.CHANGE_WINDOW, lyricScrollMode(false, 10, 13))
    }

    @Test fun visibleLyricSeekUsesThePaddedReadingAnchor() {
        assertEquals(480f, lyricVisibleSeekTravel(480), .0001f)
        assertEquals(-120f, lyricVisibleSeekTravel(-120), .0001f)
        assertEquals(0f, lyricVisibleSeekTravel(0), .0001f)
    }

    @Test fun everyVisibleSeekUsesContinuousScrollWhileOffscreenSeekUsesLayeredHandoff() {
        assertEquals(LyricClickNavigation.SCROLL, lyricClickNavigation(targetVisible = true))
        assertEquals(LyricClickNavigation.LAYERED, lyricClickNavigation(targetVisible = false))
        assertEquals(LyricScrollMode.ANIMATE, lyricScrollMode(false, 8, 12, targetVisible = true))
        assertEquals(LyricScrollMode.SNAP, lyricScrollMode(true, 8, 12, targetVisible = true))
    }

    @Test fun progressTapKeepsAnimatedValueWhileDragFollowsFinger() {
        assertEquals(.35f, playerProgressVisualValue(.8f, false, .35f), .0001f)
        assertEquals(.8f, playerProgressVisualValue(.8f, true, .35f), .0001f)
    }

    @Test fun visibleLyricSeekCrossfadesCachedLayersWithoutDoubleOpacity() {
        assertEquals(1f, lyricSeekLayerAlpha(300f, 300f, outgoing = true, crossfade = true), .0001f)
        assertEquals(0f, lyricSeekLayerAlpha(300f, 300f, outgoing = false, crossfade = true), .0001f)
        assertEquals(.5f, lyricSeekLayerAlpha(150f, 300f, outgoing = true, crossfade = true), .0001f)
        assertEquals(.5f, lyricSeekLayerAlpha(150f, 300f, outgoing = false, crossfade = true), .0001f)
        assertEquals(0f, lyricSeekLayerAlpha(0f, 300f, outgoing = true, crossfade = true), .0001f)
        assertEquals(1f, lyricSeekLayerAlpha(0f, 300f, outgoing = false, crossfade = true), .0001f)
    }

    @Test fun kugouKrcParserReadsLineAndWordTiming() {
        val lines = parseKugouKrc("[1000,800]<0,400,0>你<400,400,0>好\n[2000,800]<0,800,0>世界")

        assertEquals(listOf(1_000L, 2_000L), lines.map { it.timeMs })
        assertEquals(listOf("你好", "世界"), lines.map { it.text })
    }

    @Test fun neteaseEapiEncryptionMatchesKnownVector() {
        assertEquals(
            "1c0fb45b24bf45f8006f40710ab99ab37d400d166411c5ae8292ee9e129fc9aab8805876d72c7ee21377d45c2ccdaf5bc7ae8d46329b2df19916c58144af157b743d4b511578106d0dc75e6df0f82ab3d7d258396e2f570deed4b04578127c84",
            NeteaseCrypto.encryptEapi("/api/login/cellphone", "{\"e_r\":true}"),
        )
    }

    @Test fun neteaseMobileEapiResponseCanBeDecrypted() {
        val encrypted = "4dc12a14eca8f30f33123e619c7b9c893b1ae1dd553e6d9cc22204a313ce169d"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        assertEquals("{\"code\":200,\"message\":\"ok\"}", NeteaseCrypto.decryptMobileEapi(encrypted))
    }

    @Test fun neteaseCookieMergeReplacesValuesAndDropsAttributes() {
        assertEquals(
            "MUSIC_U=new; __csrf=token",
            mergeCookies("MUSIC_U=old; Path=/; HttpOnly", "MUSIC_U=new; __csrf=token; Max-Age=60"),
        )
    }

    @Test fun neteaseMediaUrlsAreUpgradedToHttps() {
        assertEquals("https://p1.music.126.net/cover.jpg", "http://p1.music.126.net/cover.jpg".asNeteaseHttpsUrl())
        assertEquals("https://m10.music.126.net/song.mp3", "https://m10.music.126.net/song.mp3".asNeteaseHttpsUrl())
    }

    @Test fun automaticEndHonorsRepeatModeWhileNextStillAdvances() {
        assertNull(nextQueueIndex(2, 3, false, RepeatMode.OFF, true))
        assertEquals(0, nextQueueIndex(2, 3, false, RepeatMode.ALL, true))
        assertEquals(2, nextQueueIndex(2, 3, false, RepeatMode.ONE, true))
        assertEquals(0, nextQueueIndex(2, 3, false, RepeatMode.ONE, false))
    }

    @Test fun shuffledNextDoesNotRepeatCurrentAndHandlesSingleTrack() {
        repeat(20) { assertNotEquals(1, nextQueueIndex(1, 4, true, RepeatMode.ALL, false)) }
        assertEquals(0, nextQueueIndex(0, 1, true, RepeatMode.ALL, false))
        assertNull(nextQueueIndex(0, 0, true, RepeatMode.ALL, false))
    }

    @Test fun audioQualityFallbackNeverExceedsPreference() {
        assertEquals(
            listOf(AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.HIGHER, AudioQuality.STANDARD),
            AudioQuality.LOSSLESS.fallbackCandidates(),
        )
        assertEquals(AudioQuality.EXHIGH, AudioQuality.fromProvider("exhigh", 320_000))
        assertEquals(AudioQuality.HIGHER, AudioQuality.fromProvider("", 192_000))
    }

    @Test fun trackTransitionKeepsDisplayedQualityUntilNewSourceIsReady() {
        val track = testTrack("qq-next")
        val state = MusicOneUiState(
            qualityLoading = true,
            qualityChanging = true,
            activeQuality = AudioQuality.HI_RES,
            availableQualities = AudioQuality.entries,
            playbackMessage = "旧请求失败",
        ).forTrackTransition(track, listOf(track), playWhenReady = true)

        assertEquals(track, state.currentTrack)
        assertTrue(state.isPlaying)
        assertFalse(state.qualityLoading)
        assertFalse(state.qualityChanging)
        assertEquals(AudioQuality.HI_RES, state.activeQuality)
        assertTrue(state.availableQualities.isEmpty())
        assertNull(state.playbackMessage)
        assertEquals(LyricLoadState.LOADING, state.lyricLoadState)
        assertEquals(TrackTransitionDirection.NEXT, state.trackTransitionDirection)
        assertFalse(state.forTrackTransition(track, playWhenReady = false).isPlaying)

        val readyTrack = track.copy(lyrics = listOf(TimedLyric(0L, "已有歌词")))
        assertEquals(
            LyricLoadState.READY,
            state.forTrackTransition(readyTrack, playWhenReady = true).lyricLoadState,
        )
    }

    @Test fun lyricTrackTransitionMirrorsForPreviousAndNext() {
        assertEquals(800, lyricTrackEnterOffset(800, TrackTransitionDirection.NEXT))
        assertEquals(-800, lyricTrackExitOffset(800, TrackTransitionDirection.NEXT))
        assertEquals(-800, lyricTrackEnterOffset(800, TrackTransitionDirection.PREVIOUS))
        assertEquals(800, lyricTrackExitOffset(800, TrackTransitionDirection.PREVIOUS))
    }

    @Test fun uncachedRapidLyricsWaitForContentBeforeEntering() {
        assertEquals(
            LyricLoadState.LOADING,
            displayedLyricLoadState(true, false, LyricLoadState.UNAVAILABLE),
        )
        assertEquals(
            LyricLoadState.READY,
            displayedLyricLoadState(true, true, LyricLoadState.LOADING),
        )
        assertEquals(
            LyricLoadState.UNAVAILABLE,
            displayedLyricLoadState(false, false, LyricLoadState.UNAVAILABLE),
        )
    }

    @Test fun qqTrackWaitsForPlaybackResolutionBeforeLyricsTransition() {
        val lyrics = listOf(TimedLyric(0L, "已有歌词"))
        val qq = testTrack("qq-pending").copy(source = MusicSource.QQ, lyrics = lyrics)
        val netease = qq.copy(source = MusicSource.NETEASE)

        assertTrue(qq.forPendingPlaybackPresentation().lyrics.isEmpty())
        assertEquals(lyrics, netease.forPendingPlaybackPresentation().lyrics)
    }

    @Test fun playbackRequestMustMatchTrackAndGeneration() {
        assertTrue(isCurrentPlaybackRequest(4, 4, "qq-song", "qq-song"))
        assertFalse(isCurrentPlaybackRequest(3, 4, "qq-song", "qq-song"))
        assertFalse(isCurrentPlaybackRequest(4, 4, "qq-old", "qq-song"))
    }

    private fun testTrack(id: String) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = "测试歌曲",
        artists = "测试歌手",
        album = "测试专辑",
        durationMs = 180_000,
        artworkStart = 0,
        artworkEnd = 0,
        artworkMark = "测",
        previewUrl = "",
    )

}
