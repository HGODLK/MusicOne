package com.musicone.source.sdk

/** 可选播放策略；未实现此接口时，宿主使用默认值。 */
interface SourcePlaybackBehavior {
    val renewOnPlayerErrorLimit: Int get() = 0
    val skipOnPlayerError: Boolean get() = true
    val pauseOnFailure: Boolean get() = false
}

/** 可选歌词交接和刷新策略，不向已有提供者接口增加抽象成员。 */
interface SourceLyricBehavior {
    val waitForSingleSwitchSettlement: Boolean get() = false
    val preserveVerbatimFallback: Boolean get() = false
}
