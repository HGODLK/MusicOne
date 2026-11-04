package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.musicone.sourceintegration.SourcePackageController

/** 包移除与重导入只更新队列标记，不在安装状态变化时发起播放。 */
@Composable
internal fun rememberQueueSourceStatus(source: MusicSource): QueueSourceStatus {
    if (source == MusicSource.LOCAL) return QueueSourceStatus.AVAILABLE
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsState()
    val startup by controller.startup.collectAsState()
    val entry = installed.firstOrNull { it.id == source.id }
    return queueSourceStatus(source, controller.running(source.id) != null,
        installed = entry != null && !entry.removed, initializing = startup.initializing,
        pendingRemoval = entry?.pendingRemoval == true, removed = entry?.removed == true)
}
