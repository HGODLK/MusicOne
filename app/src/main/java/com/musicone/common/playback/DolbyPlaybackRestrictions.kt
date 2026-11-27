package com.musicone

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 设置或 USB 音频连接变化时实时更新杜比可用状态，但不改动用户保存的首选音质。 */
internal fun CoroutineScope.observeDolbyPlaybackRestrictions(onChanged: (Boolean) -> Unit) = launch {
    snapshotFlow { DolbyPlaybackPolicy.eligibility.allowed }.collect { onChanged(it) }
}
