package com.musicone

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PhonePlayerNativeOrientationTest {
    @Test fun gravityCoordinatesKeepBothLandscapeSidesAndTreatBothFacesAsFlat() {
        assertEquals(0, phonePlayerGravityPose(0f, 9.8f, 0f)!!.orientation)
        assertEquals(-90, phonePlayerStrictRotation(phonePlayerGravityPose(-9.8f, 0f, 0f)!!.orientation))
        assertEquals(90, phonePlayerStrictRotation(phonePlayerGravityPose(9.8f, 0f, 0f)!!.orientation))
        assertEquals(0, phonePlayerStrictRotation(phonePlayerGravityPose(0f, -9.8f, 0f)!!.orientation))
        for (z in listOf(-9.8f, 9.8f)) assertEquals(0f, phonePlayerGravityPose(0f, 0f, z)!!.inclination, .001f)
        assertNull(phonePlayerGravityPose(0f, 0f, 0f))
        assertNull(phonePlayerGravityPose(Float.NaN, 1f, 0f))
    }

    @Test fun horizontalAndPortraitEntryBandsAreBothLimitedToFifteenDegrees() {
        for (angle in listOf(75, 90, 105)) assertEquals(-90, phonePlayerStrictRotation(angle))
        for (angle in listOf(255, 270, 285)) assertEquals(90, phonePlayerStrictRotation(angle))
        for (angle in listOf(0, 15, 165, 180, 195, 345, 359)) assertEquals(0, phonePlayerStrictRotation(angle))
        for (angle in listOf(-1, 16, 60, 74, 106, 164, 196, 240, 254, 286, 344, 360))
            assertNull(phonePlayerStrictRotation(angle))
    }

    @Test fun landscapeNeedsTwoHundredMillisecondsAndSmallNoiseDoesNotRestartTheWindow() {
        val gate = PhonePlayerNativeOrientationGate()
        gate.update(75, 80f, 1000)
        gate.update(90, 80f, 1050)
        gate.update(105, 80f, 1150)
        assertEquals(1200L, gate.decisionAtMs)
        assertFalse(gate.settle(1199).ready)
        assertEquals(PhonePlayerNativeOrientation(-90, true), gate.settle(1200))
        assertNull(gate.decisionAtMs)
        assertEquals(-90, gate.update(90, 80f, 1300).openingRotation)
    }

    @Test fun portraitNeedsThreeHundredMillisecondsWithoutASecondSensorCallback() {
        val gate = PhonePlayerNativeOrientationGate(-90)
        gate.update(15, 80f, 1000)
        gate.update(0, 80f, 1100)
        assertEquals(1300L, gate.decisionAtMs)
        assertEquals(-90, gate.settle(1299).rotationDegrees)
        assertFalse(gate.state.ready)
        assertEquals(PhonePlayerNativeOrientation(0, true), gate.settle(1300))
    }

    @Test fun leavingAnEntryBandCancelsTheDeadlineAndNeverReusesOldLandscapeForOpening() {
        val gate = PhonePlayerNativeOrientationGate(-90)
        gate.update(90, 80f, 0)
        gate.settle(200)
        assertEquals(-90, gate.state.openingRotation)
        gate.update(60, 80f, 210)
        assertEquals(-90, gate.state.rotationDegrees)
        assertEquals(0, gate.state.openingRotation)
        assertNull(gate.decisionAtMs)
        gate.update(90, 80f, 300)
        assertEquals(0, gate.settle(499).openingRotation)
        gate.update(106, 80f, 499)
        assertEquals(0, gate.settle(1000).openingRotation)
        gate.update(90, 80f, 1100)
        assertEquals(-90, gate.settle(1300).openingRotation)
    }

    @Test fun slightTiltWhileFaceUpOrDownCannotTriggerEitherLandscapeSide() {
        for (angle in listOf(90, 270)) for (tilt in listOf(0f, 5f, 20f, 29f)) for (face in listOf(-1f, 1f)) {
            val radians = Math.toRadians(tilt.toDouble())
            val x = (if (angle == 90) -1f else 1f) * (9.8 * sin(radians)).toFloat()
            val pose = phonePlayerGravityPose(x, 0f, face * (9.8 * cos(radians)).toFloat())!!
            val gate = PhonePlayerNativeOrientationGate()
            gate.update(pose.orientation, pose.inclination, 0)
            assertEquals(PhonePlayerNativeOrientation(), gate.settle(1000))
        }
    }

    @Test fun thirtyAndFortyFiveDegreePostureHysteresisRequiresANewStableWindow() {
        val gate = PhonePlayerNativeOrientationGate(-90)
        gate.update(270, 44f, 0)
        assertNull(gate.decisionAtMs)
        gate.update(270, 45f, 100)
        gate.update(270, 31f, 200)
        assertEquals(90, gate.settle(300).openingRotation)
        gate.update(90, 30f, 310)
        assertEquals(90, gate.state.rotationDegrees)
        assertEquals(0, gate.state.openingRotation)
        gate.update(90, 44f, 500)
        assertNull(gate.decisionAtMs)
        gate.update(90, 45f, 600)
        assertEquals(0, gate.settle(799).openingRotation)
        assertEquals(-90, gate.settle(800).openingRotation)
    }

    @Test fun goingFlatOrLosingTheSensorCancelsAPendingTurnAndKeepsTheDisplayedDirection() {
        for (inclination in listOf(10f, Float.NaN)) {
            val gate = PhonePlayerNativeOrientationGate(-90)
            gate.update(270, 80f, 0)
            gate.update(270, inclination, 199)
            assertEquals(PhonePlayerNativeOrientation(-90, false), gate.settle(1000))
            assertNull(gate.decisionAtMs)
        }
        val gate = PhonePlayerNativeOrientationGate(90)
        gate.update(90, 80f, 0)
        gate.reset()
        assertEquals(PhonePlayerNativeOrientation(90, false), gate.settle(1000))
        gate.reset(0)
        assertEquals(PhonePlayerNativeOrientation(), gate.state)
    }

    @Test fun passingPortraitBrieflyCanReachTheOppositeLandscapeWithoutAPortraitFlash() {
        val gate = PhonePlayerNativeOrientationGate(-90)
        gate.update(0, 80f, 0)
        gate.update(16, 80f, 100)
        gate.update(270, 80f, 200)
        assertEquals(-90, gate.settle(399).rotationDegrees)
        assertEquals(90, gate.settle(400).rotationDegrees)
    }

    @Test fun accelerometerFallbackUsesElapsedTimeAndClearsItsHistoryOnResume() {
        val filter = PhonePlayerGravityFilter()
        filter.update(0f, 9.8f, 0f, 0)
        val smooth = filter.update(-9.8f, 0f, 0f, 150_000_000)!!
        assertEquals(45, smooth.orientation)
        assertEquals(90f, smooth.inclination, .001f)
        filter.reset()
        assertEquals(90, filter.update(-9.8f, 0f, 0f, 160_000_000)!!.orientation)
        assertNull(filter.update(Float.NaN, 0f, 0f, 170_000_000))
        assertEquals(270, filter.update(9.8f, 0f, 0f, 180_000_000)!!.orientation)
    }

    @Test fun openingAndHiddenPreparationIgnoreRawCustomDirectionWhenNativePostureIsNotReady() = runBlocking {
        val disabled = PhonePlayerPreferences.disableLandscapePlayer
        try {
            PhonePlayerPreferences.updateDisableLandscapePlayer(false)
            val presentation = PhonePlayerPresentation(this).apply { isPhone = true; latestRotation = -90 }
            presentation.nativeOrientation = PhonePlayerNativeOrientation(-90, false)
            assertFalse(presentation.nativeRotationReady)
            assertEquals(0, presentation.nativeOpeningRotation)
            assertEquals(0, retainedPlayerLayoutRotation(0, presentation.nativeOpeningRotation, false, true, true))
            presentation.prepareOpen()
            assertEquals(0, presentation.rotationDegrees)
            assertEquals(0, presentation.turn.targetRotation)
            presentation.nativeOrientation = PhonePlayerNativeOrientation(90, true)
            presentation.prepareOpen()
            assertEquals(90, presentation.rotationDegrees)
            assertEquals(90, presentation.turn.targetRotation)
            assertEquals(-90, presentation.latestRotation)
        } finally {
            coroutineContext.cancelChildren()
            PhonePlayerPreferences.updateDisableLandscapePlayer(disabled)
        }
    }

    @Test fun explicitLandscapeDisableStillOverridesFlatPostureWhileCustomPlayerKeepsItsDirection() = runBlocking {
        val disabled = PhonePlayerPreferences.disableLandscapePlayer
        try {
            PhonePlayerPreferences.updateDisableLandscapePlayer(true)
            val presentation = PhonePlayerPresentation(this).apply {
                isPhone = true; latestRotation = -90; nativeOrientation = PhonePlayerNativeOrientation(90, false)
            }
            assertTrue(presentation.nativeRotationReady)
            assertEquals(0, presentation.nativeRotation)
            presentation.customPlayer = true
            presentation.prepareOpen()
            assertEquals(-90, presentation.rotationDegrees)
            assertEquals(-90, presentation.latestRotation)
        } finally {
            coroutineContext.cancelChildren()
            PhonePlayerPreferences.updateDisableLandscapePlayer(disabled)
        }
    }
}
