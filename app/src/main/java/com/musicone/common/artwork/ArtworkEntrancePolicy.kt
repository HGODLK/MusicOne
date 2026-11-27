package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf

/** 封面默认加载完后从占位图淡入；入场动画期间可以延迟加载或只读缓存避免掉帧。 */
internal data class ArtworkEntrancePolicy(val loadingAllowed: Boolean = true, val fadeArtwork: Boolean = true,
    val cacheOnly: Boolean = false)

internal val LocalArtworkEntrancePolicy = staticCompositionLocalOf { ArtworkEntrancePolicy() }
