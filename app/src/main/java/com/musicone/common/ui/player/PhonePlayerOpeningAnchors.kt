package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 仅协调本轮横屏目标批次，不持有开合进度，也不随切歌重开事务。 */
internal class PhonePlayerOpeningAnchors {
    val layers = PlayerOpeningLayerPreparation()
    var generation by mutableIntStateOf(0)
        private set
    private var rotation = 0
    private var owner: Any? = null
    private var committedGeneration = -1
    private var committedAnchors: Map<String, MotionAnchor> = emptyMap()
    private var refresh: (() -> Unit)? = null

    fun begin(rotation: Int) {
        generation++
        layers.begin()
        this.rotation = rotation
        committedGeneration = -1
    }

    fun attach(owner: Any, refresh: () -> Unit) { this.owner = owner; this.refresh = refresh }
    fun detach(owner: Any) {
        if (this.owner === owner) { this.owner = null; refresh = null }
    }

    fun publish(generation: Int, rotation: Int, anchors: Map<String, MotionAnchor>, motion: PageMotion): Boolean {
        if (generation != this.generation || rotation != this.rotation ||
            !anchors.keys.containsAll(Keys)) return false
        if (!Keys.all { key -> anchors[key]?.let { validTarget(key, it, motion.hostBounds) } == true }) return false
        motion.targets.putAll(anchors)
        committedAnchors = Keys.mapNotNull { key -> motion.targets[key]?.let { key to it } }.toMap()
        committedGeneration = generation
        return true
    }

    fun ready(motion: PageMotion): Boolean {
        if (rotation == 0) return true
        refresh?.invoke()
        return committedGeneration == generation && Keys.all {
            motion.targets[it] === committedAnchors[it] &&
                motion.targets[it]?.let { anchor -> validTarget(it, anchor, motion.hostBounds) } == true
        } && layers.ready(IntSize(motion.hostBounds.width.roundToInt(), motion.hostBounds.height.roundToInt()))
    }

    private fun validTarget(key: String, anchor: MotionAnchor, host: Rect): Boolean {
        if (anchorFits(anchor.bounds, host)) return true
        val bounds = anchor.bounds
        // 已测量的空歌手等文字允许零面积；它没有飞行字形，不能被误当成布局尚未登记。
        return key != "cover" && host.width > 1f && host.height > 1f &&
            bounds.width >= 0f && bounds.height >= 0f && (bounds.width <= 1f || bounds.height <= 1f) &&
            bounds.left >= host.left - 1f && bounds.top >= host.top - 1f &&
            bounds.right <= host.right + 1f && bounds.bottom <= host.bottom + 1f
    }

    companion object { val Keys = setOf("cover", "title", "subtitle") }
}

/** 使用横向画布的一次真实坐标变换，不读取 Turn 内层的补偿坐标。 */
internal fun phoneLandscapePhysicalAnchor(anchor: MotionAnchor, localToRoot: (Offset) -> Offset): MotionAnchor {
    val rect = anchor.bounds
    val points = arrayOf(localToRoot(rect.topLeft), localToRoot(rect.topRight),
        localToRoot(rect.bottomLeft), localToRoot(rect.bottomRight))
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    points.forEach { left = min(left, it.x); top = min(top, it.y); right = max(right, it.x); bottom = max(bottom, it.y) }
    return anchor.copy(bounds = Rect(left, top, right, bottom))
}

/** 延迟重读同一画布，宿主首帧坐标登记晚于子布局时也不会误用旧方向批次。 */
internal class PhoneLandscapeAnchorLayout {
    var coordinates: LayoutCoordinates? = null
    var localAnchors: Map<String, MotionAnchor> = emptyMap()
    var size: IntSize = IntSize.Zero
    fun physicalAnchors(rotation: Int, host: Rect): Map<String, MotionAnchor>? {
        val coordinates = coordinates?.takeIf { it.isAttached && it.size == size } ?: return null
        // Compose 根节点可能包含系统栏，播放宿主则有独立边界，不能要求二者同尺寸。
        if (host.width <= 1f || host.height <= 1f) return null
        if (!localAnchors.keys.containsAll(PhonePlayerOpeningAnchors.Keys)) return null
        val origin = coordinates.playerPointInRoot(Offset.Zero)
        val axis = coordinates.playerPointInRoot(Offset(1f, 0f)) - origin
        val expected = phonePlayerFlightRotate(Offset(1f, 0f), Offset.Zero, rotation.toFloat())
        if (!phoneLandscapeCanvasMatchesRotation(axis, expected)) return null
        return localAnchors.mapValues { (_, anchor) -> phoneLandscapePhysicalAnchor(anchor, coordinates::playerPointInRoot) }
    }
}

/** 新代次不能借旧画布尚未更新的坐标提前通过；检查实际基向量而非目标变量。 */
internal fun phoneLandscapeCanvasMatchesRotation(axis: Offset, expected: Offset): Boolean {
    val length = hypot(axis.x, axis.y)
    return length > .001f && abs(axis.x / length - expected.x) < .001f &&
        abs(axis.y / length - expected.y) < .001f
}
