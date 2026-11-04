package com.musicone

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject

internal fun offlinePlaybackUrl(key: String): String {
    val path = "/" + key.substringAfterLast('|').substringAfterLast('/')
    return URI("musicone-cache", "audio", path, null).toASCIIString() +
        "?key=" + URLEncoder.encode(key, "UTF-8")
}

internal fun offlinePlaybackKey(url: String): String? {
    if (!url.startsWith("musicone-cache:")) return null
    return runCatching {
        val uri = URI(url)
        uri.rawQuery?.substringAfter("key=", "")?.takeIf { it.isNotEmpty() }
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
}

internal fun offlineRecordQuality(key: String, record: JSONObject?): AudioQuality? =
    record?.storedQuality()
        ?: resolvedPlaybackQuality(key)
        ?: legacyCachedAudioQuality(key.substringAfterLast('|'))

/**
 * 记录里保存的是取源时的真实档位，文件头不能把 24bit 无损改判成 Hi-Res。
 * 文件头只用于把标高的 Hi-Res 纠正为无损，以及给没有档位记录的旧缓存补判。
 */
internal fun offlineStandardQuality(stored: AudioQuality, recorded: Boolean, media: FlacMediaInfo?): AudioQuality = when {
    stored.isCustom || media == null -> stored
    stored == AudioQuality.HI_RES && media.cdResolution -> AudioQuality.LOSSLESS
    recorded -> stored
    media.beyondLossless -> AudioQuality.HI_RES
    else -> AudioQuality.LOSSLESS
}

/** 完整字节仍需排除试听；待校验的旧记录可以直接用本地媒体时长补验。 */
internal fun verifyOfflineRecord(
    record: JSONObject?, completeBytes: Boolean, readDuration: () -> Long?, saveVerification: (Boolean) -> Unit,
): Boolean {
    if (!completeBytes || record?.optBoolean("trial") == true) return false
    if (record?.optBoolean("pending") != true) return true
    val duration = readDuration() ?: return false
    val full = mediaDurationMatches(duration, record.optLong("durationMs"))
    saveVerification(full)
    return full
}

/** 保存目录文案快照，包被卸载或目录更新后仍保留缓存的真实档位和说明。 */
internal fun JSONObject.putStoredQuality(quality: AudioQuality): JSONObject = put("quality", quality.name).apply {
    if (quality.isCustom) {
        put("qualityLabel", quality.label).put("qualityCompactLabel", quality.compactLabel)
        put("qualityDescription", quality.description).put("qualityStandard", quality.standardQuality?.name)
    }
}

internal fun JSONObject.storedQuality(): AudioQuality? {
    val quality = AudioQuality.parse(optString("quality")) ?: return null
    if (!quality.isCustom) return quality
    if (!has("qualityLabel")) return quality
    return AudioQuality.custom(quality.sourceId!!, quality.qualityId,
        optString("qualityLabel", quality.label), optString("qualityCompactLabel", quality.compactLabel),
        optString("qualityDescription", quality.description), AudioQuality.parse(optString("qualityStandard")))
}
