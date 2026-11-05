package com.musicone.source.subsonic

import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.*

/**
 * 优先用 OpenSubsonic 的 getLyricsBySongId（带时间轴、可含多语言）；
 * 不支持时退回旧接口 getLyrics，文本是 LRC 才使用，纯文本不伪造同步。
 */
internal class SubsonicLyricService(
    private val requests: SubsonicRequests,
    private val client: SubsonicClient,
) : SourceLyricProvider {
    override val parserVersion = SUBSONIC_LYRIC_PARSER_VERSION

    override suspend fun lyrics(track: SourceTrackDetails): SourceLyrics? {
        val ref = track.subsonicRef()
        return requests.forServer(ref.serverKey) { server ->
            val lines = structuredLyrics(server, ref.id) ?: legacyLyrics(server, track)
            lines?.takeIf { it.isNotEmpty() }?.let { values ->
                SourceLyrics(values.map { line ->
                    SourceLyricLine(line.startMs, line.text, line.words.map { SourceLyricWord(it.startMs, it.durationMs, it.text) },
                        translation = line.translation, parserVersion = SUBSONIC_LYRIC_PARSER_VERSION)
                })
            }
        }
    }

    private fun structuredLyrics(server: SubsonicServer, songId: String): List<ParsedLyricLine>? {
        if (!server.supportsStructuredLyrics) return null
        return try { parseStructuredLyrics(client.get(server, "getLyricsBySongId", listOf("id" to songId))) }
        catch (error: SubsonicApiException) { if (error.code == 40) throw error else null }
    }

    private fun legacyLyrics(server: SubsonicServer, track: SourceTrackDetails): List<ParsedLyricLine>? {
        val artist = track.track.artists.firstOrNull() ?: track.artistsText
        if (track.track.title.isBlank()) return null
        val value = try {
            client.get(server, "getLyrics", listOf("artist" to artist, "title" to track.track.title)).optJSONObject("lyrics")
        } catch (error: SubsonicApiException) { if (error.code == 40) throw error else null }
        // 旧接口按歌名和歌手模糊匹配，可能取到别的歌；返回的歌名明显不一致时不用。
        val title = value?.optString("title").orEmpty()
        if (title.isNotBlank() && !title.equals(track.track.title, ignoreCase = true)) return null
        return parseLrc(value?.optString("value").orEmpty())
    }

    override fun timing(track: SourceTrackDetails) = SourceLyricTiming(SUBSONIC_LINE_LEAD_MS, 0)
}

internal const val SUBSONIC_LINE_LEAD_MS = 200L
