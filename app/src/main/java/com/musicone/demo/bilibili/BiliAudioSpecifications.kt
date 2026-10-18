package com.musicone.demo

/** 只描述平台实际给出的编码；采样率、位深等待音轨解析，不填默认规格。 */
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
