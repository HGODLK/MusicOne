package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistCardTransitionTest {
    @Test fun duplicatePlaylistUsesClickedSourceForEnteringReturningAndInformation() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val cards = PlaylistCardTransition(scope)
        val motion = PageMotion(scope, false, 320, 280)
        val navigation = RecommendationNavigation(androidx.compose.runtime.mutableStateOf(null), motion, cards)
        val playlist = MusicPlaylist("qq-42", MusicSource.QQ, "歌单", "", "", 0, 0L, 0L, "歌", emptyList())
        val libraryKey = "qq-my-library:true:${playlist.id}"
        val recentKey = "qq-my-recent:${playlist.id}"
        val libraryAnchor = MotionAnchor(Rect(20f, 100f, 180f, 260f))
        val recentAnchor = MotionAnchor(Rect(200f, 400f, 360f, 560f))
        val target = MotionAnchor(Rect(0f, 0f, 400f, 400f))
        motion.updateHost(Rect(0f, 0f, 800f, 800f))
        motion.sources[libraryKey] = libraryAnchor
        motion.sources[recentKey] = recentAnchor
        motion.waitForTarget = false
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                yield()
            }
        }
        // 两处相同歌单分别点击，均应独立选择来源并返回同一个入口。
        for ((clicked, other) in listOf(libraryKey to recentKey, recentKey to libraryKey)) {
            cards.open(clicked, null, {}) {
                navigation.open(playlist)
                motion.targets[clicked] = target
            }
            frames(12)
            assertEquals(clicked, motion.coverKey)
            assertEquals(motion.sources[clicked], motion.sourceSnapshot[motion.coverKey])
            assertEquals(0f, cards.alphaFor(clicked))
            assertEquals(1f, cards.alphaFor(other))
            frames(30)
            navigation.close()
            frames(2)
            assertEquals(motion.sources[clicked], motion.sourceSnapshot[motion.coverKey])
            assertEquals(1f, cards.alphaFor(other))
            frames(25)
            assertEquals(MotionPhase.HIDDEN, motion.phase)
            assertTrue(cards.alphaFor(clicked) < 1f)
            assertEquals(1f, cards.alphaFor(other))
            frames(25)
            assertEquals(1f, cards.alphaFor(clicked))
            assertNull(cards.activeSourceKey)
        }
        coroutineContext.cancelChildren()
    }

    @Test fun scrollAndFadeFinishBeforeNavigationAndRestoreOnlyAfterLanding() = runBlocking {
        val clock = BroadcastFrameClock()
        val cards = PlaylistCardTransition(CoroutineScope(coroutineContext + clock))
        val visible = CompletableDeferred<Unit>()
        var navigations = 0
        cards.open("a", null, { visible.await() }) {
            assertEquals(0f, cards.alphaFor("a"))
            navigations++
        }
        cards.open("b", null, {}, { navigations++ })
        repeat(20) { yield(); clock.sendFrame(it * 16_000_000L) }
        assertEquals(0, navigations)
        assertEquals(1f, cards.alphaFor("a"))
        visible.complete(Unit)
        repeat(20) { yield(); clock.sendFrame((it + 20) * 16_000_000L) }
        yield()
        assertEquals(1, navigations)
        assertEquals(0f, cards.alphaFor("a"))
        assertEquals(1f, cards.alphaFor("b"))
        cards.restore()
        assertEquals(0f, cards.alphaFor("a"))
        repeat(24) { yield(); clock.sendFrame((it + 40) * 16_000_000L) }
        yield()
        assertEquals(1f, cards.alphaFor("a"))
        coroutineContext.cancelChildren()
    }

    @Test fun visibilityAccountsForBothHeaderAndBottomBar() {
        val viewport = Rect(0f, 120f, 1000f, 700f)
        assertEquals(-40f, playlistScrollDistance(Rect(20f, 80f, 320f, 462f), viewport))
        assertEquals(82f, playlistScrollDistance(Rect(20f, 400f, 320f, 782f), viewport))
        assertEquals(0f, playlistScrollDistance(Rect(20f, 160f, 320f, 542f), viewport))
    }

    @Test fun landingFadesInformationInContinuously() = runBlocking {
        val clock = BroadcastFrameClock()
        val cards = PlaylistCardTransition(CoroutineScope(coroutineContext + clock))
        cards.open("a", null, {}) {}
        repeat(20) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertEquals(0f, cards.alphaFor("a"))
        cards.restore()
        repeat(5) { yield(); clock.sendFrame((it + 20) * 16_000_000L) }
        yield()
        assertTrue(cards.alphaFor("a") > 0f && cards.alphaFor("a") < .3f)
        repeat(8) { yield(); clock.sendFrame((it + 25) * 16_000_000L) }
        yield()
        assertTrue(cards.alphaFor("a") < 1f)
        repeat(20) { yield(); clock.sendFrame((it + 33) * 16_000_000L) }
        yield()
        assertEquals(1f, cards.alphaFor("a"))
        coroutineContext.cancelChildren()
    }

    @Test fun recommendationCardsStayCompactAndScrollableAcrossWidths() {
        val phone = recommendationCardLayout(350f)
        assertTrue(phone.compact)
        assertEquals(259f, phone.widthDp, .01f)
        assertTrue(phone.heightDp < 320f)

        val portraitTablet = recommendationCardLayout(728f)
        assertFalse(portraitTablet.compact)
        assertEquals(260f, portraitTablet.widthDp, .01f)
        assertTrue(portraitTablet.widthDp * 2.5f < 728f)

        val landscapeTablet = recommendationCardLayout(984f)
        assertEquals(300f, landscapeTablet.widthDp, .01f)
        assertTrue(landscapeTablet.widthDp * 3f < 984f)
    }
}
