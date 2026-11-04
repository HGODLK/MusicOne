package com.musicone

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.*

/** 首帧播放之后再补充规格；切歌取消旧任务并校验媒体身份。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackDetailsLoader(private val context: Context, private val player: Player) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var requested: String? = null

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        job?.cancel()
        requested = null
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) return
        val config = player.currentMediaItem?.localConfiguration ?: return
        val url = config.uri.toString()
        if (url == requested) return
        requested = url
        val key = config.customCacheKey ?: return
        val expectedDuration = player.mediaMetadata.extras?.getLong("musicone_duration") ?: 0L
        job = scope.launch {
            delay(400)
            val info = try { withContext(Dispatchers.IO) {
                val cache = MusicDiskCache.get(context).audio
                if (cache.isCached(key, 0, 42)) {
                    val reader = CacheDataSource.Factory().setCache(cache).createDataSource()
                    try {
                        reader.open(DataSpec.Builder().setUri(config.uri).setKey(key).setLength(42).build())
                        val header = ByteArray(42)
                        var count = 0
                        while (count < header.size) {
                            val read = reader.read(header, count, header.size - count)
                            if (read < 0) break
                            count += read
                        }
                        flacMediaInfo(header.copyOf(count))
                    } finally { reader.close() }
                } else if (shouldReadFlacPlaybackDetails(url)) {
                    readFlacMediaInfo(url, 4_000, SupplementalPlaybackRequests.resource(url)?.requestHeaders.orEmpty())
                } else null
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (player.currentMediaItem?.localConfiguration?.uri?.toString() == url) {
                info?.let { PlaybackAudioInfo.remember(url, it.description) }
                if (SupplementalPlaybackRequests.resource(url)?.verificationPending == true) {
                    val duration = info?.durationMs ?: player.duration.takeIf { it > 0 }
                    if (duration != null && expectedDuration > 0) {
                        val full = mediaDurationMatches(duration, expectedDuration)
                        withContext(Dispatchers.IO) { OfflineMusicStore(context).verify(key, full) }
                        if (player.currentMediaItem?.localConfiguration?.uri?.toString() == url) PlaybackAudioInfo.verify(url, full)
                    }
                }
            }
        }
    }

    fun release() { scope.cancel() }
}

/** 新目录的标准映射不代表编码；只按实际资源声明补读 FLAC 参数。 */
internal fun shouldReadFlacPlaybackDetails(url: String): Boolean {
    val resource = SupplementalPlaybackRequests.resource(url)
    return resource?.mimeType.equals("audio/flac", true) || resource?.format.equals("flac", true) ||
        resolvedPlaybackQuality(url) in listOf(AudioQuality.LOSSLESS, AudioQuality.HI_RES)
}
