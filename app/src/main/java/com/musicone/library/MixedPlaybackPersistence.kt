package com.musicone

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject

/** 播放会话与浏览平台无关；首次兼容读取旧 QQ 快照，不覆盖旧数据。 */
internal class MixedPlaybackPersistence(context: Context) {
    private val preferences = context.getSharedPreferences("mixed_playback_snapshot", Context.MODE_PRIVATE)
    private val legacy = LegacyPlaybackSnapshotStore(context)
    private val lifetime = SupervisorJob()
    private val encoding = MixedPlaybackSnapshotEncoding()
    private val writer = PlaybackSnapshotWriter(CoroutineScope(lifetime + Dispatchers.IO)) { state, position ->
        try {
            encoding.contentIfChanged(state)?.let { preferences.edit().putString("snapshot", it).apply() }
            preferences.edit().putLong("position", position.coerceAtLeast(0)).apply()
        } catch (_: Exception) { android.util.Log.w("MusicPlayback", "保存混合播放进度失败") }
    }
    private var restored = false
    private var lastProgressBucket = -1L

    fun restore(source: MusicSource, state: MusicOneUiState): PlaybackRestore? {
        if (restored || state.currentTrack != null) return null
        restored = true
        val raw = preferences.getString("snapshot", null)
        val saved = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val legacySnapshot = if (saved == null) legacy.read() else null
        val next = if (saved != null) saved.toMixedPlaybackState(state) else legacySnapshot?.let { previous ->
            restorePlaybackSnapshot(previous.queue, previous.currentTrackId, previous.originalQueueIds,
                previous.shuffle, previous.repeatMode, previous.radioActive, state)
        }
        if (next == null) return null
        val current = requireNotNull(next.currentTrack)
        val position = preferences.getLong("position", legacySnapshot?.positionMs ?: 0L).coerceAtLeast(0L)
            .let { if (current.durationMs > 0) it.coerceAtMost(current.durationMs) else it }
        resetProgressBucket(position)
        return PlaybackRestore(next, position)
    }
    fun save(state: MusicOneUiState, position: Long) { if (state.currentTrack != null) writer.save(state, position) }
    // 只在明确清空时写入空快照，阻止重启后回退恢复旧队列。
    fun clearQueue() { writer.save(MusicOneUiState(), 0L); resetProgressBucket(0L) }
    fun saveProgressIfNeeded(state: MusicOneUiState, position: Long) {
        val bucket = position / 5_000L
        if (bucket != lastProgressBucket) { lastProgressBucket = bucket; save(state, position) }
    }
    fun resetProgressBucket(position: Long) { lastProgressBucket = position / 5_000L }
    fun close() { writer.close(); lifetime.complete() }
}
