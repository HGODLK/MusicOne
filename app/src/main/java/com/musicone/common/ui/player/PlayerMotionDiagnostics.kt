package com.musicone

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot

/** 本轮真机复现日志：不新增帧驱动、不订阅绘制状态，也不输出歌曲、账号或凭证。 */
internal object PlayerMotionDiagnostics {
    const val enabled = false
    private const val TAG = "MusicOneMotionDiag"
    private val samples = LinkedHashMap<String, Long>()
    private val changes = LinkedHashMap<String, String>()
    private val tracks = LinkedHashMap<TrackThemeIdentity, Int>()
    private var sequence = 0
    private var trackSequence = 0

    @Synchronized fun track(identity: TrackThemeIdentity?): Int {
        if (!enabled) return 0
        if (identity == null) return 0
        return tracks.getOrPut(identity) { ++trackSequence }.also { trim(tracks) }
    }

    @Synchronized fun event(name: String, details: () -> String) {
        if (!enabled) return
        Snapshot.withoutReadObservation {
            Log.i(TAG, "v4 #${++sequence} t=${SystemClock.uptimeMillis()} $name ${details()}")
        }
    }

    @Synchronized fun changed(key: String, details: () -> String) {
        if (!enabled) return
        Snapshot.withoutReadObservation {
            val value = details()
            if (changes.put(key, value) != value) event(key) { value }
            trim(changes)
        }
    }

    @Synchronized fun sample(key: String, details: () -> String) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        val previous = samples[key]
        if (previous != null && now - previous < 100L) return
        samples[key] = now
        trim(samples)
        changed(key, details)
    }

    private fun <K, V> trim(map: LinkedHashMap<K, V>) {
        if (map.size > 128) map.remove(map.keys.first())
    }
}
