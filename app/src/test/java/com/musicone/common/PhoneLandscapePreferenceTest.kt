package com.musicone

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneLandscapePreferenceTest {
    @Test fun disablingLandscapeUsesPortraitForBothSidesAndHiddenPreparation() = runBlocking {
        try {
            PhonePlayerPreferences.updateDisableLandscapePlayer(true)
            for (rotation in listOf(-90, 0, 90)) {
                val presentation = PhonePlayerPresentation(this).apply {
                    isPhone = true; latestRotation = rotation
                    nativeOrientation = PhonePlayerNativeOrientation(rotation, true)
                }
                presentation.prepareOpen()
                assertEquals(0, presentation.rotationDegrees)
                assertEquals(rotation, presentation.latestRotation)
                assertEquals(0, presentation.nativeRotation)
                assertEquals(0, retainedPlayerLayoutRotation(0, presentation.nativeRotation,
                    mounted = false, retentionAllowed = true, defaultPhonePlayer = true))
            }
            yield()
        } finally { PhonePlayerPreferences.updateDisableLandscapePlayer(false) }
    }

    @Test fun reenablingLandscapeRestoresSensorDirectionAndNeverChangesTheCustomPlayerPolicy() = runBlocking {
        try {
            PhonePlayerPreferences.updateDisableLandscapePlayer(true)
            for (rotation in listOf(-90, 90)) {
                val presentation = PhonePlayerPresentation(this).apply {
                    isPhone = true; customPlayer = true; latestRotation = rotation
                }
                presentation.prepareOpen()
                assertEquals(rotation, presentation.rotationDegrees)
                presentation.updateCustomPresentation(rotation, mounted = true)
                assertEquals(rotation, presentation.rotationDegrees)
            }
            PhonePlayerPreferences.updateDisableLandscapePlayer(false)
            for (rotation in listOf(-90, 0, 90))
                assertEquals(rotation, PhonePlayerOrientationPolicy.playerRotation(rotation, false))
        } finally { PhonePlayerPreferences.updateDisableLandscapePlayer(false) }
    }
}
