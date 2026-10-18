package com.musicone.demo

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 设置及 USB 音频连接变化即时更新杜比限制，不改写用户保存的首选档位。 */
internal fun CoroutineScope.observeDolbyPlaybackRestrictions(onChanged: (Boolean) -> Unit) = launch {
    snapshotFlow { DolbyPlaybackPolicy.eligibility.allowed }.collect { onChanged(it) }
}
