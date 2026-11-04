package com.musicone

/** 自动档位由包在 resolve(automatic=true) 中按真实协议与设备事实决定。 */
internal fun automaticQualityForSource(source: MusicSource, quality: AudioQuality,
    dolby: DolbyPlaybackEligibility = DolbyPlaybackPolicy.eligibility): AudioQuality =
    quality
