package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * 回到前台时变化一次的绘制键。手动录制的 GraphicsLayer 只在所属节点重绘时才重录，
 * 部分机型（如华为）长时间息屏后会回收显示列表，亮屏只重放外层就会画成空白；
 * 在录制处的绘制阶段读取它，回到前台时强制重录一次。
 */
@Composable
internal fun rememberLayerRefreshKey(): State<Int> {
    val key = remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { key.intValue++ }
    return key
}
