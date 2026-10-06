package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EntityNavigationTest {
    @Test fun ninthPageDropsOldestAndReturnsThroughRemainingEight() = runBlocking {
        val clock = BroadcastFrameClock()
        val nav = EntityNavigation(CoroutineScope(coroutineContext + clock))
        var time = 0L
        suspend fun settle() { repeat(65) { yield(); time += 16_666_667; clock.sendFrame(time); yield() } }
        repeat(9) { index ->
            nav.open(EntityTarget.Artist(QqSearchSinger("mid$index", "歌手$index", null)))
            settle()
        }
        assertEquals(8, nav.pages.size)
        assertEquals("mid1", (nav.pages.first().target as EntityTarget.Artist).singer.id)
        assertNull(nav.pages.first().sourceMenu)
        assertEquals(Rect.Zero, nav.pages.first().origin)
        repeat(8) { nav.back(); settle() }
        assertTrue(nav.pages.isEmpty())
        assertFalse(nav.moving)
        coroutineContext.cancelChildren()
    }
    @Test fun duplicateTapWhileMovingDoesNotPushTwice() = runBlocking {
        val clock = BroadcastFrameClock()
        val nav = EntityNavigation(CoroutineScope(coroutineContext + clock))
        val target = EntityTarget.Artist(QqSearchSinger("mid", "歌手", null))
        nav.open(target); nav.open(target)
        assertEquals(1, nav.pages.size)
        coroutineContext.cancelChildren()
    }
    @Test fun backDuringOpeningContinuesFromCurrentPosition() = runBlocking {
        val clock = BroadcastFrameClock()
        val nav = EntityNavigation(CoroutineScope(coroutineContext + clock))
        nav.open(EntityTarget.Artist(QqSearchSinger("mid", "歌手", null)))
        var time = 0L
        repeat(8) { yield(); time += 16_666_667; clock.sendFrame(time); yield() }
        val frame = nav.pages.single()
        val before = frame.progress.value
        assertTrue(before > 0f && before < 1f)
        nav.back()
        assertEquals(before, frame.progress.value)
        repeat(40) { yield(); time += 16_666_667; clock.sendFrame(time); yield() }
        assertTrue(nav.pages.isEmpty())
        assertFalse(nav.moving)
        coroutineContext.cancelChildren()
    }
}
