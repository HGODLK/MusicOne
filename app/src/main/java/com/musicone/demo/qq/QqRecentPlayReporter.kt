package com.musicone.demo

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 每次播放代次仅上报一次，信息流补齐身份后允许再次尝试。 */
internal class QqRecentPlayReporter(
    private val scope: CoroutineScope,
    private val report: suspend (MusicTrack) -> Unit,
) {
    private val reported = mutableSetOf<Long>()
    fun report(track: MusicTrack, generation: Long) {
        if (track.source != MusicSource.QQ || !track.hasQqRecentPlayIdentity() || !reported.add(generation)) return
        scope.launch {
            runCatching { report(track) }.onFailure { Log.w("QqRecentPlay", "最近播放上报失败", it) }
        }
    }
}
