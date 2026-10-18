package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LibraryCardSwipeStateTest {
    @Test fun interruptedSwitchContinuesFromDisplayedPositionAndReleasesAfterReverse() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        var selected = 0
        val swipe = LibraryCardSwipeState(0, scope) { selected = it }
        var time = 0L
        suspend fun frames(count: Int) { repeat(count) { yield(); time += 16_666_667L; clock.sendFrame(time); yield() } }
        swipe.select(1)
        frames(9)
        val interrupted = swipe.value
        assertTrue(interrupted > 0f && interrupted < 1f)
        swipe.begin()
        assertEquals(interrupted, swipe.value, .0001f)
        swipe.move(25f, 400f)
        val dragged = swipe.value
        assertEquals((interrupted - .0625f).coerceAtLeast(0f), dragged, .0001f)
        swipe.select(0)
        assertEquals(dragged, swipe.value, .0001f)
        frames(50)
        assertEquals(0f, swipe.value, .0001f)
        assertEquals(0, selected)
        assertFalse(swipe.moving)
        coroutineContext.cancelChildren()
    }

    @Test fun positionWinsAtEdgesAndShortReversalUsesReleaseDirection() {
        assertEquals(1, libraryCardSwipeTarget(.85f, -300f, 1800f, 400f, false, 0))
        assertEquals(0, libraryCardSwipeTarget(.55f, -60f, 1000f, 400f, false, 0))
        assertEquals(0, libraryCardSwipeTarget(.8f, -300f, -1800f, 400f, true, 0))
    }

    @Test fun slowSwipeSwitchesBothWaysBeforeReachingTheMiddle() {
        assertEquals(1, libraryCardSwipeTarget(.3f, -120f, 0f, 400f, false, 0))
        assertEquals(0, libraryCardSwipeTarget(.7f, 120f, 0f, 400f, false, 1))
        assertEquals(0, libraryCardSwipeTarget(.25f, -100f, 0f, 400f, false, 0))
        assertEquals(1, libraryCardSwipeTarget(.75f, 100f, 0f, 400f, false, 1))
    }

    @Test fun shortFlingUsesLowerVelocityButCancellationKeepsSelection() {
        assertEquals(1, libraryCardSwipeTarget(.22f, -88f, -300f, 400f, false, 0))
        assertEquals(0, libraryCardSwipeTarget(.78f, 88f, 300f, 400f, false, 1))
        assertEquals(0, libraryCardSwipeTarget(.6f, -240f, -1000f, 400f, true, 0))
        assertEquals(1, libraryCardSwipeTarget(.4f, 240f, 1000f, 400f, true, 1))
    }
}
