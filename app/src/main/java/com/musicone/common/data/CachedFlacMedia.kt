package com.musicone

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource

/** 旧版缓存可能把 Hi-Res 错记成无损，离线选音质前直接读本地 FLAC 文件头纠正。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun cachedFlacMediaInfo(cache: Cache, key: String, url: String): FlacMediaInfo? {
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
        flacMediaInfo(bytes.copyOf(count))
    } catch (_: Exception) {
        null
    } finally {
        reader.close()
    }
}
