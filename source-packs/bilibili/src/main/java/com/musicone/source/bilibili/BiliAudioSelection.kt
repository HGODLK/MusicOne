package com.musicone.source.bilibili

internal data class BiliAudioSelection(val quality: String, val stream: BiliAudioStream)

/** 选音轨的公共逻辑（播放和单测共用），设备是否支持杜比、是否手动解锁由调用方传入。 */
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
