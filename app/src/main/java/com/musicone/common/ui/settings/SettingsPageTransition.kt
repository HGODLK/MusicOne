package com.musicone

import androidx.compose.animation.*

/** 沿用 0.2.0 的设置层级动效，宿主页面和外部音源入口共用同一节奏。 */
internal fun settingsPageTransform(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return ContentTransform(
        targetContentEnter = fadeIn(musicMotion(320)) + slideInHorizontally(musicMotion(360)) { direction * it / 16 },
        initialContentExit = fadeOut(musicMotion(220)) + slideOutHorizontally(musicMotion(280)) { -direction * it / 16 },
        sizeTransform = null,
    )
}

/** 出场页面保留自己的参数，不能读取已经被下一页覆盖的歌单或音源身份。 */
internal data class SettingsPageTarget(
    val destination: SettingsDestination,
    val cachedPlaylistId: String? = null,
    val supplementalSourceId: String? = null,
    val supplementalEntryRevision: Long = 0L,
    val forward: Boolean = true,
) {
    val stateKey: String get() = when (destination) {
        SettingsDestination.CACHED_PLAYLIST -> "${destination.name}:$cachedPlaylistId"
        else -> destination.name
    }
}

internal fun SettingsNavigation.pageTarget() = SettingsPageTarget(
    destination, cachedPlaylistId.takeIf { destination == SettingsDestination.CACHED_PLAYLIST },
    supplementalSourceId.takeIf { destination == SettingsDestination.SUPPLEMENTAL },
    supplementalEntryRevision.takeIf { destination == SettingsDestination.SUPPLEMENTAL } ?: 0L,
    forward,
)
