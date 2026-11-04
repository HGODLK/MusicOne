package com.musicone

import java.net.URI

/** 仅兼容读取 0.2 旧孤立缓存的确定文件名规格；新资源使用包声明和真实媒体头。 */
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
