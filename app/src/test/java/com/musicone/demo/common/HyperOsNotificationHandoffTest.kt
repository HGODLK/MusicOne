package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class HyperOsNotificationHandoffTest {
    private data class Card(val track: String, val playing: Boolean = true,
        val lyric: String? = null, val cover: String? = null, val nativeCover: String? = null)
    private data class Overlay(val track: String, val lyric: String, val cover: String)
    private class Host {
        val pending = mutableListOf<Runnable>()
        val published = mutableListOf<Card>()
        val handoff = HyperOsNotificationHandoff<Card, Overlay>(
            enqueue = { pending += it }, remove = { pending -= it },
            decorate = { media, overlay ->
                check(media.track == overlay.track)
                media.copy(lyric = overlay.lyric, cover = overlay.cover)
            }, equivalent = { a, b -> a == b }, publish = { published += it })
        fun flush() { val tasks = pending.toList(); pending.clear(); tasks.forEach(Runnable::run) }
        fun start(): Card {
            handoff.mediaCreated("a", Card("a"))
            handoff.overlayUpdated("a", Overlay("a", "原文", "封面甲"))
            flush()
            return published.last()
        }
    }

    @Test fun queuedLyricAndPauseAreIncludedInTheSameNativeNotificationWithoutASecondCallback() {
        val host = Host()
        host.start()
        host.handoff.overlayUpdated("a", Overlay("a", "暂停位置原文", "封面甲"))
        val result = host.handoff.mediaCreated("a", Card("a", playing = false))
        assertEquals(Card("a", false, "暂停位置原文", "封面甲"), result)
        assertTrue(host.pending.isEmpty())
        host.handoff.mediaUpdated("a", Card("a", playing = false))
        assertEquals(1, host.pending.size)
        host.flush()
        assertEquals(1, host.published.size)
    }

    @Test fun aNewMediaItemKeepsTheWholePreviousCardUntilItsOwnCoverAndLyricsArrive() {
        val host = Host()
        val previous = host.start()
        assertSame(previous, host.handoff.mediaCreated("b", Card("b")))
        host.flush()
        assertEquals(listOf(previous), host.published)
        host.handoff.overlayUpdated("b", Overlay("b", "乙原文", "封面乙"))
        host.flush()
        assertEquals(Card("b", true, "乙原文", "封面乙"), host.published.last())
        assertEquals(2, host.published.size)
    }

    @Test fun lyricsArrivingBeforeMediaNeverDecorateThePreviousTrack() {
        val host = Host()
        val previous = host.start()
        host.handoff.overlayUpdated("b", Overlay("b", "乙原文", "封面乙"))
        host.flush()
        assertEquals(listOf(previous), host.published)
        val result = host.handoff.mediaCreated("b", Card("b"))
        assertEquals(Card("b", true, "乙原文", "封面乙"), result)
        assertTrue(host.pending.isEmpty())
        host.flush()
        assertEquals(1, host.published.size)
    }

    @Test fun rapidTrackChangesCommitOnlyTheLatestMatchingPair() {
        val host = Host()
        host.start()
        host.handoff.mediaCreated("b", Card("b"))
        host.handoff.overlayUpdated("b", Overlay("b", "乙原文", "封面乙"))
        host.handoff.mediaCreated("c", Card("c"))
        host.handoff.overlayUpdated("c", Overlay("c", "丙原文", "封面丙"))
        assertEquals(1, host.pending.size)
        host.flush()
        assertEquals(2, host.published.size)
        assertEquals(Card("c", true, "丙原文", "封面丙"), host.published.last())
    }

    @Test fun duplicateArtworkAndContentUpdatesDoNotRepublishTheSameFocusCard() {
        val host = Host()
        host.start()
        host.handoff.mediaUpdated("a", Card("a"))
        host.handoff.overlayUpdated("a", Overlay("a", "原文", "封面甲"))
        host.flush()
        assertEquals(1, host.published.size)
    }

    @Test fun pauseArrivingBeforeTheLyricSnapshotDoesNotRepublishUnchangedContent() {
        val host = Host()
        host.start()
        val paused = host.handoff.mediaCreated("a", Card("a", false))
        assertEquals(Card("a", false, "原文", "封面甲"), paused)
        host.handoff.overlayUpdated("a", Overlay("a", "原文", "封面甲"))
        host.flush()
        assertEquals(1, host.published.size)
    }

    @Test fun anActualNativeMediaArtworkUpdateIsPreservedEvenWhenTheFocusCoverIsUnchanged() {
        val host = Host()
        host.start()
        val updated = Card("a", nativeCover = "媒体封面甲")
        host.handoff.mediaUpdated("a", updated)
        host.flush()
        assertEquals(2, host.published.size)
        assertEquals("媒体封面甲", host.published.last().nativeCover)
        assertEquals("封面甲", host.published.last().cover)
        host.handoff.mediaUpdated("a", updated)
        host.flush()
        assertEquals(2, host.published.size)
    }

    @Test fun disablingLyricsCancelsPendingFocusAndRestoresTheLatestNativeMediaCard() {
        val host = Host()
        host.start()
        host.handoff.mediaCreated("b", Card("b"))
        host.handoff.overlayUpdated("b", Overlay("b", "乙原文", "封面乙"))
        host.handoff.overlayUpdated(null, null)
        assertTrue(host.pending.isEmpty())
        assertEquals(Card("b"), host.published.last())
        host.flush()
        assertEquals(2, host.published.size)
    }

    @Test fun closingTheServiceDiscardsQueuedUpdatesAndLateArtworkCallbacks() {
        val host = Host()
        host.start()
        host.handoff.mediaCreated("b", Card("b"))
        host.handoff.close()
        host.handoff.overlayUpdated("b", Overlay("b", "乙原文", "封面乙"))
        host.handoff.mediaUpdated("b", Card("b"))
        host.flush()
        assertTrue(host.pending.isEmpty())
        assertEquals(1, host.published.size)
    }

    @Test fun ordinaryMediaNotificationsAreNotDelayedWhenLyricsAreDisabled() {
        val host = Host()
        val initial = Card("a")
        assertSame(initial, host.handoff.mediaCreated("a", initial))
        host.handoff.mediaUpdated("a", Card("a", false))
        assertEquals(listOf(Card("a", false)), host.published)
        assertTrue(host.pending.isEmpty())
    }
}
