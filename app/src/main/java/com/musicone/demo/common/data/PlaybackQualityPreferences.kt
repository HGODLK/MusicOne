package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.channels.awaitClose

internal class PlaybackQualityPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("musicone_playback_quality", Context.MODE_PRIVATE)
    private val platforms = PlatformPreferences(context)

    fun read(source: MusicSource): AudioQuality {
        val saved = preferences.getString(source.preferenceKey(), null)
            ?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
        // B站是补充音源，没有独立设置入口；跟随当前主音源的首选档。
        if (source == MusicSource.BILIBILI) {
            val main = platforms.selectedSource()
            if (main != MusicSource.BILIBILI) return biliPreferredFromMain(read(main))
        }
        return normalizedQualityPreference(source, saved)
    }

    fun save(source: MusicSource, quality: AudioQuality) {
        preferences.edit().putString(source.preferenceKey(), quality.name).apply()
    }

    fun observe(source: MusicSource): kotlinx.coroutines.flow.Flow<AudioQuality> = kotlinx.coroutines.flow.callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == source.preferenceKey()) trySend(read(source))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(source))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun MusicSource.preferenceKey(): String = "preferred_${name.lowercase()}"
}

/** B站没有独立设置入口，按主音源首选映射：MP3H/MP3L 为普通，Dolby/HiRes/FLAC 优先杜比。 */
internal fun biliPreferredFromMain(main: AudioQuality): AudioQuality =
    if (main.ordinal >= AudioQuality.LOSSLESS.ordinal) AudioQuality.DOLBY else AudioQuality.STANDARD

/** B站只有标准/高音质/Dolby 三档；除标准外都按 HiRes 请求，避免旧 320 设置退成普通。 */
internal fun normalizedQualityPreference(source: MusicSource, saved: AudioQuality?): AudioQuality {
    if (source == MusicSource.BILIBILI) return when (saved) {
        AudioQuality.STANDARD -> AudioQuality.STANDARD
        AudioQuality.DOLBY -> AudioQuality.DOLBY
        else -> AudioQuality.HI_RES
    }
    if (saved == AudioQuality.HIGHER) return AudioQuality.EXHIGH
    if (saved == AudioQuality.DOLBY && source != MusicSource.QQ) return AudioQuality.LOSSLESS
    return saved ?: if (source == MusicSource.QQ) AudioQuality.EXHIGH else AudioQuality.LOSSLESS
}
