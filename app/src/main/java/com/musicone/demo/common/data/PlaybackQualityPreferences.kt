package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.channels.awaitClose

internal class PlaybackQualityPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("musicone_playback_quality", Context.MODE_PRIVATE)

    fun read(source: MusicSource): AudioQuality {
        val saved = preferences.getString(source.preferenceKey(), null)
            ?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
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

/** B站只展示真实三档，历史 FLAC 首选归入 HiRes 高音质请求。 */
internal fun normalizedQualityPreference(source: MusicSource, saved: AudioQuality?): AudioQuality {
    if (source == MusicSource.BILIBILI) return when (saved) {
        AudioQuality.STANDARD, AudioQuality.HIGHER, AudioQuality.EXHIGH -> AudioQuality.STANDARD
        AudioQuality.DOLBY -> AudioQuality.DOLBY
        else -> AudioQuality.HI_RES
    }
    if (saved == AudioQuality.HIGHER) return AudioQuality.EXHIGH
    if (saved == AudioQuality.DOLBY && source != MusicSource.QQ) return AudioQuality.LOSSLESS
    return saved ?: if (source == MusicSource.QQ) AudioQuality.EXHIGH else AudioQuality.LOSSLESS
}
