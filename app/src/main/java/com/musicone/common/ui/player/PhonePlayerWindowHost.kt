package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp

internal val LocalPhonePlayerRotation = staticCompositionLocalOf { 0 }

/** 手机横屏显隐状态栏时保留原占位，底页与竖屏目标不随系统栏重新排版。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun playerStableStatusBarInsets(): WindowInsets =
    if (isPhonePlayerConfiguration(androidx.compose.ui.platform.LocalConfiguration.current))
        WindowInsets.statusBarsIgnoringVisibility else WindowInsets.statusBars

@Composable
internal fun Modifier.playerStableStatusBarsPadding(): Modifier =
    windowInsetsPadding(playerStableStatusBarInsets())

/** 窗口保持竖屏，仅交换播放子树的测量宽高并直接旋转其绘制及输入坐标。 */
@Composable
internal fun PhonePlayerWindowHost(rotationDegrees: Int, content: @Composable () -> Unit) {
    Layout(content = { Box(Modifier.fillMaxSize()) { content() } }, modifier = Modifier.fillMaxSize()) { children, limits ->
        val width = limits.maxWidth
        val height = limits.maxHeight
        val rotated = rotationDegrees != 0
        val child = children.single().measure(Constraints.fixed(
            if (rotated) height else width, if (rotated) width else height,
        ))
        layout(width, height) {
            child.placeWithLayer((width - child.width) / 2, (height - child.height) / 2) {
                transformOrigin = TransformOrigin.Center
                rotationZ = rotationDegrees.toFloat()
            }
        }
    }
}

/** 系统安全区仍属于竖屏窗口，进入横向画布时须同步转换四条边。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun phonePlayerLandscapePadding(rotationDegrees: Int, balanceHorizontal: Boolean = false): PaddingValues {
    val density = LocalDensity.current
    // 动画结束隐藏状态栏时仍保留同一安全距离，避免无挖孔设备的整块内容再次跳位。
    val safe = WindowInsets.safeDrawing.union(WindowInsets.statusBarsIgnoringVisibility)
        .union(WindowInsets.displayCutout).union(WindowInsets.waterfall)
    val left = safe.getLeft(density, LayoutDirection.Ltr)
    val right = safe.getRight(density, LayoutDirection.Ltr)
    val top = safe.getTop(density)
    val bottom = safe.getBottom(density)
    return with(density) {
        phonePlayerWindowPadding(left.toDp(), top.toDp(), right.toDp(), bottom.toDp(),
            rotationDegrees, balanceHorizontal)
    }
}

/** 左右横屏按同一映射转换；竖屏和平板保留原边距。返回值按绝对左到右读取。 */
internal fun phonePlayerWindowPadding(left: Dp, top: Dp, right: Dp, bottom: Dp,
    rotationDegrees: Int, balanceHorizontal: Boolean = false): PaddingValues {
    if (rotationDegrees == 0) return PaddingValues(left, top, right, bottom)
    // 摄像头落在横向画布侧边时，两侧取相同安全距离，避免整页视觉偏向一边。
    val side = maxOf(top, bottom)
    return if (balanceHorizontal) PaddingValues(side,
        if (rotationDegrees > 0) right else left, side,
        if (rotationDegrees > 0) left else right)
    else if (rotationDegrees > 0) PaddingValues(top, right, bottom, left)
    else PaddingValues(bottom, left, top, right)
}
