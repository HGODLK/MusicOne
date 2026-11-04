package com.musicone

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PhonePlayerTrackActionPolicyTest {
    @Test fun changingLayoutDoesNotJumpTheGlyphBeforeOrDuringRotation() {
        for (angle in listOf(-90f, -45f, 0f, 20f, 45f, 90f)) {
            for (element in listOf(-30f, 0f, 60f)) {
                val portrait = PhonePlayerTrackActionPolicy.iconRotation(0, element, angle)
                assertEquals(portrait, PhonePlayerTrackActionPolicy.iconRotation(90, element, angle), .0001f)
                assertEquals(portrait, PhonePlayerTrackActionPolicy.iconRotation(-90, element, angle), .0001f)
            }
        }
        assertEquals(0f, PhonePlayerTrackActionPolicy.iconRotation(90, 0f, 0f), .0001f)
        assertEquals(90f, PhonePlayerTrackActionPolicy.iconRotation(90, 0f, 45f), .0001f)
        assertEquals(180f, PhonePlayerTrackActionPolicy.iconRotation(90, 0f, 90f), .0001f)
    }
    @Test fun bothLandscapeSidesPutPreviousAbovePlayAndNextBelowPlayInViewingCoordinates() {
        for (rotation in listOf(-90, 90)) {
            for (previous in listOf(true, false)) {
                val onLeft = previous == PhonePlayerTrackActionPolicy.previousOnLeft(rotation)
                // 动作行已被内外两个窗口旋转抵消，左右位置先在物理竖屏窗口中取值。
                val physicalPosition = Offset(if (onLeft) -1f else 1f, 0f)
                val viewedPosition = rotate(physicalPosition, -rotation.toFloat())
                assertEquals(0f, viewedPosition.x, .0001f)
                assertEquals(if (previous) -1f else 1f, viewedPosition.y, .0001f)
                val press = Offset(PhonePlayerTrackActionPolicy.pressDirection(rotation, previous).toFloat(), 0f)
                assertEquals(viewedPosition.y, rotate(press, -rotation.toFloat()).y, .0001f)
            }
        }
    }

    @Test fun glyphsStayUpAndDownInLandscapeEvenWhileTheElementTurnIsMoving() {
        for (rotation in listOf(-90, 90)) {
            for (elementRotation in listOf(-90f, -45f, 0f, 45f, 90f)) {
                for (previous in listOf(true, false)) {
                    val intrinsic = Offset(if (previous) -1f else 1f, 0f)
                    val icon = PhonePlayerTrackActionPolicy.iconRotation(rotation, elementRotation)
                    val viewed = rotate(intrinsic, icon + elementRotation - rotation)
                    assertEquals(0f, viewed.x, .0001f)
                    assertEquals(if (previous) -1f else 1f, viewed.y, .0001f)
                }
            }
        }
    }

    @Test fun portraitKeepsPreviousOnTheLeftAndNextOnTheRightWithoutRotatingTheirGlyphs() {
        assertTrue(PhonePlayerTrackActionPolicy.previousOnLeft(0))
        assertEquals(-1, PhonePlayerTrackActionPolicy.pressDirection(0, true))
        assertEquals(1, PhonePlayerTrackActionPolicy.pressDirection(0, false))
        for (elementRotation in listOf(-90f, -45f, 0f, 45f, 90f)) {
            assertEquals(0f, elementRotation + PhonePlayerTrackActionPolicy.iconRotation(0, elementRotation), .0001f)
        }
    }

    private fun rotate(vector: Offset, degrees: Float): Offset {
        val angle = degrees * Math.PI / 180.0
        return Offset((vector.x * cos(angle) - vector.y * sin(angle)).toFloat(),
            (vector.x * sin(angle) + vector.y * cos(angle)).toFloat())
    }
}
