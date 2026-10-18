package com.musicone.nativeaudio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.Collections

/**
 * 格式识别与播放源分流工厂。
 * 针对 APE、ALAC (M4A)、WavPack、DSF、DFF、AIFF 整数 PCM 走 Native 解码 MediaSource；
 * 现有 MP3、AAC、FLAC 等继续走原生 Media3 链路。
 */
@OptIn(UnstableApi::class)
class NativeAudioRoutingMediaSourceFactory(
    private val defaultFactory: MediaSource.Factory,
    private val dataSourceFactory: DataSource.Factory,
    private val targetDsdSampleRate: Int = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
    private val context: Context? = null,
) : MediaSourceFactory {

    private val nativeFactory = NativeAudioMediaSource.Factory(
        dataSourceFactory = dataSourceFactory,
        targetDsdSampleRate = targetDsdSampleRate,
        context = context,
    )

    private val routingCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 64
            }
        }
    )

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        nativeFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        nativeFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri ?: return defaultFactory.createMediaSource(mediaItem)
        val shouldUseNative = shouldRouteToNative(uri, mediaItem.localConfiguration?.mimeType)
        return if (shouldUseNative) {
            nativeFactory.createMediaSource(mediaItem)
        } else {
            defaultFactory.createMediaSource(mediaItem)
        }
    }

    private fun shouldRouteToNative(uri: Uri, mimeType: String?): Boolean {
        val uriString = uri.toString()
        routingCache[uriString]?.let { return it }

        val scheme = uri.scheme?.lowercase().orEmpty()
        // 外部 HTTP/HTTPS 网络流不拦截，保留现有在线音源处理
        if (scheme == "http" || scheme == "https") {
            routingCache[uriString] = false
            return false
        }

        // 1. 尝试从 URI 路径直接解析扩展名
        val path = uri.path?.lowercase().orEmpty()
        val decodedPath = runCatching { Uri.decode(path) }.getOrDefault(path).lowercase()
        val extension = decodedPath.substringAfterLast('.', "")

        if (extension in setOf("ape", "wv", "dsf", "dff")) {
            routingCache[uriString] = true
            return true
        }

        // 2. 检查 MIME 类型提示
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.contains("ape") || mime.contains("wavpack") || mime.contains("dsd") || mime.contains("dsf") || mime.contains("dff")) {
            routingCache[uriString] = true
            return true
        }

        // 3. 对 M4A/MP4 容器、无后缀、.audio 或 content:// URI：使用原生探针对内容进行权威探测
        // 完美支持 moov atom 位于文件尾部的 24-bit ALAC，以及 SAF 虚拟 content:// 路径
        val isNativeSupported = runCatching {
            val ds = dataSourceFactory.createDataSource()
            val spec = DataSpec.Builder().setUri(uri).build()
            val probe = NativeAudioProbe.probe(ds, spec).getOrNull()
            probe != null && (
                probe.formatName in setOf("APE", "ALAC", "WavPack", "AIFF", "DSD64", "DSD128", "DSD256", "DSD512") ||
                probe.isDsd
            )
        }.getOrDefault(false)

        routingCache[uriString] = isNativeSupported
        return isNativeSupported
    }
}
