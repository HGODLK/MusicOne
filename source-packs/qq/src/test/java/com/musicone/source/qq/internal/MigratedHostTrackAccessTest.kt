package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostTrackAccessTest {
    @Test fun numericQqSongWithoutMidRemainsEligibleForMetadataRecovery() {
        val track = JSONObject().put("songid", 12345).put("songname", "可补全歌曲")
            .put("pay", JSONObject().put("pay_play", 0)).toQqTrack()
        assertEquals("qq-12345", track.id)
        assertEquals("", track.qqPlaybackMid())
        assertTrue(track.playable)
    }

    @Test fun qqMetadataEnrichmentRestoresMissingArtworkAndQualityIds() {
        val original = testQqTrack("qq-song").copy(
            durationMs = 0L,
            artworkUrl = null,
            qualityIds = mapOf(AudioQuality.LOSSLESS to "stale-media-mid"),
        )
        val detail = testQqTrack("qq-song").copy(
            durationMs = 247_000L,
            album = "动画主题曲",
            artworkUrl = "https://y.gtimg.cn/cover.jpg",
            mediaId = "media-mid",
            qualityIds = mapOf(AudioQuality.STANDARD to "media-mid", AudioQuality.EXHIGH to "media-mid"),
        )

        val enriched = original.mergeQqTrackMetadata(detail)

        assertTrue(original.needsQqMetadataEnrichment())
        assertEquals(detail.artworkUrl, enriched.artworkUrl)
        assertEquals(detail.album, enriched.album)
        assertEquals(detail.durationMs, enriched.durationMs)
        assertEquals(detail.qualityIds, enriched.qualityIds)
        assertFalse(AudioQuality.LOSSLESS in enriched.qualityIds)
    }

    @Test fun qqMagazineTrackCarriesVerifiedMetadataIntoPlayback() {
        val magazineTrack = testQqTrack("qq-73456789").copy(
            durationMs = 0L,
            catalogId = "73456789",

        ).withQqSongMid("")
        val ready = magazineTrack.mergeQqTrackMetadata(
            testQqTrack("qq-007AbcdEF12345").copy(
                durationMs = 247_000L,

                mediaId = "002QI8jU0AlNad",
            ).withQqSongMid("007AbcdEF12345"),
        )

        assertTrue(magazineTrack.requiresQqPlaybackMetadata())
        assertFalse(ready.requiresQqPlaybackMetadata())
        assertEquals(247_000L, ready.durationMs)
        assertEquals("007AbcdEF12345", ready.qqPlaybackMid())
    }

    @Test fun qqNumericFeedSongKeepsSongMidSeparateFromMediaMid() {
        val cardTrack = testQqTrack("qq-73456789").copy(
            catalogId = "73456789",
            mediaId = "",
        )
        val enriched = cardTrack.mergeQqTrackMetadata(
            testQqTrack("qq-007AbcdEF12345").copy(mediaId = "002QI8jU0AlNad"),
        )

        assertEquals("", cardTrack.qqPlaybackMid())
        assertEquals("007AbcdEF12345", enriched.qqPlaybackMid())
        assertEquals("qq-73456789", enriched.id)
        assertEquals("M500002QI8jU0AlNad.mp3", qqPlaybackFilename("M500", "mp3", enriched.qqPlaybackMid(), enriched.mediaId))
        val canonical = enriched.withCanonicalQqIdentity()
        assertEquals("qq-007AbcdEF12345", canonical.id)
        assertEquals("73456789", canonical.catalogId)
        val restored = enriched.toStoredTrackJson().toStoredTrack()!!
        assertEquals("007AbcdEF12345", restored.qqPlaybackMid())
        assertEquals("002QI8jU0AlNad", restored.mediaId)
    }

    @Test fun qqMetadataFailureDropsStaleAdvancedQuality() {
        val stale = testQqTrack("qq-song").copy(
            mediaId = "media-mid",
            qualityIds = mapOf(AudioQuality.LOSSLESS to "stale-media-mid"),
        )

        val safe = stale.withConservativeQqQuality()

        assertEquals(mapOf(AudioQuality.STANDARD to "media-mid"), safe.qualityIds)
    }

    @Test fun qqShortTrackBorrowsArtworkOnlyFromSameTitleAndArtist() {
        val short = testQqTrack("qq-short")
        val wrongArtist = testQqTrack("qq-wrong").copy(
            artists = "其他歌手",
            artworkUrl = "https://example.com/wrong.jpg",
        )
        val official = testQqTrack("qq-official").copy(
            durationMs = 247_000L,
            album = "忘记了也没关系",
            artworkUrl = "https://example.com/official.jpg",
        )

        val resolved = short.withQqArtworkFallback(listOf(wrongArtist, official))

        assertEquals(official.artworkUrl, resolved.artworkUrl)
        assertEquals(official.album, resolved.album)
        assertTrue(resolved.qualityIds.isEmpty())
    }

    @Test fun qqShortTrackBorrowsArtworkFromReleaseWithVersionSuffix() {
        val short = testQqTrack("qq-002TldEL1sXYBS").copy(
            title = "私、案山子。 (我、稻草人。)",
            artists = "あめのむらくもＰ、GUMI",
            artworkUrl = null,
        )
        val wrongArtist = short.copy(
            id = "qq-wrong",
            artists = "其他歌手",
            artworkUrl = "https://example.com/wrong.jpg",
        )
        val release = short.copy(
            id = "qq-003oRkXc39GfjC",
            title = "私、案山子。 (feat. GUMI)",
            artists = "あめのむらくもP;GUMI",
            album = "底に花",
            artworkUrl = "https://example.com/release.jpg",
        )

        val resolved = short.withQqArtworkFallback(listOf(wrongArtist, release))

        assertEquals(release.artworkUrl, resolved.artworkUrl)
        assertEquals("底に花", resolved.album)
        assertEquals(1, qqArtworkTitleMatchScore(short.title, release.title))
    }

    @Test fun qqPlaceholderColorsAreStableAndDependOnDisplayName() {
        assertEquals(qqArtworkColors("私、案山子。"), qqArtworkColors("  私、案山子。  "))
        assertTrue(qqArtworkColors("私、案山子。") != qqArtworkColors("夜航星"))
    }

    @Test fun qqPreviewRedirectIsRecognizedAsTrial() {
        assertTrue("C400preview.m4a?vkey=token&src=C400original.m4a".isQqTrialUrl())
        assertFalse("M500original.mp3?vkey=token&guid=123".isQqTrialUrl())
    }

    @Test fun qqPlaybackFilenameUsesMediaMidWithoutDuplicatingSongMid() {
        assertEquals("M500media-mid.mp3", qqPlaybackFilename("M500", "mp3", "song-mid", "media-mid"))
    }

    @Test fun qqPlaybackFilenameDuplicatesSongMidOnlyWhenMediaMidIsMissing() {
        assertEquals("M500song-midsong-mid.mp3", qqPlaybackFilename("M500", "mp3", "song-mid", ""))
    }

    @Test fun qqPlaybackFilenamesStrictlyUsesMediaMidWhenAvailable() {
        val filenames = qqPlaybackFilenames("M500", "mp3", "song-mid", "media-mid")
        assertEquals(listOf("M500media-mid.mp3", "M500media-midmedia-mid.mp3"), filenames)
        assertFalse(filenames.any { it.contains("song-mid") })
    }

    @Test fun qqPlaybackFilenamesFallsBackToSongMidWhenMediaMidIsMissing() {
        val filenames = qqPlaybackFilenames("M500", "mp3", "song-mid", "")
        assertEquals(listOf("M500song-midsong-mid.mp3", "M500song-mid.mp3"), filenames)
    }

    @Test fun qqQualityBatchKeepsOnlyFullPlaybackUrls() {
        assertEquals(
            listOf(AudioQuality.STANDARD, AudioQuality.LOSSLESS),
            qqAvailableQualitiesFromUrls(
                listOf(
                    listOf("M500song.mp3?vkey=standard"),
                    listOf("C400trial.m4a?vkey=trial&src=original.m4a"),
                    listOf("", "F000song.flac?vkey=lossless"),
                ),
            ),
        )
    }

    @Test fun qqPlaybackQualityUsesReturnedFilenameInsteadOfRequestedLevel() {
        assertEquals(AudioQuality.LOSSLESS, qqPlaybackQuality("https://cdn.example/F000song.flac?vkey=1"))
        assertEquals(AudioQuality.HI_RES, qqPlaybackQuality("RS01song.flac?vkey=1"))
        assertEquals(AudioQuality.DOLBY, qqPlaybackQuality("D004song.mp4?vkey=1"))
        assertNull(qqPlaybackQuality("C400trial.m4a?vkey=1&src=RS01song.flac"))
    }

    @Test fun qqTrackFileSizesExposeOnlyRealAdvancedQualities() {
        assertEquals(
            setOf(AudioQuality.STANDARD, AudioQuality.LOSSLESS, AudioQuality.DOLBY),
            qqQualityIdsFromFileSizes(
                "media",
                mapOf(
                    AudioQuality.STANDARD to 1L,
                    AudioQuality.LOSSLESS to 2L,
                    AudioQuality.DOLBY to 3L,
                ),
            ).keys,
        )
    }

    @Test fun qqMonthlyTrackShowsVipForAccountWithoutEntitlement() {
        val access = qqTrackAccess(requiresPayment = true, monthly = true, price = 200, trialAvailable = true)

        assertEquals(MusicAccessBadge.VIP, access.badge)
        assertTrue(access.playable)
        assertTrue(access.trialAvailable)
    }

    @Test fun qqMonthlyTrackDoesNotShowVipForEntitledAccount() {
        val access = qqTrackAccess(
            requiresPayment = true,
            monthly = true,
            price = 200,
            trialAvailable = true,
            hasVipAccess = true,
        )

        assertNull(access.badge)
        assertTrue(access.playable)
        assertFalse(access.trialAvailable)
    }

    @Test fun qqStandalonePriceShowsPaidButDoesNotProvePlaybackDenial() {
        val access = qqTrackAccess(requiresPayment = true, monthly = false, price = 200, trialAvailable = false)

        assertEquals(MusicAccessBadge.PAID, access.badge)
        assertTrue(access.playable)
    }

    @Test fun qqVipEntitlementOnlyRemovesVipMarker() {
        val vipTrack = MusicTrack(
            id = "qq-vip",
            source = MusicSource.QQ,
            title = "会员歌曲",
            artists = "歌手",
            album = "专辑",
            durationMs = 180_000L,
            artworkStart = 0L,
            artworkEnd = 0L,
            artworkMark = "会",
            previewUrl = "",
            accessBadge = MusicAccessBadge.VIP,
            trialAvailable = true,
            playable = true,
        )
        val paidTrack = vipTrack.copy(id = "qq-paid", accessBadge = MusicAccessBadge.PAID, playable = false)

        val entitledVip = vipTrack.withQqVipEntitlement(hasVipAccess = true)

        assertNull(entitledVip.accessBadge)
        assertFalse(entitledVip.trialAvailable)
        assertTrue(entitledVip.playable)
        assertEquals(MusicAccessBadge.PAID, paidTrack.withQqVipEntitlement(hasVipAccess = true).accessBadge)
        assertFalse(paidTrack.withQqVipEntitlement(hasVipAccess = true).playable)
    }

    @Test fun qqHotSwitchPrefersAFullCompatibilityTicketOverATrialAndroidTicket() {
        val trial = "https://cdn.example/F000song.flac?vkey=trial&src=preview.flac"
        val full = "https://cdn.example/F000song.flac?vkey=full"
        assertEquals(full, preferredQqPlaybackUrl(trial, full))
        assertEquals(full, preferredQqPlaybackUrl(full, null))
        assertEquals(trial, preferredQqPlaybackUrl(trial, null))
    }

    @Test fun qqSearchRootFileSizesDoNotInventFlac() {
        val qualities = qqQualityIdsFromFileSizes(
            "001G4qIk2IHLFT",
            mapOf(
                AudioQuality.STANDARD to 3_868_914L,
                AudioQuality.EXHIGH to 9_671_859L,
                AudioQuality.LOSSLESS to 0L,
            ),
        )

        assertEquals(setOf(AudioQuality.STANDARD, AudioQuality.EXHIGH), qualities.keys)
        assertFalse(AudioQuality.LOSSLESS in qualities)
    }

    @Test fun qqQualityBatchRejectsAReplacementUrlForAnotherLevel() {
        assertEquals(
            listOf(AudioQuality.EXHIGH),
            qqAvailableQualitiesFromExpectedUrls(
                listOf(AudioQuality.LOSSLESS, AudioQuality.EXHIGH),
                listOf(
                    listOf("M500song.mp3?vkey=1"),
                    listOf("M800song.mp3?vkey=2"),
                ),
            ),
        )
    }

    @Test fun qqQualityBatchUsesReturnedFilenamesWhenRowsAreMissingOrReordered() {
        assertEquals(
            listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH),
            qqAvailableQualitiesFromReturnedUrls(
                listOf(AudioQuality.STANDARD, AudioQuality.LOSSLESS, AudioQuality.EXHIGH),
                listOf(
                    listOf("M800song.mp3?vkey=high"),
                    listOf("M500song.mp3?vkey=standard"),
                ),
            ),
        )
    }

    private fun testQqTrack(id: String) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = "忘记了也没关系",
        artists = "DOUDOU",
        album = "",
        durationMs = 83_000L,
        artworkStart = 0xFF112233,
        artworkEnd = 0xFF445566,
        artworkMark = "忘",
        previewUrl = "",
    )
    @Test fun protocolKeepsIndependentHiResAndDolbyQualityLevels() {
        assertEquals(
            listOf(
                AudioQuality.STANDARD,
                AudioQuality.EXHIGH,
                AudioQuality.LOSSLESS,
                AudioQuality.HI_RES,
                AudioQuality.DOLBY,
            ),
            QQ_AUDIO_QUALITIES,
        )
    }
}
