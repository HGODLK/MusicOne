package org.example.simple

import com.musicone.source.sdk.*
import java.net.URI

/** 用户提供可访问的音频直链；真实平台应改用稳定内容 ID 及自己的解析协议。 */
class SimpleExampleSource : SimpleSupplementalSourceEntry(SimpleSupplementalSourceInfo(
    id = BuildConfig.SOURCE_ID,
    name = BuildConfig.SOURCE_NAME,
    versionCode = BuildConfig.VERSION_CODE.toLong(),
    versionName = BuildConfig.VERSION_NAME,
)) {
    override suspend fun parseInput(input: String): List<SourceTrackDetails> {
        val uri = URI(input.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "请输入 HTTPS 音频直链" }
        val url = uri.toASCIIString()
        // 此示例只接受稳定直链；临时签名地址在真实音源中应放入私有载荷。
        val identity = TrackIdentity(descriptor.id, null, url)
        val title = uri.path.substringAfterLast('/').ifBlank { "直链音频" }
        return listOf(SourceTrackDetails(
            track = SourceTrack(identity, title), stableId = url,
            payload = SourcePayload("example.direct-audio", 1, url),
        ))
    }

    override suspend fun resolvePlayback(request: SourcePlaybackRequest): SourcePlaybackResource {
        val track = request.track
        require(track.payload.format == "example.direct-audio" && track.payload.version == 1) { "不支持的音频内容" }
        return SourcePlaybackResource(
            address = PlaybackAddress(track.payload.encoded, cacheIdentity = track.stableId),
            qualityId = request.qualityId ?: "default",
            format = "",
        )
    }
}
