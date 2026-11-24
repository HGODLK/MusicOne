package com.musicone.source.webdav

import com.musicone.source.sdk.*
import com.musicone.source.webdav.internal.*

/**
 * WebDAV 歌词服务：优先读取扫描时记录的同目录同名 .lrc；若无记录也尝试按同目录同名 .lrc 路径直接获取。
 */
internal class WebDavLyricService(
    private val auth: WebDavAuthentication,
    private val client: WebDavClient,
) : SourceLyricProvider {

    override val parserVersion: Int = 1

    override suspend fun lyrics(track: SourceTrackDetails): SourceLyrics? {
        val payload = WebDavTrackPayload.parse(track.payload) ?: return null
        val server = auth.findServer(payload.serverKey)
            ?: track.track.identity.accountId?.let { auth.findServer(it) }
            ?: auth.currentServer()
            ?: return null

        val lrcPath = payload.lyricPath ?: deriveSiblingLrcPath(payload.path)
        val raw = client.fetchText(server, lrcPath) ?: return null
        return WebDavLyricsParser.parse(raw)
    }

    override fun timing(track: SourceTrackDetails): SourceLyricTiming = SourceLyricTiming(0L, 0L)

    private fun deriveSiblingLrcPath(audioPath: String): String {
        val parent = WebDavPaths.parentPath(audioPath)
        val stem = WebDavPaths.stemOf(WebDavPaths.fileNameOf(audioPath))
        return WebDavPaths.joinPath(parent, "$stem.lrc")
    }
}
