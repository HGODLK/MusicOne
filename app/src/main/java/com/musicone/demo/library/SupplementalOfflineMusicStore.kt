package com.musicone.demo

import android.content.Context
import android.util.Base64
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** DASH 只记录清单和音轨身份；离线恢复前重新检查整个音轨的缓存区间。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SupplementalOfflineMusicStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("supplemental_audio_index", Context.MODE_PRIVATE)
    private val session = BiliSessionStore(context)
    private fun owner() = session.read()?.mid.orEmpty()
    fun remember(playback: ResolvedPlayback) {
        val source = playback.source
        val key = source.cacheKey ?: return
        MusicDiskCache.get(context).trust(key, true)
        val reference = "${owner()}|${playback.track.id}|${cacheResourceVariant(key)}"
        preferences.edit().putString(reference, playback.track.toQqStoredTrackJson().put("cacheKey", key)
            .put("owner", owner()).put("manifest", source.url).put("quality", source.actualQuality.name)
            .put("bitrate", source.bitRate).put("format", source.format).toString()).apply()
    }
    private fun records(trackId: String? = null): List<Pair<String, JSONObject>> = preferences.all.mapNotNull { (key, value) ->
        val row = runCatching { JSONObject(value as String) }.getOrNull() ?: return@mapNotNull null
        if (row.optString("owner") != owner() || (trackId != null && row.optString("id") != trackId)) null else row.optString("cacheKey", key) to row
    }
    fun complete(key: String): Boolean {
        val cache = MusicDiskCache.get(context).audio
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }
    fun keys(track: MusicTrack) = records(track.id).map { it.first }.distinct()
        .filter { MusicDiskCache.get(context).audio.getCachedSpans(it).isNotEmpty() }
    fun savedTracks() = records().mapNotNull { it.second.toQqStoredTrack() }.distinctBy { it.id }
    fun cacheRecords(): List<Pair<String, JSONObject>> = records()
    fun completeTrackIds() = records().filter { complete(it.first) }.mapTo(hashSetOf()) { it.second.optString("id") }
    fun remove(track: MusicTrack) {
        records(track.id).forEach { (key, _) -> MusicDiskCache.get(context).removeAudio(key) }
        val edit = preferences.edit()
        preferences.all.forEach { (key, value) ->
            val row = runCatching { JSONObject(value as String) }.getOrNull()
            if (row?.optString("owner") == owner() && row.optString("id") == track.id) edit.remove(key)
        }
        edit.apply()
    }
    suspend fun resolve(track: MusicTrack, preferred: AudioQuality, allowOther: Boolean): ResolvedPlayback? = withContext(Dispatchers.IO) {
        records(track.id).sortedByDescending { it.second.optString("quality") == preferred.name }.firstNotNullOfOrNull { (key, row) ->
            val quality = AudioQuality.entries.firstOrNull { it.name == row.optString("quality") } ?: return@firstNotNullOfOrNull null
            if (!biliCachedQualityAllowed(preferred, quality, allowOther) ||
                !DolbyPlaybackPolicy.permits(quality) || !complete(key)) return@firstNotNullOfOrNull null
            runCatching {
                val xml = String(Base64.decode(row.getString("manifest").substringAfter(","), Base64.DEFAULT), Charsets.UTF_8)
                val offlineUrl = offlinePlaybackUrl(key).replace("&", "&amp;")
                val localXml = xml.replace(Regex("(<BaseURL[^>]*>).*?(</BaseURL>)", RegexOption.DOT_MATCHES_ALL)) { "${it.groupValues[1]}$offlineUrl${it.groupValues[2]}" }
                val url = "data:application/dash+xml;base64," + Base64.encodeToString(localXml.toByteArray(), Base64.NO_WRAP)
                SupplementalPlaybackRequests.register(url, emptyMap(), key)
                val saved = MusicLibraryStore.get(context).track(track.id) ?: row.toQqStoredTrack() ?: track
                ResolvedPlayback(saved.copy(previewUrl = url, lyrics = track.lyrics), PlaybackSource(url, preferred, quality,
                    row.optInt("bitrate"), row.optString("format"), false, cacheKey = key, mimeType = "application/dash+xml"))
            }.getOrNull()
        }
    }
}
