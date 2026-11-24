package com.musicone.sourceintegration

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.musicone.*
import com.musicone.source.sdk.SourcePlayerOpening
import com.musicone.source.sdk.SourcePlayerSharedElement
import com.musicone.source.sdk.SourcePlayerSharedScale
import com.musicone.source.sdk.SourcePlayerSharedSource

internal fun SourcePlayerSharedElement.sourceAnchorKey(): String = when (this) {
    SourcePlayerSharedElement.ARTWORK -> "cover"
    SourcePlayerSharedElement.TITLE -> "title"
    SourcePlayerSharedElement.ARTIST -> "subtitle"
    SourcePlayerSharedElement.PLAY_PAUSE -> "play"
}

/** 未声明时文字类元素等比缩放，避免迷你条与播放页字号、字数比例不同造成变形。 */
internal fun SourcePlayerOpening.scaleOf(element: SourcePlayerSharedElement): SourcePlayerSharedScale =
    sharedScale[element] ?: when (element) {
        SourcePlayerSharedElement.TITLE, SourcePlayerSharedElement.ARTIST -> SourcePlayerSharedScale.UNIFORM
        else -> SourcePlayerSharedScale.STRETCH
    }

/** 逐帧更新的裁剪轮廓；用值类型让图层在矩形或圆角变化时重新生成轮廓。 */
internal data class SourcePlayerClipShape(val rect: Rect, val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        if (corner > 0f) Outline.Rounded(RoundRect(rect, CornerRadius(corner))) else Outline.Rectangle(rect)
}

/** 根坐标（含 motion 根偏移）区域换算到页面坐标；手机横屏画布已旋转，按四角取包围盒。 */
internal fun sourcePlayerRootRectToPage(page: LayoutCoordinates?, root: Rect, rootOffset: Offset): Rect? {
    if (page == null || !page.isAttached) return null
    val rootCoordinates = page.findRootCoordinates()
    val corners = listOf(root.topLeft, root.topRight, root.bottomLeft, root.bottomRight)
        .map { page.localPositionOf(rootCoordinates, it - rootOffset) }
    return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
}

internal fun sourcePlayerLerpRect(from: Rect, to: Rect, progress: Float): Rect = Rect(
    motionLerp(from.left, to.left, progress), motionLerp(from.top, to.top, progress),
    motionLerp(from.right, to.right, progress), motionLerp(from.bottom, to.bottom, progress))

/**
 * 关闭整页淡入时，页面从迷你条表面按圆角展开、收起时收回，与原生播放页同一套圆角曲线。
 * 共享元素与裁剪区域按同一进度线性插值，两端都在区域内的元素全程不会被裁掉。
 */
@Composable
internal fun Modifier.sourcePlayerSurfaceReveal(motion: PageMotion, enabled: Boolean): Modifier {
    if (!enabled) return this
    val rootOffset = LocalMotionRootOffset.current
    val page = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { page[0] = it }.graphicsLayer {
        val progress = motion.value.coerceIn(0f, 1f)
        val source = (motion.sourceSnapshot["surface"] ?: motion.sources["surface"])?.bounds
        val from = if (motion.moving && progress < 1f && source != null)
            sourcePlayerRootRectToPage(page[0], source, rootOffset) else null
        if (from != null) {
            shape = SourcePlayerClipShape(sourcePlayerLerpRect(from, Rect(Offset.Zero, size), progress),
                playerSurfaceCorner(progress).dp.toPx())
            clip = true
        } else {
            shape = RectangleShape
            clip = false
        }
    }
}

/** 共享元素单帧姿态：位移、旋转、缩放都在目标元素自身坐标中；裁剪宽度与圆角同为未缩放的本地值。 */
internal data class SourcePlayerSharedPose(val translation: Offset, val rotation: Float, val scaleX: Float,
    val scaleY: Float, val clipWidth: Float?, val cornerPx: Float)

