package com.musicone

/** USB 开关与实际音频连接同时存在时才限制杜比；解锁仅放宽解码能力筛选。 */
internal data class DolbyPlaybackEligibility(
    val decoderSupported: Boolean,
    val unlocked: Boolean,
    val usbOutputEnabled: Boolean,
    val usbAudioConnected: Boolean,
) {
    val usbExclusive: Boolean get() = usbOutputEnabled && usbAudioConnected
    val allowed: Boolean get() = !usbExclusive && (decoderSupported || unlocked)
    val automaticAllowed: Boolean get() = !usbExclusive && decoderSupported
    val unavailableReason: String? get() = when {
        usbExclusive -> "USB独占输出时不可用"
        !decoderSupported && !unlocked -> "设备未声明杜比支持，可在设置中解锁"
        else -> null
    }
}

internal object DolbyPlaybackPolicy {
    val capabilities by lazy { DeviceAudioCapabilities.detect() }
    val eligibility: DolbyPlaybackEligibility get() = DolbyPlaybackEligibility(
        capabilities.supportsDolbyPlayback, PlaybackOptions.unlockDolbyAtmos,
        PlaybackOptions.usbLosslessOutput, UsbAudioConnectionState.connected,
    )

    fun permits(quality: AudioQuality): Boolean = quality.capabilityQuality != AudioQuality.DOLBY || eligibility.allowed

    fun permits(source: PlaybackSource): Boolean = !source.requiresDolbyCapability() || eligibility.allowed

    fun requireAllowed(source: PlaybackSource) {
        if (!permits(source)) throw PlatformApiException(eligibility.unavailableReason.orEmpty())
    }

    fun effective(quality: AudioQuality): AudioQuality =
        if (permits(quality)) quality else if (quality.isCustom)
            quality.fallbackCandidates(MusicSource.fromId(quality.sourceId!!)).firstOrNull(::permits) ?: quality
        else AudioQuality.HI_RES

    fun requireAllowed(quality: AudioQuality) {
        if (!permits(quality)) throw PlatformApiException(eligibility.unavailableReason.orEmpty())
    }
}

internal fun playerQualitiesForSource(source: MusicSource): List<AudioQuality> {
    if (source == MusicSource.LOCAL) return emptyList()
    com.musicone.sourceintegration.sourceCatalogQualities(source)?.let { return it }
    // 播放降级可以跳过某档音质，设置菜单仍需展示音源支持的全部档位。
    return AudioQuality.entries.flatMap { it.fallbackCandidates(source) }.distinct().sortedByDescending { it.ordinal }
}

internal fun isDolbyAudioMime(mime: String?): Boolean =
    mime?.lowercase() in setOf("audio/eac3", "audio/eac3-joc", "audio/ac4")

/** 解码失败或输出限制变化时，自定义档严格沿音源声明寻找非杜比资源。 */
internal fun compatibleNonDolbyQuality(source: MusicSource, current: AudioQuality?): AudioQuality? =
    if (current?.isCustom == true) current.fallbackCandidates(source)
        .firstOrNull { it != current && it.capabilityQuality != AudioQuality.DOLBY }
    else AudioQuality.HI_RES

/** 只给新目录补实际资源能力约束；容器名称和音质名称都不能证明杜比编码。 */
internal fun declaredDolbyResource(mimeType: String?, format: String): Boolean {
    val mime = mimeType?.substringBefore(';')?.trim()
    if (isDolbyAudioMime(mime)) return true
    val codecs = Regex("codecs\\s*=\\s*[\"']?([^;\"']+)", RegexOption.IGNORE_CASE)
        .find(mimeType.orEmpty())?.groupValues?.get(1)?.split(',').orEmpty()
    val declared = codecs + format
    return declared.any { it.trim().lowercase() in setOf("eac3", "e-ac-3", "ec-3", "ec+3", "eac3-joc", "ac4", "ac-4") }
}

internal fun PlaybackSource.requiresDolbyCapability(): Boolean =
    actualQuality.capabilityQuality == AudioQuality.DOLBY ||
        (actualQuality.isCustom && declaredDolbyResource(mimeType, format))
