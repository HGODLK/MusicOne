package com.musicone.source.bilibili

internal data class BiliAudioSelection(val quality: String, val stream: BiliAudioStream)

/** 播放与协议测试共用同一选择过程，解码器事实和手动解锁仍由请求边界传入。 */
internal fun selectBiliAudio(audio: BiliPlayAudioResult, preferred: String, automatic: Boolean,
    decoder: Boolean, unlocked: Boolean, usbExclusive: Boolean): BiliAudioSelection {
    val requested = if (automatic && preferred != "STANDARD") {
        if (decoder && !usbExclusive) "DOLBY" else "HI_RES"
    } else preferred
    val dolbyAllowed = !usbExclusive && if (automatic) decoder else decoder || unlocked
    return when {
        requested == "DOLBY" && dolbyAllowed && audio.dolbyAudio != null -> BiliAudioSelection("DOLBY", audio.dolbyAudio)
        requested !in setOf("STANDARD", "HIGHER", "EXHIGH") && audio.hiResAudio != null -> BiliAudioSelection("HI_RES", audio.hiResAudio)
        audio.defaultAudio != null -> BiliAudioSelection("STANDARD", audio.defaultAudio)
        else -> throw PlatformApiException("当前视频未包含可用的音频流")
    }
}
