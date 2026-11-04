package com.musicone

import android.util.Base64
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations

/** DASH 资源只缓存实际音轨；DASH 清单保留在离线索引中，不能冒充整首歌曲字节。 */
internal fun cacheDownloadUrl(source: PlaybackSource): String {
    if (source.mimeType != "application/dash+xml") return source.url
    val xml = String(Base64.decode(source.url.substringAfter(','), Base64.DEFAULT), Charsets.UTF_8)
    return dashAudioBaseUrl(xml) ?: throw PlatformApiException("音轨地址缺失，请重新解析")
}

internal fun dashAudioBaseUrl(xml: String): String? = Regex("<BaseURL[^>]*>(.*?)</BaseURL>", RegexOption.DOT_MATCHES_ALL)
    .find(xml)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")?.replace("&quot;", "\"")
    ?.replace("&apos;", "'")?.replace("&lt;", "<")?.replace("&gt;", ">")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }


/** 只有服务器确认的总长可界定完整缓存，不能把任意416或现有片段末尾当作EOF。 */
internal fun boundedCacheDownloadLength(remoteLength: Long, cachedEnd: Long, knownLength: Long = -1L): Long {
    if (remoteLength <= 0) throw PlatformApiException("服务器未声明音轨总长度，请重新解析")
    if (cachedEnd > remoteLength || (knownLength > 0 && knownLength != remoteLength))
        throw PlatformApiException("音轨长度已变化，已保留原缓存，请重新解析")
    return remoteLength
}

/** 长度已确认后仍须由调用方在最终写入前核对取消、账号和实例代次。 */
internal fun commitCacheDownloadLength(remoteLength: Long, cachedEnd: Long, knownLength: Long,
    commit: (() -> Unit) -> Unit, write: (Long) -> Unit): Long {
    val length = boundedCacheDownloadLength(remoteLength, cachedEnd, knownLength)
    commit { write(length) }
    return length
}

/** DASH分段读取可能已覆盖全文件却没有总长；先从零打开音轨核对，再让CacheWriter只填有界区间。 */
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
        // 不复用CacheDataSource，避免再次直接跳到已缓存末端发出越界Range；不读取响应正文。
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
