package com.musicone

import android.content.Context
import kotlinx.coroutines.channels.awaitClose

internal class PlaybackQualityPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("musicone_playback_quality", Context.MODE_PRIVATE)
    private val platforms = PlatformPreferences(context)
    private val packages = com.musicone.sourceintegration.SourcePackageController.get(context)

    fun read(source: MusicSource): AudioQuality {
        val saved = preferences.getString(source.preferenceKey(), null)
            ?.let { name -> AudioQuality.parse(name) }
        val playback = packages.running(source.id)?.entry?.mediaPlayback
        val main = if (playback?.followsMainQualityPreference == true) platforms.selectedSource().takeIf { it != source } else null
        val mainCapability = main?.let { mainPlaybackQualityCapabilityId(read(it)) }
        val catalog = playback as? com.musicone.source.sdk.SourceQualityCatalog
        if (catalog != null) {
            val normalized = if (saved != null || mainCapability != null)
                playback.normalizePreferredQuality(saved?.qualityId, mainCapability) else null
            return com.musicone.sourceintegration.sourceCatalogPreferred(source, catalog, saved, normalized)
        }
        val normalized = playback?.normalizePreferredQuality(saved?.qualityId, mainCapability)
        return com.musicone.sourceintegration.sourceAudioQuality(normalized.orEmpty()) ?: saved ?: AudioQuality.LOSSLESS
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
