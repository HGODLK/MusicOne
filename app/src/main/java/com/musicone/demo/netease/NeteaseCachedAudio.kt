package com.musicone.demo

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource

/** 旧缓存可能把 HiRes 写成无损；在离线选择档位之前按缓存头纠正，不联网。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun cachedNeteaseFlacInfo(cache: Cache, key: String, url: String): QqFlacInfo? {
    if (!cache.isCached(key, 0, 42)) return null
    val reader = CacheDataSource.Factory().setCache(cache).createDataSource()
    return try {
        reader.open(DataSpec.Builder().setUri(url).setKey(key).setLength(42).build())
        val bytes = ByteArray(42)
        var count = 0
        while (count < bytes.size) {
            val read = reader.read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read == 0) return null
            count += read
        }
        qqFlacInfo(bytes.copyOf(count))
    } catch (_: Exception) {
        null
    } finally {
        reader.close()
    }
}
