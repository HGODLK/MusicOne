package com.musicone.source.webdav

import com.musicone.source.sdk.*
import com.musicone.source.webdav.internal.*

/**
 * WebDAV 媒体播放服务：
 * - 单档原始文件音质目录（SourceQualityCatalog）；
 * - 按曲目载荷定位所属服务器，解析真实地址与 Authorization 请求头；
 * - 精准声明 MIME Type，确保 DSD/APE/WavPack/ALAC 走宿主原生解码。
 */
internal class WebDavPlaybackService(
    private val auth: WebDavAuthentication,
    private val client: WebDavClient,
    private val metadataReader: WebDavMetadataReader,
) : SourceMediaPlayback, SourceQualityCatalog, SourcePlaybackBehavior {

    override val qualityCatalog: List<SourceQualityDefinition> = listOf(
        SourceQualityDefinition(
            id = WebDavMetadataReader.WEBDAV_QUALITY_ORIGINAL,
            label = "原始文件",
            compactLabel = "原声",
            description = "直接播放 WebDAV 服务器上的原始音频文件",
            standardQualityId = null,
        ),
    )

    override val defaultCatalogQualityId: String = WebDavMetadataReader.WEBDAV_QUALITY_ORIGINAL
    override val defaultQualityId: String = WebDavMetadataReader.WEBDAV_QUALITY_ORIGINAL
    override val renewOnPlayerErrorLimit: Int = 2

    override fun catalogFallbackQualityIds(preferredId: String): List<String> =
        listOf(WebDavMetadataReader.WEBDAV_QUALITY_ORIGINAL)

    override fun fallbackQualityIds(preferredId: String): List<String> =
        listOf(WebDavMetadataReader.WEBDAV_QUALITY_ORIGINAL)

    override fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?): String =
        WebDavMetadataReader.WEBDAV_QUALITY_ORIGINAL

    override suspend fun enrich(track: SourceTrackDetails): SourceTrackDetails {
        val payload = WebDavTrackPayload.parse(track.payload) ?: return track
        val server = resolveServer(payload, track) ?: return track
        val entry = WebDavEntry(
            path = payload.path,
            name = payload.fileName,
            isDirectory = false,
            sizeBytes = payload.sizeBytes,
            etag = payload.etag,
            lastModified = payload.lastModified,
            contentType = payload.contentType,
        )
        val lyricEntry = payload.lyricPath?.let { WebDavEntry(it, WebDavPaths.fileNameOf(it), false) }
        val coverEntry = payload.coverPath?.let { WebDavEntry(it, WebDavPaths.fileNameOf(it), false) }
        return metadataReader.enrichEntry(server, entry, lyricEntry, coverEntry, folderCoverUri = null)
    }

    override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> {
        val payload = WebDavTrackPayload.parse(track.payload)
        val mime = if (payload != null) {
            WebDavPaths.resolveMimeType(payload.fileName, payload.contentType, payload.detectedCodecMime)
        } else {
            "audio/*"
        }
        return listOf(
            SourceAudioQuality(
                id = WebDavMetadataReader.WEBDAV_QUALITY_ORIGINAL,
                label = "原始文件",
                mimeType = mime,
                available = true,
            ),
        )
    }

    override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource {
        val payload = WebDavTrackPayload.parse(request.track.payload)
            ?: throw SourceOperationException("TRACK_UNAVAILABLE", "缺少 WebDAV 文件路径信息")
        val server = resolveServer(payload, request.track)
            ?: throw SourceOperationException("AUTH_REQUIRED", "未找到对应的 WebDAV 服务器，请重新连接")
        if (server.expired) {
            throw SourceOperationException("AUTH_EXPIRED", "WebDAV 服务器凭据已失效，请重新登录")
        }

        val resolved = try {
            client.resolveMediaUrl(server, payload.path, probeNetwork = true)
        } catch (error: SourceOperationException) {
            if (error.code == "AUTH_EXPIRED") auth.markExpired(server.key)
            throw error
        }

        val mime = WebDavPaths.resolveMimeType(
            fileName = payload.fileName,
            serverContentType = resolved.contentType ?: payload.contentType,
            detectedCodecMime = payload.detectedCodecMime,
        )
        val format = WebDavPaths.formatLabel(payload.fileName, payload.detectedCodecMime)
        val cacheId = WebDavIdentity.cacheIdentity(server, payload.path, payload.etag, payload.sizeBytes)

        return SourcePlaybackResource(
            address = PlaybackAddress(
                url = resolved.url,
                headers = resolved.headers,
                mimeType = mime,
                cacheIdentity = cacheId,
            ),
            qualityId = WebDavMetadataReader.WEBDAV_QUALITY_ORIGINAL,
            format = format,
            accountId = server.accountId,
            actualFormat = format,
        )
    }

    override suspend fun recover(
        request: SourcePlaybackRequest,
        previous: SourcePlaybackResource,
    ): SourcePlaybackResource = resolve(request)

    override suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo> =
        tracks.map { track ->
            val payload = WebDavTrackPayload.parse(track.payload)
            val server = payload?.let { resolveServer(it, track) }
            when {
                payload == null -> TrackAccessInfo(playable = false, reason = "缺少文件信息")
                server == null -> TrackAccessInfo(playable = false, reason = "未连接对应 WebDAV 服务器")
                server.expired -> TrackAccessInfo(playable = false, reason = "服务器凭据已失效")
                else -> TrackAccessInfo(playable = true)
            }
        }

    private fun resolveServer(payload: WebDavTrackPayload, track: SourceTrackDetails): WebDavServer? {
        auth.findServer(payload.serverKey)?.let { return it }
        val accountId = track.track.identity.accountId
        if (!accountId.isNullOrBlank()) {
            auth.findServer(accountId)?.let { return it }
        }
        return auth.currentServer()
    }
}
