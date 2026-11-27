package com.musicone.source.bilibili

/** 只按接口返回的编码字段显示格式，采样率和位深等播放器解析后再展示，不写默认值。 */
internal fun biliStreamCodecLabel(stream: BiliAudioStream, dolby: Boolean): String = when {
    dolby -> "Dolby Atmos"
    stream.codecs.startsWith("mp4a", true) -> "AAC"
    stream.codecs.equals("flac", true) || stream.mimeType.equals("audio/flac", true) -> "FLAC"
    stream.codecs.startsWith("ec-3", true) -> "E-AC-3"
    stream.codecs.startsWith("ac-3", true) -> "AC-3"
    stream.codecs.startsWith("opus", true) -> "Opus"
    stream.codecs.isNotBlank() -> stream.codecs.uppercase()
    else -> "音频"
}

