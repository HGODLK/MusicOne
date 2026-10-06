package com.musicone.demo

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** 离线媒体显式携带原缓存键，读取链不再接入 HTTP 或过期 CDN 票据。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CachedPlaybackDataSource(
    private val cached: DataSource.Factory,
    private val streaming: DataSource.Factory,
    private val onReadFinished: () -> Unit = {},
) : DataSource {
    private var reader: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(listener: TransferListener) {
        listeners += listener
        reader?.addTransferListener(listener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val key = offlinePlaybackKey(dataSpec.uri.toString())
        val source = (if (key != null) cached else streaming).createDataSource()
        reader = source
        listeners.forEach(source::addTransferListener)
        return source.open(if (key == null) dataSpec else dataSpec.buildUpon().setKey(key).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(reader).read(buffer, offset, length)

    override fun getUri(): Uri? = reader?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = reader?.responseHeaders.orEmpty()
    override fun close() {
        try { reader?.close() }
        finally {
            reader = null
            // EOF 后内容长度才可能写回缓存；不能只依赖先到达的缓存块回调。
            onReadFinished()
        }
    }
}
