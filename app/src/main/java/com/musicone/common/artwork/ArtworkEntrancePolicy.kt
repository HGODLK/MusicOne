package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf

/** 远程封面默认从占位淡入真实封面；需要保护入场动效的局部内容可额外延后加载或只读缓存。 */
internal data class ArtworkEntrancePolicy(val loadingAllowed: Boolean = true, val fadeArtwork: Boolean = true,
    val cacheOnly: Boolean = false)

internal val LocalArtworkEntrancePolicy = staticCompositionLocalOf { ArtworkEntrancePolicy() }
