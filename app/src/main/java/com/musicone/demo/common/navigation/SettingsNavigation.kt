package com.musicone.demo

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
    private var loginReturn = SettingsDestination.SOURCES
    var backInterceptor: (() -> Unit)? = null
    var choosingSource by mutableStateOf(false)
    var cachedPlaylistId by mutableStateOf<String?>(null)
        private set
    fun openCachedMusic() { cachedPlaylistId = null; destination = SettingsDestination.CACHED_MUSIC }
    fun openCachedPlaylist(id: String) { cachedPlaylistId = id; destination = SettingsDestination.CACHED_PLAYLIST }

    fun openSources() { destination = SettingsDestination.SOURCES }

    fun openSettings() {
        destination = SettingsDestination.SETTINGS
    }

    fun openPlatformLogin(fromAvatar: Boolean = false) {
        loginReturn = if (fromAvatar) SettingsDestination.CLOSED else SettingsDestination.SOURCES
        choosingSource = false
        destination = SettingsDestination.PLATFORM_LOGIN
    }

    fun back() {
        backInterceptor?.let { it(); return }
        backPage()
    }

    fun backPage() {
        destination = when (destination) {
            SettingsDestination.CACHED_PLAYLIST -> SettingsDestination.CACHED_MUSIC
            SettingsDestination.CACHED_MUSIC -> SettingsDestination.SETTINGS
            SettingsDestination.PLATFORM_LOGIN -> loginReturn
            SettingsDestination.SOURCES -> SettingsDestination.SETTINGS
            SettingsDestination.SETTINGS -> SettingsDestination.CLOSED
            SettingsDestination.CLOSED -> SettingsDestination.CLOSED
        }
    }

    fun close() {
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
