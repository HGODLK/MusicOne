package com.musicone

import java.net.URI

/** 仅用于兼容读取 0.2 老版本缓存文件的文件名音质标识，新缓存直接读音源包信息和文件头。 */
internal fun legacyCachedAudioQuality(value: String): AudioQuality? {
    val normalized = if (value.startsWith("//")) "https:$value" else value
    val path = runCatching {
        if (normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) {
            URI.create(normalized).rawPath.orEmpty()
        } else normalized.substringBefore('?').substringBefore('#')
    }.getOrDefault(normalized.substringBefore('?').substringBefore('#'))
    val file = path.substringAfterLast('/')
    val specifications = listOf(
        Triple("M500", ".mp3", AudioQuality.STANDARD),
        Triple("M800", ".mp3", AudioQuality.EXHIGH),
        Triple("F000", ".flac", AudioQuality.LOSSLESS),
        Triple("RS01", ".flac", AudioQuality.HI_RES),
        Triple("D008", ".mp4", AudioQuality.DOLBY),
        Triple("D009", ".mp4", AudioQuality.DOLBY),
        Triple("D004", ".mp4", AudioQuality.DOLBY),
    )
    return specifications.firstOrNull { (prefix, suffix, _) ->
        file.startsWith(prefix, true) && file.endsWith(suffix, true)
    }?.third
}
