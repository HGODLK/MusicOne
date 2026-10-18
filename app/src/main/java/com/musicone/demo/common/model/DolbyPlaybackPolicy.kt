package com.musicone.demo

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

    fun permits(quality: AudioQuality): Boolean = quality != AudioQuality.DOLBY || eligibility.allowed

    fun effective(quality: AudioQuality): AudioQuality =
        if (permits(quality)) quality else AudioQuality.HI_RES

    fun requireAllowed(quality: AudioQuality) {
        if (!permits(quality)) throw PlatformApiException(eligibility.unavailableReason.orEmpty())
    }
}

internal fun playerQualitiesForSource(source: MusicSource): List<AudioQuality> =
    when (source) {
        MusicSource.LOCAL -> emptyList()
        MusicSource.BILIBILI -> listOf(AudioQuality.DOLBY, AudioQuality.HI_RES, AudioQuality.STANDARD)
        else -> PLAYER_AUDIO_QUALITIES.filter { it != AudioQuality.DOLBY || source == MusicSource.QQ }
    }

internal fun isDolbyAudioMime(mime: String?): Boolean =
    mime?.lowercase() in setOf("audio/eac3", "audio/eac3-joc", "audio/ac4")
