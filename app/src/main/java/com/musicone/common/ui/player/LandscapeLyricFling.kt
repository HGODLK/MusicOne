package com.musicone

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

private class LandscapeLyricRelease { var velocityY: Float? = null }

internal class LyricFlingBinding(val modifier: Modifier, val behavior: FlingBehavior)

/** 旋转窗口只按列表本地坐标计算松手速度；不消费事件，拖动仍由 LazyColumn 处理。 */
@Composable
internal fun rememberLyricFlingBinding(): LyricFlingBinding {
    return rememberPlayerVerticalFling(LocalPhonePlayerRotation.current)
}

/** 菜单可能归零方向 Local，必须显式使用其实际旋转参数。 */
@Composable
internal fun rememberPlayerVerticalFling(rotation: Int): LyricFlingBinding {
    val original = ScrollableDefaults.flingBehavior()
    val release = remember(rotation) { LandscapeLyricRelease() }
    val behavior = remember(rotation, original, release) {
        if (rotation == 0) original else object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                // 普通顺序列表的滚动坐标与手指纵向速度相反。
                val velocity = release.velocityY?.let { -it } ?: initialVelocity
                release.velocityY = null
                return with(original) { performFling(velocity) }
            }
        }
    }
    val input = if (rotation == 0) Modifier else Modifier.pointerInput(release) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tracker = VelocityTracker()
            release.velocityY = null
            tracker.addPosition(down.uptimeMillis, down.position)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // 使用已转换的当前位置，避免混入旋转前坐标系的历史采样。
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    release.velocityY = tracker.calculateVelocity().y
                    break
                }
            } while (true)
        }
    }
    return LyricFlingBinding(input, behavior)
}
