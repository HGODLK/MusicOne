package com.musicone.demo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalPlaybackAdapter(private val context: Context) : PlatformPlaybackAdapter {
    override val source = MusicSource.LOCAL
    private val store = MusicLibraryStore.get(context)
    override suspend fun enrichTrack(track: MusicTrack): MusicTrack = withContext(Dispatchers.IO) { store.track(track.id) ?: track }
    override suspend fun resolve(track: MusicTrack, quality: AudioQuality): PlaybackSource = withContext(Dispatchers.IO) {
        val current = store.track(track.id) ?: track
        require(current.localUri.isNotBlank()) { "文件尚未关联，请重新选择原文件或目录" }
        try {
            val parsed = Uri.parse(current.localUri)
            if (parsed.scheme == "file") {
                val file = java.io.File(parsed.path.orEmpty())
                require(file.exists() && file.canRead()) { "无法打开音频文件" }
            } else {
                context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { }
                    ?: error("无法打开音频文件")
            }
        } catch (error: Exception) {
            store.unavailable(listOf(track.id))
            throw PlatformApiException("文件访问已失效，请重新关联原文件或目录")
        }
        // 已导入歌曲仍可能保存旧转换规格，展示由当前播放策略计算，不改动源文件。
        val storedFormat = current.mediaId
        val formatDesc = if (storedFormat.startsWith("DSD") && storedFormat.contains(" → PCM ")) {
            com.musicone.nativeaudio.NativeAudioOutputPolicy.dsdPcmDescription(storedFormat.substringBefore(" →"))
        } else storedFormat.takeIf { it.isNotBlank() }
        PlaybackSource(
            url = current.localUri,
            requestedQuality = quality,
            actualQuality = AudioQuality.STANDARD,
            bitRate = 0,
            format = formatDesc ?: "本地音频",
            trial = false,
            actualFormat = formatDesc,
        )
    }
    override suspend fun availableQualities(track: MusicTrack, onProgress: (List<AudioQuality>) -> Unit): List<AudioQuality> = emptyList()
    override suspend fun lyrics(track: MusicTrack): List<TimedLyric> = withContext(Dispatchers.IO) { store.lyrics(track) }
}
