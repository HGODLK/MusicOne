package com.musicone.demo

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.musicone.nativeaudio.NativeAudioProbe
import com.musicone.nativeaudio.NativeProbeMetadata

internal data class ExtractedAudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mimeType: String,
    val formatName: String,
    val formatDescription: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val isDsd: Boolean,
    val coverBytes: ByteArray? = null,
)

/**
 * 独立的本地音频元数据提取组件，供导入与重新关联共用。
 * 先尝试原有系统方式，失败或遇到新增无损格式时回退至原生探测。
 */
internal object LocalAudioMetadataExtractor {

    private val NATIVE_PRIMARY_EXTENSIONS = setOf("ape", "wv", "dsf", "dff", "aif", "aiff", "aifc")

    fun extract(context: Context, uri: Uri, fallbackDisplayName: String): ExtractedAudioMetadata {
        val path = uri.path?.lowercase().orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase()

        val displayExtension = fallbackDisplayName.substringAfterLast('.', "").lowercase()
        // 1. 已知原生格式与 SAF 显示名称中的 AIFF，直接调用 Native 探测确认内容
        if (NATIVE_PRIMARY_EXTENSIONS.contains(extension) || displayExtension in setOf("aif", "aiff", "aifc")) {
            return extractViaNative(context, uri, fallbackDisplayName)
        }

        // 2. 其余格式先尝试系统 MediaMetadataRetriever
        return runCatching {
            extractViaSystem(context, uri, fallbackDisplayName)
        }.getOrElse { systemError ->
            // 系统提取失败时，回退到 Native 探测
            runCatching {
                extractViaNative(context, uri, fallbackDisplayName)
            }.getOrElse { nativeError ->
                throw systemError // 保留原始系统原因或具体原生错误
            }
        }
    }

    private fun extractViaSystem(context: Context, uri: Uri, fallbackDisplayName: String): ExtractedAudioMetadata {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mime = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val hasAudio = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"

            require(duration > 0 && (mime.isBlank() || mime.startsWith("audio/") || hasAudio)) {
                "系统未识别到可播放音频"
            }

            // AIFF 的采样率和位深以原生探测为准，避免系统成功读取标签后退回默认规格。
            if (mime.lowercase() in setOf("audio/aiff", "audio/x-aiff", "audio/aif", "audio/x-aif", "audio/aifc", "audio/x-aifc")) {
                return extractViaNative(context, uri, fallbackDisplayName)
            }

            // 对于 M4A 容器，检查是否为 ALAC 编码
            val path = uri.path?.lowercase().orEmpty()
            if (path.endsWith(".m4a") || path.endsWith(".mp4")) {
                val nativeProbe = NativeAudioProbe.probeUri(context, uri).getOrNull()
                if (nativeProbe != null && nativeProbe.formatName.equals("ALAC", ignoreCase = true)) {
                    return mapFromNativeProbe(nativeProbe, fallbackDisplayName)
                }
            }

            val title = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf(String::isNotBlank) ?: fallbackDisplayName.substringBeforeLast('.')
            val artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf(String::isNotBlank) ?: "未知创作者"
            val album = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val cover = reader.embeddedPicture

            val codec = when (mime) {
                "audio/flac" -> "FLAC"
                "audio/mpeg" -> "MP3"
                "audio/mp4a-latm" -> "AAC"
                "audio/x-wav", "audio/wav" -> "WAV"
                else -> mime.removePrefix("audio/").uppercase()
            }

            return ExtractedAudioMetadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                mimeType = mime.ifBlank { "audio/*" },
                formatName = codec,
                formatDescription = codec,
                sampleRate = 44100,
                bitDepth = 16,
                channelCount = 2,
                isDsd = false,
                coverBytes = cover,
            )
        } finally {
            runCatching { reader.release() }
        }
    }

    private fun extractViaNative(context: Context, uri: Uri, fallbackDisplayName: String): ExtractedAudioMetadata {
        val result = NativeAudioProbe.probeUri(context, uri).getOrThrow()
        return mapFromNativeProbe(result, fallbackDisplayName)
    }

    private fun mapFromNativeProbe(probe: NativeProbeMetadata, fallbackDisplayName: String): ExtractedAudioMetadata {
        val title = probe.title.ifBlank { fallbackDisplayName.substringBeforeLast('.') }
        val artist = probe.artist.ifBlank { "未知创作者" }
        val album = probe.album

        val desc = if (probe.isDsd) {
            val dsdName = if (probe.dsdRateMultiplier > 0) "DSD${probe.dsdRateMultiplier}" else probe.formatName
            com.musicone.nativeaudio.NativeAudioOutputPolicy.dsdPcmDescription(dsdName)
        } else {
            val rateText = if (probe.sampleRate > 0) {
                val khz = java.math.BigDecimal(probe.sampleRate).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()
                "${khz}kHz"
            } else ""
            "${probe.formatName} · ${probe.sourceBitDepth}bit / $rateText"
        }

        return ExtractedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = probe.durationMs,
            mimeType = if (probe.formatName == "AIFF") "audio/aiff" else "audio/x-native-${probe.formatName.lowercase()}",
            formatName = probe.formatName,
            formatDescription = desc,
            sampleRate = probe.sampleRate,
            bitDepth = probe.sourceBitDepth,
            channelCount = probe.channelCount,
            isDsd = probe.isDsd,
            coverBytes = probe.coverData,
        )
    }
}
