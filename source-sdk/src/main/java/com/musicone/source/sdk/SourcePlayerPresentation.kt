package com.musicone.source.sdk

/** 播放服务可选的展示策略；未实现时沿用宿主通用文案和封面行为。 */
interface SourcePlayerPresentation {
    fun qualityLabel(qualityId: String): String? = null
    fun qualityCompactLabel(qualityId: String): String? = null
    val pendingAudioDescription: String? get() = null
    val missingArtworkHoldMs: Long get() = 0
}
