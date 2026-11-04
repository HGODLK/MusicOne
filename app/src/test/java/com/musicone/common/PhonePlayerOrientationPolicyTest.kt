package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePlayerOrientationPolicyTest {
    private val policy = PhonePlayerOrientationPolicy

    @Test fun phonePolicyStopsAt600DpAndRejectsUnknownWidth() {
        assertFalse(policy.isPhone(0))
        assertTrue(policy.isPhone(360))
        assertTrue(policy.isPhone(599))
        assertFalse(policy.isPhone(600))
        assertFalse(policy.isPhone(840))
    }

    @Test fun systemRotationLockIsRespectedUnlessUserOverridesIt() {
        assertTrue(policy.shouldListen(true, true, false, true))
        assertFalse(policy.shouldListen(true, false, false, true))
        assertTrue(policy.shouldListen(true, false, true, true))
        assertTrue(policy.shouldListen(true, true, true, true))
    }

    @Test fun overrideCannotEnableSensorsInBackgroundOrWithoutSensorSupport() {
        for (systemAllowsRotation in listOf(false, true)) {
            for (ignoreRotationLock in listOf(false, true)) {
                assertFalse(policy.shouldListen(false, systemAllowsRotation, ignoreRotationLock, true))
                assertFalse(policy.shouldListen(true, systemAllowsRotation, ignoreRotationLock, false))
            }
        }
    }

    @Test fun unknownOrientationPreservesBothLandscapeSidesAndPortrait() {
        for (previous in listOf(-90, 0, 90)) {
            assertEquals(previous, policy.rotationDegrees(-1, previous))
        }
    }

    @Test fun landscapeDirectionsCounterRotateThePhysicalDeviceAndPortraitResetsThem() {
        assertEquals(-90, policy.rotationDegrees(90, 0))
        assertEquals(90, policy.rotationDegrees(270, 0))
        for (previous in listOf(-90, 0, 90)) {
            for (orientation in listOf(0, 180, 359)) {
                assertEquals(0, policy.rotationDegrees(orientation, previous))
            }
        }
        assertEquals(90, policy.rotationDegrees(270, -90))
        assertEquals(-90, policy.rotationDegrees(90, 90))
    }

    @Test fun leftLandscapeHysteresisPreventsBoundaryOscillationAndStillExits() {
        assertEquals(0, policy.rotationDegrees(59, 0))
        assertEquals(-90, policy.rotationDegrees(60, 0))
        assertEquals(-90, policy.rotationDegrees(40, -90))
        assertEquals(-90, policy.rotationDegrees(39, -90))
        assertEquals(-90, policy.rotationDegrees(26, -90))
        assertEquals(0, policy.rotationDegrees(25, -90))
        assertEquals(-90, policy.rotationDegrees(120, 0))
        assertEquals(0, policy.rotationDegrees(121, 0))
        assertEquals(-90, policy.rotationDegrees(140, -90))
        assertEquals(-90, policy.rotationDegrees(141, -90))
        assertEquals(0, policy.rotationDegrees(155, -90))
    }

    @Test fun rightLandscapeHysteresisPreventsBoundaryOscillationAndStillExits() {
        assertEquals(0, policy.rotationDegrees(239, 0))
        assertEquals(90, policy.rotationDegrees(240, 0))
        assertEquals(90, policy.rotationDegrees(220, 90))
        assertEquals(90, policy.rotationDegrees(219, 90))
        assertEquals(0, policy.rotationDegrees(205, 90))
        assertEquals(90, policy.rotationDegrees(300, 0))
        assertEquals(0, policy.rotationDegrees(301, 0))
        assertEquals(90, policy.rotationDegrees(320, 90))
        assertEquals(90, policy.rotationDegrees(321, 90))
        assertEquals(90, policy.rotationDegrees(334, 90))
        assertEquals(0, policy.rotationDegrees(335, 90))
    }

    @Test fun crossingTheIntermediatePortraitAngleCanReachTheOtherLandscapeWithoutPortraitFlash() {
        val direction = PhonePlayerOrientationStability(-90)
        assertEquals(-90, direction.update(145, 0))
        assertEquals(-90, direction.update(180, 20))
        assertEquals(-90, direction.update(220, 120))
        assertEquals(-90, direction.update(270, 200))
        assertEquals(-90, direction.settle(279))
        assertEquals(90, direction.settle(280))
        assertEquals(null, direction.decisionAtMs)

        assertEquals(90, direction.update(350, 400))
        assertEquals(90, direction.update(0, 480))
        assertEquals(90, direction.update(40, 560))
        assertEquals(90, direction.update(90, 600))
        assertEquals(-90, direction.settle(680))
    }

    @Test fun stablePortraitCommitsAtItsDeadlineWithoutAnotherSensorEvent() {
        for (initial in listOf(-90, 90)) {
            for (portrait in listOf(0, 180, 359)) {
                val direction = PhonePlayerOrientationStability(initial)
                assertEquals(initial, direction.update(portrait, 1000))
                assertEquals(1320L, direction.decisionAtMs)
                assertEquals(initial, direction.settle(1319))
                assertEquals(0, direction.settle(1320))
                assertEquals(null, direction.decisionAtMs)
            }
        }
    }

    @Test fun leavingPortraitEntryBandAndUnknownOrPausedSensorCancelThePendingDecision() {
        val direction = PhonePlayerOrientationStability(-90)
        direction.update(0, 0)
        direction.update(30, 200)
        assertEquals(-90, direction.settle(400))
        direction.update(10, 500)
        direction.update(-1, 600)
        assertEquals(-90, direction.settle(1000))
        direction.update(0, 1100)
        direction.reset(-90)
        assertEquals(-90, direction.settle(2000))
        direction.reset(0)
        assertEquals(0, direction.rotationDegrees)
        assertEquals(null, direction.decisionAtMs)
    }

    @Test fun sensorNoiseDoesNotRestartAStableCandidateDeadline() {
        val direction = PhonePlayerOrientationStability()
        assertEquals(0, direction.update(90, 0))
        assertEquals(0, direction.update(100, 30))
        assertEquals(80L, direction.decisionAtMs)
        assertEquals(-90, direction.settle(80))
        direction.update(10, 100)
        direction.update(15, 200)
        assertEquals(420L, direction.decisionAtMs)
        assertEquals(0, direction.settle(420))
    }
}
