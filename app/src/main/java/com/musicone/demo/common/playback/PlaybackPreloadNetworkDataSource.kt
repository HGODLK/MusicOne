package com.musicone.demo

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** 仅包装播放用 HTTP 上游；预加载下载器、本地和磁盘缓存读取不进入网络速度样本。 */
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