internal fun sourcePlayerSharedPose(source: Rect, sourceCornerPx: Float, origin: Offset, targetSize: Size,
    rotation: Int, progress: Float, scale: SourcePlayerSharedScale, targetCornerPx: Float?): SourcePlayerSharedPose {
    val rest = 1f - progress
    val delta = source.topLeft - origin
    // 绘制层位移属于横向内容坐标，源端仍在竖屏物理窗口中。
    val local = when (rotation) {
        90 -> Offset(delta.y, -delta.x)
        -90 -> Offset(-delta.y, delta.x)
        else -> delta
    }
    val heightRatio = source.height / targetSize.height
    val scaleX: Float
    val scaleY: Float
    var clipWidth: Float? = null
    if (scale == SourcePlayerSharedScale.UNIFORM) {
        scaleX = 1f + (heightRatio - 1f) * rest
        scaleY = scaleX
        // 起点只露出迷你条文字宽度，随进度展开到包页面全宽。
        val visible = motionLerp(source.width / heightRatio.coerceAtLeast(.001f), targetSize.width, progress)
        if (visible < targetSize.width) clipWidth = visible
    } else {
        scaleX = 1f + (source.width / targetSize.width - 1f) * rest
        scaleY = 1f + (heightRatio - 1f) * rest
    }
    val corner = targetCornerPx?.let {
        motionLerp(sourceCornerPx, it, progress) / minOf(scaleX, scaleY).coerceAtLeast(.001f)
    } ?: 0f
    return SourcePlayerSharedPose(local * rest, -rotation * rest, scaleX, scaleY, clipWidth, corner)
}

/**
 * 迷你条副本与包元素同框：包元素按共享姿态变换，副本以源端尺寸布局，缩放到同一视觉框。
 * 等比缩放的文字两轴都按高度比，避免字形被拉伸。
 */
internal fun sourcePlayerBarCopyScale(shared: SourcePlayerSharedPose, source: Size, target: Size,
    scale: SourcePlayerSharedScale): Pair<Float, Float> {
    val y = target.height * shared.scaleY / source.height.coerceAtLeast(.001f)
    val x = if (scale == SourcePlayerSharedScale.UNIFORM) y
        else target.width * shared.scaleX / source.width.coerceAtLeast(.001f)
    return x to y
}

/**
 * 变换包自己绘制的内容，不用宿主字体、图标或封面替代目标页面。
 * barCopy 非空时另画一份迷你条样式副本，与包元素同轨飞行：副本 1-p 淡出、包元素 p 淡入，与原生飞行控件一致。
 */
