package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouAdaptationTest {
    private fun track(id: String) = MusicTrack("kugou-$id", MusicSource.KUGOU, id, "歌手", "", 90_000,
        0, 0, "", "", artworkUrl = "https://cdn.kugou.com/$id.jpg")

    @Test fun krcUsesRelativeWordTimesAndPreservesSpacesAndPunctuation() {
        val lyric = parseKugouKrc("[10000,2000]<100,500,0>Hello <600,1400,0>世界！\r\n").single()
        assertEquals("Hello 世界！", lyric.text)
        assertEquals(listOf(LyricWord(10_100, 10_600, "Hello "), LyricWord(10_600, 12_000, "世界！")), lyric.words)
        assertTrue(hasReusableLyrics(track("a"), listOf(lyric)))
        assertFalse(hasReusableLyrics(track("a"), listOf(lyric.copy(parserVersion = 0))))
    }

    @Test fun malformedKrcDoesNotDiscardFollowingRows() {
        val lyrics = parseKugouKrc("[0,1000]<9223372036854775807,100,0>坏\n[2000,200]<0,200,0>好")
        assertEquals("好", lyrics.single().text)
        assertEquals(2_000L, lyrics.single().words.single().startMs)
    }

    @Test fun lyricSearchCandidateSelectsKrcBeforeLrcWhenAvailable() {
        assertEquals(listOf("krc", "lrc"), kugouLyricFormats(JSONObject("""{"krctype":1,"contenttype":0}""")))
        assertEquals(listOf("lrc", "krc"), kugouLyricFormats(JSONObject("""{"krctype":0,"contenttype":1}""")))
    }

    @Test fun cloudHashLevelAndRelateGoodsKeepTheirActualQualities() {
        val item = JSONObject("""{"ID":999,"hash":"sq","level":5,"timelen":90000,
            "relate_goods":[{"hash":"base","level":2},{"hash":"hq","level":4}],"name":"歌手 - 歌曲"}""")
        val result = item.toKugouTrack(true)
        assertEquals("kugou-sq", result.id)
        assertEquals("sq", result.qualityIds[AudioQuality.LOSSLESS])
        assertEquals("base", result.qualityIds[AudioQuality.STANDARD])
        assertEquals("hq", result.qualityIds[AudioQuality.EXHIGH])
        assertEquals("", result.catalogId)
        assertEquals(90_000L, result.durationMs)
        val onlyLossless = result.copy(qualityIds = mapOf(AudioQuality.LOSSLESS to "sq"))
        assertNull(onlyLossless.kugouHashFor(AudioQuality.STANDARD))
    }

    @Test fun favoriteArtworkFollowsNewLikesAndRollsBackToSnapshot() {
        val older = track("old")
        val newer = track("new")
        val cloud = MusicPlaylist("kugou-cloudlist:1", MusicSource.KUGOU, "我喜欢", "", "", 1,
            0, 0, "", listOf(older), artworkUrl = older.artworkUrl)
        val snapshot = KugouFavoriteArtwork()
        snapshot.record(cloud)
        val liked = snapshot.present(mapOf(newer.id to (newer to true)))!!
        assertEquals(newer.artworkUrl, liked.artworkUrl)
        assertEquals(2, liked.count)
        snapshot.record(liked)
        assertEquals(2, snapshot.present(mapOf(newer.id to (newer to true)))!!.count)
        snapshot.record(cloud)
        assertEquals(older.artworkUrl, snapshot.present(emptyMap())!!.artworkUrl)
        assertNull(snapshot.present(mapOf(older.id to (older to false)))!!.artworkUrl)
        snapshot.clear()
        assertNull(snapshot.present(emptyMap()))
    }

    @Test fun error34351ShowsRecoveryChallengeInsteadOfSmsCodeFailure() {
        assertNull(kugouAccountRecoveryChallengeForError(34_350, "device=example"))
        val challenge = kugouAccountRecoveryChallengeForError(34_351, "device=example")!!
        assertEquals(PlatformSecurityVerificationKind.WEB, challenge.kind)
        assertEquals("https://h5.kugou.com/recover/v-e063c2ff/index.html?type=mobile", challenge.url)
        assertEquals("device=example", challenge.credentialSeed)
        assertTrue(challenge.message.contains("注销"))
        assertTrue(challenge.isKugouAccountRecovery())
    }

    @Test fun completingRecoveryClearsOldCaptchaState() {
        val state = PlatformSettingsUiState(
            captcha = "123456", captchaSent = true, resendSeconds = 45,
            working = true, securityVerification = kugouAccountRecoveryChallenge("device=example").toUiState(),
        ).afterKugouAccountRecovery()
        assertEquals("", state.captcha)
        assertFalse(state.captchaSent)
        assertEquals(0, state.resendSeconds)
        assertFalse(state.working)
        assertNull(state.securityVerification)
        assertTrue(state.message.orEmpty().contains("重新获取"))
    }

    @Test fun undoRemovalKeepsExistingCloudOrder() {
        val latest = track("latest")
        val older = track("older")
        val cloud = MusicPlaylist("kugou-cloudlist:1", MusicSource.KUGOU, "我喜欢", "", "", 2,
            0, 0, "", listOf(latest, older), artworkUrl = latest.artworkUrl)
        val snapshot = KugouFavoriteArtwork()
        snapshot.record(cloud)
        snapshot.present(mapOf(older.id to (older to false)))
        val restored = snapshot.present(mapOf(older.id to (older to true)))!!
        assertEquals(cloud.tracks, restored.tracks)
        assertEquals(latest.artworkUrl, restored.artworkUrl)
    }
}
