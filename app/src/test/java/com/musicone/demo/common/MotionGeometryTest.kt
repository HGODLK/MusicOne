package com.musicone.demo

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class MotionGeometryTest {
    @Test fun dismissalStartsOnlyInUpperHalfOutsideLyrics() {
        val tabletLyrics = Rect(550f, 40f, 1000f, 800f)
        assertTrue(canStartPlayerDismiss(Offset(250f, 300f), 800f, tabletLyrics))
        assertFalse(canStartPlayerDismiss(Offset(700f, 300f), 800f, tabletLyrics))
        assertFalse(canStartPlayerDismiss(Offset(250f, 500f), 800f, tabletLyrics))
        val phoneLyrics = Rect(26f, 140f, 374f, 650f)
        assertTrue(canStartPlayerDismiss(Offset(100f, 100f), 800f, phoneLyrics))
        assertFalse(canStartPlayerDismiss(Offset(100f, 200f), 800f, phoneLyrics))
        assertTrue(canStartPlayerDismiss(Offset(100f, 200f), 800f, Rect.Zero))
    }
    @Test fun expandedPlayerStopsAtDeviceManagedSquareBounds() {
        assertEquals(19f, playerSurfaceCorner(0f))
        assertEquals(0f, playerSurfaceCorner(1f))
    }

    @Test fun pageSurfaceHandsOffFromStationaryMiniGlassWithoutStretchingIt() {
        assertEquals(0f, playerSurfaceReveal(0f), .0001f)
        assertTrue(playerSurfaceReveal(.16f) in .45f..55f)
        assertEquals(1f, playerSurfaceReveal(.32f), .0001f)
        assertEquals(1f, playerSurfaceReveal(1f), .0001f)
    }

    @Test fun tabletLayoutRequiresBothWideWindowAndLandscapeOrientation() {
        assertTrue(usesTabletLandscape(1_280.dp, 800.dp))
        assertFalse(usesTabletLandscape(800.dp, 1_280.dp))
        assertFalse(usesTabletLandscape(700.dp, 700.dp))
        assertFalse(usesTabletLandscape(699.dp, 500.dp))
        assertFalse(usesTabletLandscape(900.dp, 479.dp))
        assertEquals(PlayerLayoutMode.WIDE_SINGLE, playerLayoutSpec(800.dp, 1_200.dp).mode)
        assertEquals(PlayerLayoutMode.LANDSCAPE_TWO_PANE, playerLayoutSpec(1_200.dp, 800.dp).mode)
    }

    @Test fun flyingGlassStaysVisibleDuringEarlyExpansionAndHandsOffLater() {
        assertEquals(1f, playerGlassAlpha(0f), .0001f)
        assertEquals(1f, playerGlassAlpha(.12f), .0001f)
        assertTrue(playerGlassAlpha(.47f) in .45f..55f)
        assertEquals(0f, playerGlassAlpha(.82f), .0001f)
        assertEquals(1f, playerGlassBorderAlpha(0f), .0001f)
        assertEquals(.25f, playerGlassBorderAlpha(.5f), .0001f)
        assertEquals(0f, playerGlassBorderAlpha(1f), .0001f)
    }

    @Test fun lyricsCoverCornerUsesQuantizedScaleCompensation() {
        assertEquals(12f, phoneArtworkCorner(0f))
        assertEquals(68f, phoneArtworkCorner(1f))
        assertEquals(0f, phoneArtworkCorner(.5f) % 4f)
        assertEquals(phoneArtworkCorner(.501f), phoneArtworkCorner(.5f))
    }

    @Test fun tabletLyricsHeaderLeavesTheEnlargedArtworkFullyOpaque() {
        assertEquals(86f, phoneLyricsHeaderHeight(62f), .0001f)
        assertEquals(116f, phoneLyricsHeaderHeight(96f), .0001f)
    }

    @Test fun lyricExitRevealSharesTheRetractionProgressWithoutFirstFrameJump() {
        assertEquals(0f, lyricExitRevealFraction(0f, 0f), .00001f)
        assertEquals(0f, lyricExitRevealFraction(.2f, .2f), .00001f)
        assertTrue(lyricExitRevealFraction(.2001f, .2f) < .001f)
        assertEquals(1f, lyricExitRevealFraction(.48f, .2f), .00001f)
        val samples = (0..100).map { lyricExitRevealFraction(it / 100f, 0f) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun lyricExitCompletesTheRowCrossingTheRealViewportEdge() {
        assertEquals(-18f, lyricExitScrollDelta(-70, 52, -52)!!, .0001f)
        assertNull(lyricExitScrollDelta(-104, 52, -52))
        assertNull(lyricExitScrollDelta(-52, 52, -52))
        assertNull(lyricExitScrollDelta(-40, 52, -52))
    }

    @Test fun flyingTextUsesTheMeasuredEndpointTypography() {
        val anchor = MotionAnchor(Rect(0f, 0f, 300f, 50f), textSizeSp = 30f)
        assertEquals(30f, playerControlTextSize(anchor, 22f), .0001f)
        assertEquals(22f, playerControlTextSize(anchor.copy(textSizeSp = 0f), 22f), .0001f)
        assertTrue(playerTextMarqueeEnabled(MotionPhase.SHOWN))
        assertFalse(playerTextMarqueeEnabled(MotionPhase.PREPARING))
        assertFalse(playerTextMarqueeEnabled(MotionPhase.MOVING))
        assertTrue(playerTextMarqueeEnabled(MotionPhase.MOVING, targetContentHandoff = true))
    }

    @Test fun backdropCapturePausesWhilePreparedGlassSnapshotsAreMoving() {
        assertTrue(shouldRefreshPlayerBackdrop(MotionPhase.HIDDEN, false, 0f))
        assertFalse(shouldRefreshPlayerBackdrop(MotionPhase.MOVING, true, .5f))
        assertFalse(shouldRefreshPlayerBackdrop(MotionPhase.SHOWN, true, 1f))
        assertTrue(shouldRefreshPlayerBackdrop(MotionPhase.MOVING, false, .2f))
        assertFalse(shouldRefreshPlayerBackdrop(MotionPhase.MOVING, true, .9f))
    }

    @Test fun glassSnapshotsUseBoundedHalfResolutionSampling() {
        assertEquals(.5f, playerGlassSampleScale(1080, 720), .0001f)
        assertEquals(1_440f / 2_960f, playerGlassSampleScale(2_960, 1_848), .0001f)
        assertEquals(.35f, playerGlassSampleScale(5_000, 3_000), .0001f)
        assertEquals(.28f, playerGlassTintAlpha(0f), .0001f)
        assertEquals(.16f, playerGlassTintAlpha(1f), .0001f)
        assertEquals(1f, playerGlassSourceHandoffAlpha(0f), .0001f)
        assertEquals(.5f, playerGlassSourceHandoffAlpha(.08f), .0001f)
        assertEquals(0f, playerGlassSourceHandoffAlpha(.16f), .0001f)
        assertEquals(0f, playerGlassSourceHandoffAlpha(1f), .0001f)
    }
    @Test fun movingCoverReachesBothMeasuredAnchors() {
        val card = Rect(20f, 90f, 300f, 472f)
        val cover = Rect(0f, 24f, 440f, 504f)
        assertEquals(card, motionRect(card, cover, 0f))
        assertEquals(cover, motionRect(card, cover, 1f))
        assertEquals(Rect(10f, 57f, 370f, 488f), motionRect(card, cover, .5f))
        assertEquals(card, motionRect(card, cover, -1f))
        assertEquals(cover, motionRect(card, cover, 2f))
    }

    @Test fun offscreenSourceUsesFallbackInsteadOfFlyingToInvisibleCard() {
        val host = Rect(0f, 24f, 1200f, 800f)
        assertTrue(anchorFits(Rect(20f, 80f, 300f, 460f), host))
        assertFalse(anchorFits(Rect(20f, -50f, 300f, 300f), host))
        assertFalse(anchorFits(Rect.Zero, host))
        assertFalse(anchorFits(Rect(20f, 600f, 300f, 900f), host))
    }

    @Test fun playerDismissUsesFinalPositionAndReleaseDirection() {
        assertTrue(shouldDismissPlayer(.64f, 0f, true))
        assertTrue(shouldDismissPlayer(.8f, 1_000f, true))
        assertFalse(shouldDismissPlayer(.97f, 1_000f, true))
        assertFalse(shouldDismissPlayer(.9f, -1_000f, true))
        assertFalse(shouldDismissPlayer(.4f, 1_000f, false))
    }

    @Test fun qualityMenuExpandsFromTheActualTouchPoint() {
        val host = Rect(100f, 50f, 900f, 650f)
        val origin = qualityMenuTransformOrigin(Offset(300f, 500f), host)
        assertEquals(.25f, origin.pivotFractionX, .0001f)
        assertEquals(.75f, origin.pivotFractionY, .0001f)
        assertEquals(TransformOrigin.Center, qualityMenuTransformOrigin(Offset.Zero, Rect.Zero))
    }

    @Test fun flyingPlaylistArtworkBleedsPastItsClipToHideSamplingSeams() {
        assertEquals(
            Rect(9f, 19f, 111f, 121f),
            playlistArtworkBleedBounds(Rect(10f, 20f, 110f, 120f), 1f),
        )
    }

    @Test fun playlistDetailsEnterUpwardThenReverseWithThePage() {
        assertEquals(0f, playlistDetailProgress(1f, 0f), .0001f)
        assertEquals(.5f, playlistDetailProgress(1f, .5f), .0001f)
        assertEquals(.5f, playlistDetailProgress(.61f, 1f), .0001f)
        assertEquals(1f, playlistDetailProgress(1f, 1f), .0001f)
    }

    @Test fun interruptedMotionUsesOnlyRemainingDuration() {
        assertEquals(80, motionDuration(.25f, 0f, 320))
        assertEquals(240, motionDuration(.25f, 1f, 320))
        assertEquals(1, motionDuration(1f, 1f, 320))
    }
}
