package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.channels.awaitClose

internal class PlaybackQualityPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("musicone_playback_quality", Context.MODE_PRIVATE)

    fun read(source: MusicSource): AudioQuality {
        val saved = preferences.getString(source.preferenceKey(), null)
            ?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } }
        if (saved == AudioQuality.HIGHER) return AudioQuality.EXHIGH
        if (saved == AudioQuality.DOLBY) return AudioQuality.LOSSLESS
        return saved ?: if (source == MusicSource.QQ) AudioQuality.EXHIGH else AudioQuality.LOSSLESS
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
