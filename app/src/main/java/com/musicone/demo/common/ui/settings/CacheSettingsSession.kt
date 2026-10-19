package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState

/** 返回设置首页和设置内部切页不提交，关闭动画结束后才应用缓存配置。 */
@Composable
internal fun ApplyCacheSettingsOnExit(destination: SettingsDestination, mounted: Boolean, apply: () -> Unit) {
    val latestApply = rememberUpdatedState(apply)
    LaunchedEffect(destination, mounted) {
        if (destination == SettingsDestination.CLOSED && !mounted) latestApply.value()
    }
}
