package com.musicone.demo

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.Cache

/** 从本地缓存校验时长，不为试听判断再次访问远程音频。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun cachedAudioDuration(cache: Cache, key: String, url: String): Long? = runCatching {
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
    if (length <= 0 || !cache.isCached(key, 0, length)) return null
    val source = object : MediaDataSource() {
        override fun getSize(): Long = length
        override fun close() = Unit
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            if (size == 0) return 0
            val count = minOf(size.toLong(), length - position).toInt()
            val reader = CacheDataSource.Factory().setCache(cache).createDataSource()
            return try {
                reader.open(DataSpec.Builder().setUri(url).setKey(key).setPosition(position).setLength(count.toLong()).build())
                reader.read(buffer, offset, count)
            } finally { reader.close() }
        }
    }
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(source)
        (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
            extractor.getTrackFormat(index).takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.div(1000)
        }
    } finally { extractor.release(); source.close() }
}.getOrNull()
