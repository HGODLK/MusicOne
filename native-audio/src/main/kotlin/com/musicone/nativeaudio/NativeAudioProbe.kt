package com.musicone.nativeaudio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource

data class NativeProbeMetadata(
    val formatName: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val sourceBitDepth: Int,
    val isDsd: Boolean,
    val dsdRateMultiplier: Int = 0,
    val title: String,
    val artist: String,
    val album: String,
    val coverData: ByteArray? = null,
    val coverMime: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeProbeMetadata) return false
        return formatName == other.formatName &&
                durationMs == other.durationMs &&
                sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                sourceBitDepth == other.sourceBitDepth &&
                isDsd == other.isDsd &&
                title == other.title &&
                artist == other.artist &&
                album == other.album
    }

    override fun hashCode(): Int {
        var result = formatName.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + sourceBitDepth
        result = 31 * result + isDsd.hashCode()
        return result
    }
}

object NativeAudioProbe {

    @OptIn(UnstableApi::class)
    fun probe(dataSource: DataSource, dataSpec: DataSpec): Result<NativeProbeMetadata> {
        return runCatching {
            NativeAudioBridge.ensureLoaded()
            val ioBridge = NativeAudioIoBridge(dataSource, dataSpec)
            try {
                val raw = NativeAudioBridge.nativeProbe(ioBridge)
                    ?: error("探测原生音频失败：未返回结果")
                if (!raw.success) {
                    error(raw.errorMessage ?: "原生音频格式探测失败")
                }
                if (raw.isWavPackHybrid) {
                    error("暂不支持 WavPack Hybrid 模式")
                }

                val title = raw.title?.takeIf { it.isNotBlank() } ?: ""
                val artist = raw.artist?.takeIf { it.isNotBlank() } ?: "未知创作者"
                val album = raw.album?.takeIf { it.isNotBlank() } ?: ""

                NativeProbeMetadata(
                    formatName = raw.formatName ?: "AUDIO",
                    durationMs = raw.durationMs,
                    sampleRate = raw.sampleRate,
                    channelCount = raw.channelCount,
                    sourceBitDepth = raw.sourceBitDepth,
                    isDsd = raw.isDsd,
                    dsdRateMultiplier = raw.dsdRateMultiplier,
                    title = title,
                    artist = artist,
                    album = album,
                    coverData = raw.coverData,
                    coverMime = raw.coverMime,
                )
            } finally {
                ioBridge.close()
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun probeUri(context: Context, uri: Uri): Result<NativeProbeMetadata> {
        val dataSource = DefaultDataSource.Factory(context).createDataSource()
        val dataSpec = DataSpec.Builder().setUri(uri).build()
        return probe(dataSource, dataSpec)
    }
}
