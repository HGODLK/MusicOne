package com.musicone.demo

import androidx.compose.runtime.staticCompositionLocalOf

/** 只由需要保护入场动效的局部内容启用，其他封面保持原加载与显示时机。 */
internal data class ArtworkEntrancePolicy(val loadingAllowed: Boolean = true, val fadeArtwork: Boolean = false)

internal val LocalArtworkEntrancePolicy = staticCompositionLocalOf { ArtworkEntrancePolicy() }
