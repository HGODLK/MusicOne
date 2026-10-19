package com.musicone.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 只在设置可见时查询，不触发编译，也不将等待系统调度写成正在编译。 */
@Composable
internal fun SettingsOptimizationNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible by produceState(false, context, lifecycle) {
        val reader = ProfileOptimizationStatus(context)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = reader.isPending()
                delay(5_000L)
            }
        }
    }
    if (visible) Text(
        text = "应用优化尚未完成，优化期间可能短暂发热或卡顿。",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
