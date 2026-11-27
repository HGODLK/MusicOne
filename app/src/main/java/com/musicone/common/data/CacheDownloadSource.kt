package com.musicone

import android.util.Base64
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations

/** DASH 歌曲只缓存实际音轨流，MPD 清单单独记在离线索引里，不能把清单当成整首歌的音频数据。 */
internal fun cacheDownloadUrl(source: PlaybackSource): String {
    if (source.mimeType != "application/dash+xml") return source.url
    val xml = String(Base64.decode(source.url.substringAfter(','), Base64.DEFAULT), Charsets.UTF_8)
    return dashAudioBaseUrl(xml) ?: throw PlatformApiException("音轨地址缺失，请重新解析")
}

internal fun dashAudioBaseUrl(xml: String): String? = Regex("<BaseURL[^>]*>(.*?)</BaseURL>", RegexOption.DOT_MATCHES_ALL)
    .find(xml)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")?.replace("&quot;", "\"")
    ?.replace("&apos;", "'")?.replace("&lt;", "<")?.replace("&gt;", ">")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }


/** 必须以服务端返回的总字节数为准来判断有没有下完，不能把 416 错误或已有片段末尾当成下载完成。 */
internal fun boundedCacheDownloadLength(remoteLength: Long, cachedEnd: Long, knownLength: Long = -1L): Long {
    if (remoteLength <= 0) throw PlatformApiException("服务器未声明音轨总长度，请重新解析")
    if (cachedEnd > remoteLength || (knownLength > 0 && knownLength != remoteLength))
        throw PlatformApiException("音轨长度已变化，已保留原缓存，请重新解析")
    return remoteLength
}

/** 确认总长度后，真正写入前由调用方再核对一次协程取消、账号和音源实例有没有变。 */
internal fun commitCacheDownloadLength(remoteLength: Long, cachedEnd: Long, knownLength: Long,
    commit: (() -> Unit) -> Unit, write: (Long) -> Unit): Long {
    val length = boundedCacheDownloadLength(remoteLength, cachedEnd, knownLength)
    commit { write(length) }
    return length
}

/** DASH 分段缓存可能已经下全但没记总长度；先从 0 探测一次拿准确总长，再交给 CacheWriter 补齐剩余区间。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun cacheDownloadSpec(source: PlaybackSource, upstream: DataSource.Factory, cache: Cache, key: String,
    commit: (() -> Unit) -> Unit): DataSpec {
    val headers = if (source.mimeType == "application/dash+xml")
        source.requestHeaders.filterKeys { !it.equals("Range", ignoreCase = true) } else source.requestHeaders
    val spec = DataSpec.Builder().setUri(cacheDownloadUrl(source)).setKey(key).setHttpRequestHeaders(headers)
        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
    if (source.mimeType != "application/dash+xml") return spec
    val probe = upstream.createDataSource()
    val remoteLength = try {
        // 直接用上游数据源探测总长（不读正文），避免 CacheDataSource 跳到缓存末尾发越界 Range。
        val openedLength = probe.open(spec)
        val contentRange = probe.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
        HttpUtil.getDocumentSize(contentRange).takeIf { it > 0 } ?: openedLength
    } finally { probe.close() }
    val cachedEnd = cache.getCachedSpans(key).maxOfOrNull { Math.addExact(it.position, it.length) } ?: 0L
    val length = commitCacheDownloadLength(remoteLength, cachedEnd,
        ContentMetadata.getContentLength(cache.getContentMetadata(key)), commit) { confirmedLength ->
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, confirmedLength)
        })
    }
    return spec.buildUpon().setLength(length).build()
}
