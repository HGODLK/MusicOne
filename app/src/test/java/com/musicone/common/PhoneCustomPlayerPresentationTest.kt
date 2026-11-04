package com.musicone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneCustomPlayerPresentationTest {
    @Test fun hiddenCustomPlayerRetainsSensorDirectionWithoutRotatingRootPages() = runBlocking {
        val presentation = PhonePlayerPresentation(this).apply {
            isPhone = true
            customPlayer = true
            latestRotation = 90
        }
        presentation.updateCustomPresentation(90, mounted = true)
        assertEquals(90, presentation.rotationDegrees)
        presentation.updateCustomPresentation(90, mounted = false)
        assertEquals(0, presentation.rotationDegrees)
        assertEquals(90, presentation.latestRotation)
        presentation.prepareOpen()
        assertEquals(90, presentation.rotationDegrees)
    }

    @Test fun bothPhoneLandscapeDirectionsResetAfterClosing() = runBlocking {
        for (rotation in listOf(-90, 90)) {
            val presentation = PhonePlayerPresentation(this).apply { isPhone = true }
            presentation.updateCustomPresentation(rotation, mounted = true)
            assertEquals(rotation, presentation.rotationDegrees)
            presentation.updateCustomPresentation(rotation, mounted = false)
            assertEquals(0, presentation.rotationDegrees)
        }
    }

    @Test fun tabletNeverInheritsThePhoneCanvasRotation() = runBlocking {
        val presentation = PhonePlayerPresentation(this).apply { isPhone = true }
        presentation.updateCustomPresentation(90, mounted = true)
        presentation.isPhone = false
        presentation.updateCustomPresentation(90, mounted = true)
        assertEquals(0, presentation.rotationDegrees)
    }

    @Test fun portraitCustomPlayerKeepsRootOrientationUnchanged() = runBlocking {
        val presentation = PhonePlayerPresentation(this).apply { isPhone = true }
        presentation.updateCustomPresentation(0, mounted = true)
        assertEquals(0, presentation.rotationDegrees)
    }
}
