package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates

/** 只供无订阅诊断回调读取，区分边界缓存与当前坐标；不参与任何显示判断。 */
internal fun lyricBoundaryDiagnostic(
    alphas: List<Float>, bands: List<Pair<Float, Float>>, boundary: LyricLineBoundary,
    root: LayoutCoordinates?, child: LayoutCoordinates?,
): String {
    val origin = if (root?.isAttached == true && child?.isAttached == true) {
        runCatching { root.localPositionOf(child, Offset.Zero) }.getOrNull()
    } else null
    val screenBounds = if (child?.isAttached == true) runCatching {
        var screen: LayoutCoordinates = child
        while (true) screen = screen.parentLayoutCoordinates ?: break
        "unclipped=${screen.localBoundingBoxOf(child, clipBounds = false)} " +
            "clipped=${screen.localBoundingBoxOf(child, clipBounds = true)} " +
            "textCoordinates={${phoneCoordinateDiagnostic(child)}}"
    }.getOrElse { "boundsError=${it.javaClass.simpleName}" } else "detached"
    return "alphas=$alphas bands=$bands rowTop=${boundary.rowTop()} viewport=${boundary.viewportHeight()} " +
        "buffer=${boundary.window.topBuffer} shift=${boundary.window.offset()} " +
        "root=${root?.let(System::identityHashCode)} attached=${root?.isAttached} " +
        "child=${child?.let(System::identityHashCode)} attached=${child?.isAttached} origin=$origin $screenBounds"
}
