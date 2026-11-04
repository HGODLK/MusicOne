package com.musicone

import android.content.Context
import com.musicone.sourceintegration.*
import android.util.Base64
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** DASH 只记录清单和音轨身份；离线恢复前重新检查整个音轨的缓存区间。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SupplementalOfflineMusicStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("supplemental_audio_index", Context.MODE_PRIVATE)
    private fun owner(source: MusicSource) = sourceAccountState(context, source)?.account?.id.orEmpty()
    private fun source(row: JSONObject) = runCatching { MusicSource.valueOf(row.optString("source")) }.getOrDefault(MusicSource.BILIBILI)
    fun remember(playback: ResolvedPlayback) {
        val source = playback.source
        val key = source.cacheKey ?: return
        MusicDiskCache.get(context).trust(key, true)
        val reference = "${playback.track.source.name}:${owner(playback.track.source)}|${playback.track.id}|${cacheResourceVariant(key)}"
        preferences.edit().putString(reference, playback.track.toStoredTrackJson().put("cacheKey", key)
            .put("owner", owner(playback.track.source)).put("manifest", source.url).putStoredQuality(source.actualQuality)
            .put("bitrate", source.bitRate).put("format", source.format).put("resourceMime", source.mimeType).toString()).apply()
    }
    private fun records(trackId: String? = null, requestedSource: MusicSource? = null): List<Pair<String, JSONObject>> = preferences.all.mapNotNull { (key, value) ->
        val row = runCatching { JSONObject(value as String) }.getOrNull() ?: return@mapNotNull null
        if ((requestedSource != null && source(row) != requestedSource) || row.optString("owner") != owner(source(row)) || (trackId != null && row.optString("id") != trackId)) null else row.optString("cacheKey", key) to row
    }
    fun complete(key: String): Boolean {
        val cache = MusicDiskCache.get(context).audio
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }
    fun recordedKeys(track: MusicTrack) = records(track.id, track.source).map { it.first }.distinct()
    fun keys(track: MusicTrack) = recordedKeys(track)
        .filter { MusicDiskCache.get(context).audio.getCachedSpans(it).isNotEmpty() }
    fun savedTracks(source: MusicSource? = null) = records(requestedSource = source).mapNotNull { it.second.toStoredTrack() }.distinctBy { it.id }
    fun cacheRecords(): List<Pair<String, JSONObject>> = records()
    fun completeTrackIds() = records().filter { complete(it.first) }.mapTo(hashSetOf()) { it.second.optString("id") }
    fun remove(track: MusicTrack) {
        records(track.id, track.source).forEach { (key, _) -> MusicDiskCache.get(context).removeAudio(key) }
        val edit = preferences.edit()
        preferences.all.forEach { (key, value) ->
            val row = runCatching { JSONObject(value as String) }.getOrNull()
            if (row != null && source(row) == track.source && row.optString("owner") == owner(track.source) && row.optString("id") == track.id) edit.remove(key)
        }
        edit.apply()
    }
    suspend fun resolve(track: MusicTrack, preferred: AudioQuality, allowOther: Boolean): ResolvedPlayback? = withContext(Dispatchers.IO) {
        records(track.id, track.source).sortedBy { (_, row) ->
            val quality = AudioQuality.parse(row.optString("quality"))
            if (quality == preferred) -1 else if (preferred.isCustom) preferred.fallbackCandidates(track.source).indexOf(quality).takeIf { it >= 0 } ?: Int.MAX_VALUE else 0
        }.firstNotNullOfOrNull { (key, row) ->
            val quality = row.storedQuality() ?: return@firstNotNullOfOrNull null
            if ((preferred.isCustom && quality !in preferred.fallbackCandidates(track.source)) || (!allowOther && preferred != quality) || (quality.capabilityQuality == AudioQuality.DOLBY && preferred.capabilityQuality != AudioQuality.DOLBY) ||
                !DolbyPlaybackPolicy.permits(quality) || !complete(key)) return@firstNotNullOfOrNull null
            runCatching {
                val xml = String(Base64.decode(row.getString("manifest").substringAfter(","), Base64.DEFAULT), Charsets.UTF_8)
                val offlineUrl = offlinePlaybackUrl(key).replace("&", "&amp;")
                val localXml = xml.replace(Regex("(<BaseURL[^>]*>).*?(</BaseURL>)", RegexOption.DOT_MATCHES_ALL)) { "${it.groupValues[1]}$offlineUrl${it.groupValues[2]}" }
                val url = "data:application/dash+xml;base64," + Base64.encodeToString(localXml.toByteArray(), Base64.NO_WRAP)
                SupplementalPlaybackRequests.register(url, emptyMap(), key)
                val saved = MusicLibraryStore.get(context).track(track.id) ?: row.toStoredTrack() ?: track
                val resource = PlaybackSource(url, preferred, quality, row.optInt("bitrate"), row.optString("format"), false,
                    cacheKey = key, mimeType = if (quality.isCustom) row.optString("resourceMime", "application/dash+xml") else "application/dash+xml")
                if (quality.isCustom && !DolbyPlaybackPolicy.permits(resource)) null
                else {
                    if (quality.isCustom) SupplementalPlaybackRequests.remember(resource)
                    ResolvedPlayback(saved.copy(previewUrl = url, lyrics = track.lyrics), resource)
                }
            }.getOrNull()
        }
    }
}
