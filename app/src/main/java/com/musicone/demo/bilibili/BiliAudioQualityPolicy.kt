package com.musicone.demo

internal data class BiliAudioSelection(val stream: BiliAudioStream, val quality: AudioQuality) {
    val isDolby: Boolean get() = quality == AudioQuality.DOLBY
}

/** 高档首选共用 B站 HiRes/杜比音轨；自动选择只认真实解码能力，解锁仅用于手动选择。 */
internal fun biliAutomaticQuality(quality: AudioQuality, dolby: DolbyPlaybackEligibility): AudioQuality = when {
    quality.ordinal < AudioQuality.LOSSLESS.ordinal -> AudioQuality.STANDARD
    dolby.automaticAllowed -> AudioQuality.DOLBY
    else -> AudioQuality.HI_RES
}

internal fun automaticQualityForSource(source: MusicSource, quality: AudioQuality,
    dolby: DolbyPlaybackEligibility = DolbyPlaybackPolicy.eligibility): AudioQuality =
    if (source == MusicSource.BILIBILI) biliAutomaticQuality(quality, dolby) else quality

internal fun selectBiliAudio(audio: BiliPlayAudioResult, quality: AudioQuality, automatic: Boolean,
    dolby: DolbyPlaybackEligibility): BiliAudioSelection {
    val requested = if (automatic && quality == AudioQuality.DOLBY && !dolby.automaticAllowed)
        AudioQuality.HI_RES else quality
    val stream = when {
        requested == AudioQuality.DOLBY && (if (automatic) dolby.automaticAllowed else dolby.allowed) && audio.dolbyAudio != null ->
            BiliAudioSelection(audio.dolbyAudio, AudioQuality.DOLBY)
        requested.ordinal >= AudioQuality.LOSSLESS.ordinal && audio.hiResAudio != null ->
            BiliAudioSelection(audio.hiResAudio, AudioQuality.HI_RES)
        audio.defaultAudio != null -> BiliAudioSelection(audio.defaultAudio, AudioQuality.STANDARD)
        else -> throw PlatformApiException("当前视频未包含可用的音频流")
    }
    return stream
}

internal fun biliAvailableQualities(audio: BiliPlayAudioResult, dolby: DolbyPlaybackEligibility): List<AudioQuality> = buildList {
    if (audio.defaultAudio != null) add(AudioQuality.STANDARD)
    if (audio.hiResAudio != null) add(AudioQuality.HI_RES)
    if (audio.dolbyAudio != null && dolby.allowed) add(AudioQuality.DOLBY)
}

/** 普通档不借用高音质缓存，杜比缓存也不能越过自动选源时的能力降级。 */
internal fun biliCachedQualityAllowed(preferred: AudioQuality, actual: AudioQuality, allowOther: Boolean): Boolean =
    (allowOther || preferred == actual) &&
        (actual != AudioQuality.DOLBY || preferred == AudioQuality.DOLBY) &&
        (preferred.ordinal >= AudioQuality.LOSSLESS.ordinal || actual == AudioQuality.STANDARD)
