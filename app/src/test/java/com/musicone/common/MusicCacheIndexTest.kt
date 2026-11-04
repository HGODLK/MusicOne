package com.musicone

import org.junit.Assert.*
import org.junit.Test

class MusicCacheIndexTest {
    private var saved = "{}"
    private fun index() = MusicCacheIndex({ saved }, { saved = it })
    private val track = MusicTrack("qq-song", MusicSource.QQ, "歌曲", "歌手", "", 1, 0, 0, "歌", "")

    @Test fun sameSongAndVariantReusePhysicalBytesAcrossAccounts() {
        val index = index()
        val old = "QQ:one|qq-song|/song.flac"
        val next = "QQ:two|qq-song|/song.flac"
        index.importResource(old, track)
        index.trust(old, true)
        assertEquals(old, index.register(next, track) { it == old })
        assertEquals(1, index.resources(track.cacheIdentity()).size)
    }

    @Test fun unverifiedOrTrialLegacyBytesAreNotAliasedAcrossAccounts() {
        val index = index()
        val old = "QQ:one|qq-song|/song.flac"
        val next = "QQ:two|qq-song|/song.flac"
        index.importResource(old, track)
        assertEquals(next, index.register(next, track) { it == old })
        index.trust(old, true)
        assertEquals(old, index.register(next, track) { it == old })
    }

    @Test fun differentQualityAndDifferentProviderNeverShareByteRanges() {
        val index = index()
        val old = "QQ:one|qq-song|/song.flac"
        val otherQuality = "QQ:one|qq-song|/song.mp3"
        index.importResource(old, track)
        assertEquals(otherQuality, index.register(otherQuality, track) { true })
        val otherProvider = track.copy(source = MusicSource.NETEASE)
        val providerKey = "NETEASE:one|qq-song|/song.flac"
        assertEquals(providerKey, index.register(providerKey, otherProvider) { true })
    }

    @Test fun biliAccountAndCdnChangesPreserveVariantButPartsAndCodecsStaySeparate() {
        assertEquals(cacheResourceVariant("bili-mid-123-BV1abc-456-track-30280-mp4a.40.2"),
            cacheResourceVariant("bili-mid-789-BV1abc-456-track-30280-mp4a.40.2"))
        assertNotEquals(cacheResourceVariant("bili-guest-BV1abc-456-track-30280-mp4a.40.2"),
            cacheResourceVariant("bili-guest-BV1abc-457-track-30280-mp4a.40.2"))
    }

    @Test fun fifoSurvivesRestartAndRepeatedRegistrationDoesNotRefreshAge() {
        val index = index()
        val key = "QQ:one|qq-song|/song.flac"
        index.importResource(key, track)
        index.markWritten(key)
        val first = index.resource(key)!!.entered
        index.register(key, track) { true }
        index.markWritten(key)
        assertEquals(first, index().resource(key)!!.entered)
        index.forget(key)
        index.markWritten(key)
        assertTrue(index.resource(key)!!.entered > first)
    }

    @Test fun orderedRefreshRetainsKnownMembershipOrderAndReAddGetsNewOrder() {
        val index = index()
        index.replacePrimary("QQ:one", listOf("new", "old"), ordered = true)
        val initial = index.primaryOrder("new")!!
        assertTrue(initial > index.primaryOrder("old")!!)
        index.replacePrimary("QQ:one", listOf("old", "new"), ordered = true)
        assertEquals(initial, index.primaryOrder("new"))
        index.changePrimary("QQ:one", listOf("old"), false)
        index.changePrimary("QQ:one", listOf("old"), true)
        assertTrue(index.primaryOrder("old")!! > initial)
    }

    @Test fun unorderedSnapshotDoesNotInventHistoryAndOrderedDirectoryCanSeedIt() {
        val index = index()
        index.replacePrimary("QQ:one", listOf("one", "two"), ordered = false)
        assertEquals(0L, index.primaryOrder("one"))
        index.replacePrimary("QQ:one", listOf("two", "one"), ordered = true)
        assertTrue(index.primaryOrder("two")!! > index.primaryOrder("one")!!)
    }

    @Test fun remoteNewMembersOutrankHistoricalMembersAndHistorySeedingDoesNotReverseThem() {
        val index = index()
        index.replacePrimary("QQ:one", listOf("old"), ordered = false)
        index.replacePrimary("QQ:one", listOf("recent", "old"), ordered = false)
        val recent = index.primaryOrder("recent")!!
        assertTrue(recent > index.primaryOrder("old")!!)
        index.replacePrimary("QQ:one", listOf("recent", "old"), ordered = true)
        assertEquals(recent, index.primaryOrder("recent"))
        assertTrue(recent > index.primaryOrder("old")!!)
    }

    @Test fun removingOnePrimaryReferenceDoesNotDropAnotherAndRestartRetainsMembership() {
        val index = index()
        index.changePrimary("QQ:one", listOf("song"), true)
        index.replaceLibraryPrimary(listOf("song" to 2L))
        index.changePrimary("QQ:one", listOf("song"), false)
        assertTrue(index().isPrimary("song"))
        index.replaceLibraryPrimary(emptyList())
        assertFalse(index().isPrimary("song"))
    }

    @Test fun identicalFavoriteSnapshotsAndRepeatedChangesDoNotSignalAnotherRefresh() {
        val index = index()
        assertTrue(index.replacePrimary("QQ:one", listOf("one", "two"), ordered = false))
        val first = saved
        assertFalse(index.replacePrimary("QQ:one", listOf("two", "one"), ordered = false))
        assertFalse(index.changePrimary("QQ:one", listOf("one"), added = true))
        assertFalse(index.changePrimary("QQ:one", listOf("absent"), added = false))
        assertEquals(first, saved)
        assertTrue(index.changePrimary("QQ:one", listOf("one"), added = false))
    }

    @Test fun orderedHistorySeedingSignalsOnceAndPreservesOriginalOrderAfterwards() {
        val index = index()
        assertTrue(index.replacePrimary("QQ:one", listOf("one", "two"), ordered = false))
        assertTrue(index.replacePrimary("QQ:one", listOf("two", "one"), ordered = true))
        val first = saved
        assertFalse(index.replacePrimary("QQ:one", listOf("one", "two"), ordered = true))
        assertEquals(first, saved)
        assertTrue(index.primaryOrder("two")!! > index.primaryOrder("one")!!)
    }

    @Test fun libraryRefreshSignalsOnlyChangedMembershipOrOrder() {
        val index = index()
        assertTrue(index.replaceLibraryPrimary(listOf("one" to 2L, "two" to 1L)))
        val first = saved
        assertFalse(index.replaceLibraryPrimary(listOf("two" to 1L, "one" to 2L)))
        assertEquals(first, saved)
        assertTrue(index.replaceLibraryPrimary(listOf("one" to 3L, "two" to 1L)))
        assertTrue(index.replaceLibraryPrimary(emptyList()))
        assertFalse(index.replaceLibraryPrimary(emptyList()))
    }

    @Test fun dashDownloadSelectsAudioUrlRatherThanManifestAndDecodesQuery() {
        assertEquals("https://audio.test/song?x=1&y=2", dashAudioBaseUrl("<MPD><BaseURL serviceLocation=\"a\">https://audio.test/song?x=1&amp;y=2</BaseURL></MPD>"))
        assertNull(dashAudioBaseUrl("<MPD/>"))
        assertNull(dashAudioBaseUrl("<BaseURL>file:///music.flac</BaseURL>"))
    }
}