@Composable
internal fun SourcePlayerSharedContent(motion: PageMotion, readiness: SourcePlayerLayoutReadiness,
    element: SourcePlayerSharedElement, rotation: Int, enabled: Boolean, opening: SourcePlayerOpening,
    modifier: Modifier, barCopy: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val key = element.sourceAnchorKey()
    val rootOffset = LocalMotionRootOffset.current
    val rootView = LocalView.current
    var targetOrigin by remember { mutableStateOf<Offset?>(null) }
    var targetSize by remember { mutableStateOf(IntSize.Zero) }
    // 不飞回时，松手收起保留当前共享元素姿态；声明飞回时收起沿同一进度回到迷你条。
    val pose = remember(motion, opening.sharedOnClose) { derivedStateOf {
        if (motion.wantsOpen || opening.sharedOnClose) motion.value.coerceIn(0f, 1f) else null
    } }
    val retainedProgress = remember { floatArrayOf(1f) }
    val scale = opening.scaleOf(element)
    val owner = remember { Any() }
    DisposableEffect(readiness, element) {
        onDispose { readiness.remove(element, owner) }
    }
    Box(modifier.onGloballyPositioned {
        val point = it.localToRoot(Offset.Zero)
        // 包页面有独立 ComposeView；它的根坐标不包含外层手机画布的物理旋转。
        targetOrigin = sourcePlayerSharedTargetOrigin(point, rootOffset,
            IntSize(rootView.width, rootView.height), rotation)
        targetSize = it.size
        if (sourcePlayerSharedTargetValid(it.size)) readiness.publish(element, owner)
        else readiness.remove(element, owner)
    }, propagateMinConstraints = true) {
    Box(Modifier.graphicsLayer {
        val progress = pose.value?.also { retainedProgress[0] = it } ?: retainedProgress[0]
        val source = motion.sourceSnapshot[key] ?: motion.sources[key]
        val origin = targetOrigin
        if (enabled && source != null && origin != null && targetSize.width > 0 && targetSize.height > 0) {
            val targetCorner = if (element == SourcePlayerSharedElement.ARTWORK) opening.artworkCornerDp.dp.toPx() else null
            val shared = sourcePlayerSharedPose(source.bounds, source.corner.dp.toPx(), origin,
                Size(targetSize.width.toFloat(), targetSize.height.toFloat()), rotation, progress, scale, targetCorner)
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = shared.translation.x
            translationY = shared.translation.y
            rotationZ = shared.rotation
            scaleX = shared.scaleX
            scaleY = shared.scaleY
            // 静止后不再裁剪，包页面自己的圆角与绘制不受影响。
            val clipping = progress < 1f && (shared.clipWidth != null || shared.cornerPx > 0f)
            shape = if (clipping) SourcePlayerClipShape(Rect(0f, 0f, shared.clipWidth ?: size.width, size.height),
                shared.cornerPx) else RectangleShape
            clip = clipping
            alpha = if (barCopy != null && progress < 1f) progress else 1f
        } else alpha = 1f
    }) { content() }
    if (enabled && barCopy != null) {
        // 副本按迷你条源端尺寸布局，但只按父级最小约束占位，不能撑大包元素的布局；逐帧姿态留在图层参数块。
        val anchor = motion.sourceSnapshot[key] ?: motion.sources[key]
        if (anchor != null && anchor.bounds.width >= 1f && anchor.bounds.height >= 1f) Box(Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(anchor.bounds.width.roundToInt(),
                    anchor.bounds.height.roundToInt()))
                layout(constraints.minWidth, constraints.minHeight) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val progress = pose.value ?: retainedProgress[0]
                val source = motion.sourceSnapshot[key] ?: motion.sources[key]
                val origin = targetOrigin
                if (source == null || origin == null || targetSize.width <= 0 || targetSize.height <= 0 || progress >= 1f) {
                    alpha = 0f
                    return@graphicsLayer
                }
                val target = Size(targetSize.width.toFloat(), targetSize.height.toFloat())
                val shared = sourcePlayerSharedPose(source.bounds, source.corner.dp.toPx(), origin,
                    target, rotation, progress, scale, null)
                val (copyX, copyY) = sourcePlayerBarCopyScale(shared, source.bounds.size, target, scale)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = shared.translation.x
                translationY = shared.translation.y
                rotationZ = shared.rotation
                scaleX = copyX
                scaleY = copyY
                alpha = 1f - progress
            }) { barCopy() }
    }
    }
}

internal fun sourcePlayerSharedTargetValid(size: IntSize): Boolean = size.width > 0 && size.height > 0

internal fun sourcePlayerSharedTargetOrigin(point: Offset, rootOffset: Offset, rootSize: IntSize,
    rotation: Int): Offset = rootOffset + when (rotation) {
    90 -> Offset(rootSize.height.toFloat() - point.y, point.x)
    -90 -> Offset(point.y, rootSize.width.toFloat() - point.x)
    else -> point
}

/** 迷你条墨色登记键，与原生飞行控件读取的键一致；封面没有墨色。 */
internal fun SourcePlayerSharedElement.inkKey(): String? = when (this) {
    SourcePlayerSharedElement.ARTWORK -> null
    SourcePlayerSharedElement.TITLE -> "title"
    SourcePlayerSharedElement.ARTIST -> "artist"
    SourcePlayerSharedElement.PLAY_PAUSE -> "play"
}

/** 共享元素源端换算到页面坐标；迷你条不在或页面未布局时返回 null。 */
internal fun sourcePlayerSharedSource(motion: PageMotion, element: SourcePlayerSharedElement,
    page: LayoutCoordinates?, rootOffset: Offset, density: Float,
    handoff: PlayerControlHandoff? = null): SourcePlayerSharedSource? {
    val key = element.sourceAnchorKey()
    val anchor = motion.sourceSnapshot[key] ?: motion.sources[key] ?: return null
    val bounds = sourcePlayerRootRectToPage(page, anchor.bounds, rootOffset) ?: return null
    // 墨色取开合起点冻结的值，与原生飞行控件同源；迷你条未登记时不猜。
    val ink = element.inkKey()?.let { handoff?.sourceInk(it, Color.Unspecified) }?.takeIf { it.isSpecified }
    return SourcePlayerSharedSource(bounds.left, bounds.top, bounds.right, bounds.bottom,
        anchor.corner * density, motion.value.coerceIn(0f, 1f), ink?.toArgb(),
        anchor.textSizeSp, anchor.markSize)
}
