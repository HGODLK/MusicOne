package com.musicone

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal class SettingsNavigation {
    var origin = androidx.compose.ui.geometry.Rect.Zero
    var destination by mutableStateOf(SettingsDestination.CLOSED)
        private set
    var forward by mutableStateOf(true)
        private set
    private var loginReturn = SettingsDestination.SOURCES
    private var managementReturn = SettingsDestination.SOURCES
    private var supplementalReturn = SettingsDestination.SETTINGS
    var supplementalSourceId by mutableStateOf<String?>(null)
        private set
    var supplementalEntryRevision by mutableStateOf(0L)
        private set
    var backInterceptor: (() -> Unit)? = null
    var choosingSource by mutableStateOf(false)
    var cachedPlaylistId by mutableStateOf<String?>(null)
        private set
    fun openCachedMusic() { forward = true; cachedPlaylistId = null; destination = SettingsDestination.CACHED_MUSIC }
    fun openCachedPlaylist(id: String) { forward = true; cachedPlaylistId = id; destination = SettingsDestination.CACHED_PLAYLIST }

    fun openSources() { forward = true; destination = SettingsDestination.SOURCES }
    fun openLibrary() { forward = true; destination = SettingsDestination.MUSIC_LIBRARY }
    fun openSupplemental() { forward = true; supplementalEntryRevision++; supplementalSourceId = null; supplementalReturn = SettingsDestination.SETTINGS; destination = SettingsDestination.SUPPLEMENTAL }
    fun openSupplementalSource(sourceId: String) {
        forward = true
        supplementalEntryRevision++
        supplementalSourceId = sourceId
        supplementalReturn = if (destination == SettingsDestination.SOURCE_MANAGEMENT) SettingsDestination.SOURCE_MANAGEMENT else SettingsDestination.SETTINGS
        destination = SettingsDestination.SUPPLEMENTAL
    }
    fun openSourceManagement() { forward = true; managementReturn = destination; destination = SettingsDestination.SOURCE_MANAGEMENT }

    fun openSettings() {
        forward = true
        destination = SettingsDestination.SETTINGS
    }

    fun openPlatformLogin(fromAvatar: Boolean = false) {
        forward = true
        loginReturn = if (fromAvatar) SettingsDestination.CLOSED else SettingsDestination.SOURCES
        choosingSource = false
        destination = SettingsDestination.PLATFORM_LOGIN
    }

    fun back() {
        backInterceptor?.let { it(); return }
        backPage()
    }

    fun backPage() {
        forward = false
        destination = when (destination) {
            SettingsDestination.CACHED_PLAYLIST -> SettingsDestination.CACHED_MUSIC
            SettingsDestination.CACHED_MUSIC -> SettingsDestination.SETTINGS
            SettingsDestination.MUSIC_LIBRARY -> SettingsDestination.SETTINGS
            SettingsDestination.SUPPLEMENTAL -> supplementalReturn
            SettingsDestination.SOURCE_MANAGEMENT -> managementReturn
            SettingsDestination.PLATFORM_LOGIN -> loginReturn
            SettingsDestination.SOURCES -> SettingsDestination.SETTINGS
            SettingsDestination.SETTINGS -> SettingsDestination.CLOSED
            SettingsDestination.CLOSED -> SettingsDestination.CLOSED
        }
    }

    fun close() {
        forward = false
        destination = SettingsDestination.CLOSED
    }
}

@Composable
internal fun rememberSettingsNavigation(): SettingsNavigation {
    val navigation = remember { SettingsNavigation() }
    BackHandler(enabled = navigation.destination != SettingsDestination.CLOSED) {
        navigation.back()
    }
    return navigation
}
