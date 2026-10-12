package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricIntermediateSeekPositionTest {
    @Test fun returningCoverAndListShareOneVisualSeekTrajectory() {
        val initialWindow = 120f
        val initialItem = 40f
        val target = lyricSeekVisualTarget(0f, initialItem, initialWindow)
        assertEquals(160f, target, 0f)
        var applied = 0f
        listOf(120f to 0f, 80f to 32f, 20f to 90f, 0f to 160f).forEach { (window, visualMotion) ->
            applied += lyricSeekScrollRequest(visualMotion, window, initialWindow, applied)
            val item = initialItem - applied
            assertEquals(target - visualMotion, item + window, .001f)
        }
        assertEquals(0f, initialItem - applied, .001f)
    }

    @Test fun newTargetRetainsTheCurrentWindowCoordinate() {
        val initialWindow = 120f
        val applied = 18f
        val newItemOffset = 75f
        val target = lyricSeekVisualTarget(applied, newItemOffset, initialWindow)
        val window = 60f
        val requested = lyricSeekScrollRequest(target, window, initialWindow, applied)
        assertEquals(0f, newItemOffset - requested + window, .001f)
    }

    @Test fun fastReleaseDoesNotPassANearbyTargetInEitherDirection() {
        for (direction in listOf(-1f, 1f)) {
            val travel = LyricIntermediateSeekTravel()
            var applied = 0f
            repeat(120) {
                travel.advance(3, direction * 20f - applied, 0f, direction * -2000f,
                    16_666_667L, false) { requested ->
                    applied += requested
                    requested
                }
                assertTrue("短距离寻焦不能越过目标再折返", applied * direction <= 20.001f)
                assertTrue(applied * direction >= 0f)
            }
            assertEquals(direction * 20f, applied, .001f)
            assertFalse(travel.running)
        }
    }

    @Test fun aNearbyNewTargetBrakesWithoutResettingTheVisiblePosition() {
        val travel = LyricIntermediateSeekTravel()
        var applied = 0f
        repeat(8) {
            travel.advance(3, 240f - applied, 0f, -1600f, 16_666_667L, false) { requested ->
                applied += requested
                requested
            }
        }
        val before = applied
        val latest = before + 10f
        travel.advance(4, 10f, 0f, 0f, 0L, false) { requested ->
            applied += requested
            requested
        }
        assertEquals(before, applied, .001f)
        repeat(120) {
            travel.advance(4, latest - applied, 0f, 0f, 16_666_667L, false) { requested ->
                applied += requested
                requested
            }
            assertTrue(applied <= latest + .001f)
        }
        assertEquals(latest, applied, .001f)
    }

    @Test fun anUnavailableScrollRangeDoesNotEraseTheSpringVelocity() {
        val available = LyricIntermediateSeekTravel()
        val blocked = LyricIntermediateSeekTravel()
        available.advance(3, 80f, 100f, -500f, 16_666_667L, false) { it }
        blocked.advance(3, 80f, 100f, -500f, 16_666_667L, false) { 0f }
        assertTrue(available.velocity > 0f)
        assertEquals(available.velocity, blocked.velocity, .001f)
    }
}
