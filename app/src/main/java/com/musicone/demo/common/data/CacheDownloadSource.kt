package com.musicone.demo

import android.util.Base64

/** B站只缓存实际音轨；DASH 清单保留在离线索引中，不能冒充整首歌曲字节。 */
internal fun cacheDownloadUrl(source: PlaybackSource): String {
    if (source.mimeType != "application/dash+xml") return source.url
    val xml = String(Base64.decode(source.url.substringAfter(','), Base64.DEFAULT), Charsets.UTF_8)
    return dashAudioBaseUrl(xml) ?: throw PlatformApiException("音轨地址缺失，请重新解析")
}

internal fun dashAudioBaseUrl(xml: String): String? = Regex("<BaseURL[^>]*>(.*?)</BaseURL>", RegexOption.DOT_MATCHES_ALL)
    .find(xml)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")?.replace("&quot;", "\"")
    ?.replace("&apos;", "'")?.replace("&lt;", "<")?.replace("&gt;", ">")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
