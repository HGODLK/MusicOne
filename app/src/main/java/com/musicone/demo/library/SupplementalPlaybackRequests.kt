package com.musicone.demo

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import java.util.concurrent.ConcurrentHashMap

/** 请求头与稳定缓存键绑定到已解析的具体地址，不修改 QQ 的全局 HTTP 工厂。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object SupplementalPlaybackRequests {
    private data class Request(val headers: Map<String, String>, val key: String)
    private val requests = ConcurrentHashMap<String, Request>()
    fun register(url: String, headers: Map<String, String>, cacheKey: String) {
        requests[url] = Request(headers.toMap(), cacheKey)
        if (requests.size > 512) requests.keys.firstOrNull { it != url }?.let(requests::remove)
    }
    fun cacheKey(url: String): String? = requests[url]?.key
    fun factory(upstream: DataSource.Factory): DataSource.Factory = ResolvingDataSource.Factory(upstream) { spec ->
        requests[spec.uri.toString()]?.let { request ->
            if (spec.uri.scheme in listOf("http", "https")) MusicDiskCache.available()?.activeKey = request.key
            spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders + request.headers).setKey(request.key).build()
        } ?: spec
    }
}
