package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 只负责猜你喜欢的增量取歌，播放队列状态仍由播放器统一管理。 */
internal class QqRadioRepository(context: Context) {
    private val preferences = PlatformPreferences(context)
    private val api = QqApiClient()

    suspend fun load(existingIds: Set<String>, targetSize: Int): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            if (targetSize <= 0) return@withContext emptyList()
            val session = preferences.readSession(MusicSource.QQ)
            val additions = mutableListOf<MusicTrack>()
            repeat(RADIO_REQUEST_COUNT) {
                additions += api.radioTracks(
                    cookie = session.credential,
                    count = targetSize,
                    hasVipAccess = session.account?.hasVipAccess == true,
                ).filterNot { track -> track.id in existingIds || additions.any { it.id == track.id } }
                if (additions.size >= targetSize) return@withContext additions.take(targetSize)
            }
            additions.take(targetSize)
        }

    private companion object {
        const val RADIO_REQUEST_COUNT = 4
    }
}
