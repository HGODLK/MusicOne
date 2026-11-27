package com.musicone

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** 只统计在线播放时的 HTTP 下载速度，预加载、本地文件和磁盘缓存读取不计入测速。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackPreloadNetworkFactory(upstream: DataSource.Factory,
    bandwidth: PlaybackPreloadBandwidth): DataSource.Factory = DataSource.Factory {
    val source = upstream.createDataSource()
    object : DataSource by source {
        private var transfer: Long? = null
        override fun open(dataSpec: DataSpec): Long {
            val length = source.open(dataSpec)
            transfer = bandwidth.opened(dataSpec.uri.toString())
            return length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val id = transfer ?: return source.read(buffer, offset, length)
            bandwidth.beginRead(id, System.nanoTime())
            return try {
                source.read(buffer, offset, length).also { bandwidth.endRead(id, it, System.nanoTime()) }
            } catch (error: Exception) {
                bandwidth.failed(id)
                throw error
            }
        }
        override fun close() {
            transfer?.let(bandwidth::closed)
            transfer = null
            source.close()
        }
    }
}
