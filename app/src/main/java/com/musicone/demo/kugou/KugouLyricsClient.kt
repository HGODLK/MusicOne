package com.musicone.demo

import android.util.Base64
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 按酷狗歌词候选格式下载 KRC 或 LRC，不记录歌词地址和凭据。 */
internal class KugouLyricsClient {
    fun lyrics(track: MusicTrack, cookie: String): List<TimedLyric> {
        val hash = track.kugouHashFor(AudioQuality.STANDARD)
            ?: track.qualityIds.values.firstOrNull()
            ?: track.remoteId()
        val searchParams = linkedMapOf(
            "ver" to "1",
            "man" to "yes",
            "client" to "pc",
            "keyword" to track.title,
            "hash" to hash,
            "timelength" to track.durationMs.toString(),
            "lrctxt" to "1",
        )
        val headers = kugouLyricsHeaders()
        val search = JSONObject(PlatformHttp.get(
            kugouQueryUrl("https://lyrics.kugou.com/search", searchParams), cookie, headers,
        ).text)
        val candidate = search.optJSONArray("candidates")?.optJSONObject(0) ?: return emptyList()
        val id = candidate.opt("id")?.toString().orEmpty()
        val accessKey = candidate.optString("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return emptyList()
        var failure: Exception? = null
        for (format in kugouLyricFormats(candidate)) {
            try {
                val query = mapOf("ver" to "1", "client" to "pc", "id" to id,
                    "accesskey" to accessKey, "fmt" to format, "charset" to "utf8")
                val response = JSONObject(PlatformHttp.get(kugouQueryUrl(
                    "https://lyrics.kugou.com/download", query), cookie, headers).text)
                val content = response.optString("content")
                val actualFormat = response.optString("fmt").ifBlank { format }
                val lyrics = when (actualFormat.lowercase()) {
                    "krc" -> parseKugouKrcBase64(content)
                    "lrc" -> if (content.isBlank()) emptyList() else parseLrc(
                        runCatching { String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8) }
                            .getOrDefault(content),
                    )
                    else -> emptyList()
                }
                if (lyrics.isNotEmpty()) {
                    return lyrics.map { it.copy(parserVersion = CURRENT_KUGOU_LYRIC_PARSER_VERSION) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
        return emptyList()
    }
}

internal fun kugouLyricFormats(candidate: JSONObject): List<String> {
    val preferred = if (candidate.optInt("krctype") == 1 && candidate.optInt("contenttype") != 1) "krc" else "lrc"
    return listOf(preferred, if (preferred == "krc") "lrc" else "krc")
}

private fun kugouLyricsHeaders(): Map<String, String> = mapOf(
    "KG-RC" to "1",
    "KG-THash" to "expand_search_manager.cpp:852736169:451",
    "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
)
