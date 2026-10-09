package com.musicone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** 点击播放后到达的新图，等下一次返回和信息淡入完成再释放。 */
internal class PlaylistArtworkSynchronization {
    private var playbackKeys by mutableStateOf(emptySet<String>())
    private var revision = 0L
    private val displayed = mutableMapOf<String, List<ArtworkBlendSnapshot>>()
    fun holdPlayback(key: String) { revision++; playbackKeys = playbackKeys + key }
    fun held(key: String) = key in playbackKeys
    fun record(key: String, layers: List<ArtworkBlendSnapshot>) { displayed[key] = layers }
    fun snapshot(key: String) = displayed[key]
    fun forget(key: String) { displayed.remove(key) }
    suspend fun returned() {
        val requested = revision
        delay(300)
        if (requested == revision) playbackKeys = emptySet()
    }
}
