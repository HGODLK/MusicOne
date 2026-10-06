package com.musicone.demo

import androidx.compose.animation.core.SnapSpec
import org.junit.Assert.*
import org.junit.Test

class ExperienceSettingsTest {
    @Test fun mainSourceIsASecondLevelAndLoginReturnsThere() {
        val navigation = SettingsNavigation()
        navigation.openSettings()
        navigation.openSources()
        assertEquals(SettingsDestination.SOURCES, navigation.destination)
        navigation.openPlatformLogin()
        navigation.back()
        assertEquals(SettingsDestination.SOURCES, navigation.destination)
        navigation.back()
        assertEquals(SettingsDestination.SETTINGS, navigation.destination)
        navigation.back()
        assertEquals(SettingsDestination.CLOSED, navigation.destination)
    }
    @Test fun avatarLoginReturnsToMyPage() {
        val navigation = SettingsNavigation()
        navigation.choosingSource = true
        navigation.openPlatformLogin(fromAvatar = true)
        assertFalse(navigation.choosingSource)
        navigation.back()
        assertEquals(SettingsDestination.CLOSED, navigation.destination)
    }
    @Test fun reducedMotionAppliesToTweenAndSpringAndCanBeDisabled() {
        try {
            ExperiencePreferences.update(ExperienceOptions(reduceMotion = true))
            assertTrue(musicMotion<Float>() is SnapSpec)
            assertTrue(musicSpring<Float>() is SnapSpec)
            ExperiencePreferences.update(ExperienceOptions())
            assertFalse(musicMotion<Float>() is SnapSpec)
            assertFalse(musicSpring<Float>() is SnapSpec)
        } finally { ExperiencePreferences.update(ExperienceOptions()) }
    }
}
