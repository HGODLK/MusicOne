package com.musicone.demo

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
    record?.optString("quality")?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
        ?: qqPlaybackQuality(key.substringAfterLast('|'))

/** 完整字节仍需排除试听；待校验的旧记录可以直接用本地媒体时长补验。 */
internal fun verifyOfflineRecord(
    record: JSONObject?, completeBytes: Boolean, readDuration: () -> Long?, saveVerification: (Boolean) -> Unit,
): Boolean {
    if (!completeBytes || record?.optBoolean("trial") == true) return false
    if (record?.optBoolean("pending") != true) return true
    val duration = readDuration() ?: return false
    val full = qqMediaDurationMatches(duration, record.optLong("durationMs"))
    saveVerification(full)
    return full
}
