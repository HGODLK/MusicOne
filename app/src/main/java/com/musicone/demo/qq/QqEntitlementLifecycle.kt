package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** 手机和平板共用前台恢复入口，不依赖当前停留的页面或歌曲行是否重新挂载。 */
@Composable
internal fun QqEntitlementLifecycle(source: MusicSource, refreshSession: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (source == MusicSource.QQ) {
            refreshSession()
            QqEntitlements.refreshOnForeground()
        }
    }
}
