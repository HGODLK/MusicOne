package com.musicone

/** 自动音质档位由音源包在 resolve(automatic=true) 时根据实际设备和接口情况决定。 */
internal fun automaticQualityForSource(source: MusicSource, quality: AudioQuality,
    dolby: DolbyPlaybackEligibility = DolbyPlaybackPolicy.eligibility): AudioQuality =
    quality
