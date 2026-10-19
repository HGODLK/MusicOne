package com.musicone.demo

import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import java.io.IOException

internal class CacheQuotaException(message: String) : IOException(message)

/** 正常播放在容量不足时继续流播；主动缓存与预取立即停止，不空下载整首歌曲。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class QuotaCacheDataSink(private val disk: MusicDiskCache, private val strict: Boolean) : DataSink {
    private var sink: DataSink? = null
    private var key: String? = null

    override fun open(dataSpec: DataSpec) {
        val resource = requireNotNull(dataSpec.key)
        val reason = disk.beginWrite(resource, if (dataSpec.length >= 0) dataSpec.position + dataSpec.length else -1)
        if (reason != null) { if (strict) throw CacheQuotaException(reason); return }
        key = resource
        val delegate = CacheDataSink.Factory().setCache(disk.audio).setFragmentSize(524_288).createDataSink()
        sink = delegate
        delegate.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val delegate = sink ?: return
        val reason = disk.reserve(requireNotNull(key), length.toLong())
        if (reason != null) {
            close()
            if (strict) throw CacheQuotaException(reason)
            return
        }
        delegate.write(buffer, offset, length)
    }

    override fun close() {
        val resource = key
        try { sink?.close() }
        finally {
            sink = null; key = null
            if (resource != null) disk.finishWrite(resource)
        }
    }
}
