package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootPageNavigationTest {
    @Test
    fun primaryTitleFollowsRootPagerPosition() {
        val outgoing = pageTitleMotion(page = 0, pagerPosition = .4f)
        val incoming = pageTitleMotion(page = 1, pagerPosition = .4f)

        assertEquals(-.4f, outgoing.offsetFraction, .0001f)
        assertEquals(.6f, outgoing.alpha, .0001f)
        assertEquals(.6f, incoming.offsetFraction, .0001f)
        assertEquals(.4f, incoming.alpha, .0001f)
    }

    @Test
    fun primaryTitleFadesOnlyWhileTheFirstFeedItemIsAtTheTop() {
        assertEquals(1f, primaryTitleVisibility(0, 0, 48f))
        assertEquals(.5f, primaryTitleVisibility(0, 24, 48f))
        assertEquals(0f, primaryTitleVisibility(0, 48, 48f))
        assertEquals(0f, primaryTitleVisibility(1, 0, 48f))
    }

    @Test
    fun primaryHeaderTracksTheMostAdvancedOverlayMotion() {
        assertEquals(1f, primaryHeaderMotionVisibility(0f, 0f))
        assertEquals(.6f, primaryHeaderMotionVisibility(.4f, .2f), .0001f)
        assertEquals(.25f, primaryHeaderMotionVisibility(.3f, .75f), .0001f)
        assertEquals(0f, primaryHeaderMotionVisibility(1f, 0f))
    }

    @Test
    fun positionSelectsNearestRootPage() {
        assertEquals(MusicOnePage.HOME, rootPageForPosition(.49f))
        assertEquals(MusicOnePage.MY, rootPageForPosition(.51f))
    }

    @Test
    fun dragPositionUsesValidPagerAnchor() {
        assertEquals(RootPagerAnchor(0, .4f), rootPagerAnchorForPosition(.4f))
        val secondPage = rootPagerAnchorForPosition(.6f)
        assertEquals(1, secondPage.page)
        assertEquals(-.4f, secondPage.offsetFraction, .0001f)
        assertEquals(RootPagerAnchor(1, 0f), rootPagerAnchorForPosition(2f))
    }

    @Test
    fun bottomCapsuleIgnoresDragUntilOverlayHasClosed() {
        assertNull(nextCapsuleDragPosition(null, 0f, 40f, 100f, blocked = true))
        assertEquals(.4f, nextCapsuleDragPosition(null, 0f, 40f, 100f, blocked = false)!!, .0001f)
    }

    @Test
    fun primaryHeaderDispatchesHomeReselectionToTheVisibleFeed() {
        val state = PrimaryPageHeaderState()
        var requests = 0
        val action: () -> Unit = { requests += 1 }

        state.bindScrollToTop(MusicOnePage.HOME, action)
        state.requestScrollToTop(MusicOnePage.HOME)
        assertEquals(1, requests)

        state.unbindScrollToTop(MusicOnePage.HOME, action)
        state.requestScrollToTop(MusicOnePage.HOME)
        assertEquals(1, requests)
    }
}
