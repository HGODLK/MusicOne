package com.musicone

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 根页面上临时禁止左右切页的来源，例如歌单编辑面板、卡片多选。 */
@Stable
internal class RootPageSwipeLocks {
    private val holders = mutableStateListOf<Any>()
    val locked: Boolean get() = holders.isNotEmpty()
    fun add(holder: Any) { if (holder !in holders) holders += holder }
    fun remove(holder: Any) { holders -= holder }
}

internal val LocalRootPageSwipeLocks = staticCompositionLocalOf { RootPageSwipeLocks() }

/** locked 为真期间禁止根页面左右切页，离开组合时自动解除。 */
@Composable
internal fun LockRootPageSwipe(locked: Boolean) {
    val locks = LocalRootPageSwipeLocks.current
    val holder = remember { Any() }
    DisposableEffect(locks, locked) {
        if (locked) locks.add(holder)
        onDispose { locks.remove(holder) }
    }
}

/** 松手后的落点：快速甩动按方向翻页，否则按拖过的位置就近落位。 */
internal fun rootPageSwipeTarget(position: Float, velocityX: Float, threshold: Float): MusicOnePage {
    val last = MusicOnePage.entries.lastIndex
    val clamped = position.coerceIn(0f, last.toFloat())
    val index = when {
        // 手指向左甩，页面向后翻。
        velocityX <= -threshold -> ceil(clamped).toInt()
        velocityX >= threshold -> floor(clamped).toInt()
        else -> clamped.roundToInt()
    }
    return MusicOnePage.entries[index.coerceIn(0, last)]
}

/**
 * 在根页面没有横向操作的地方左右拖动切换首页与我的：页面跟手，导航胶囊的选中块读同一位置一起移动。
 * 子组件优先：横向列表、轮播等先拿到横向拖动时这里自动让出；纵向滚动先越过触摸阈值时同样让出。
 */
internal fun Modifier.rootPageSwipe(navigation: RootPageNavigation, enabled: () -> Boolean): Modifier =
    pointerInput(navigation) {
        val flingThreshold = 600.dp.toPx()
        val last = MusicOnePage.entries.lastIndex.toFloat()
        awaitEachGesture {
            // 卡片点击会消费按下事件，这里仍要接住，后续是否横拖再看移动是否被子组件消费。
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled() || navigation.isNavigationDragBlocked()) return@awaitEachGesture
            var overSlop = 0f
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                overSlop = over
            } ?: return@awaitEachGesture
            if (!enabled() || navigation.isNavigationDragBlocked()) return@awaitEachGesture
            navigation.beginNavigationInteraction()
            val width = size.width.toFloat().coerceAtLeast(1f)
            val tracker = VelocityTracker()
            tracker.addPosition(start.uptimeMillis, start.position)
            var position = (navigation.position - overSlop / width).coerceIn(0f, last)
            navigation.dragTo(position)
            val completed = horizontalDrag(start.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                position = (position - change.positionChange().x / width).coerceIn(0f, last)
                change.consume()
                navigation.dragTo(position)
            }
            val velocity = if (completed) tracker.calculateVelocity().x else 0f
            navigation.animateTo(rootPageSwipeTarget(position, velocity, flingThreshold))
        }
    }

/** 供页面内横向货架在滑到尽头时把手势交给根页面；enabled 与根页面左右滑动同一条件。 */
internal class RootPageEdgeHandoff(val navigation: RootPageNavigation, val enabled: () -> Boolean)

internal val LocalRootPageEdgeHandoff = staticCompositionLocalOf<RootPageEdgeHandoff?> { null }

@Composable
internal fun rememberRootPageEdgeHandoff(navigation: RootPageNavigation, enabled: Boolean,
    locks: RootPageSwipeLocks): RootPageEdgeHandoff {
    val current = rememberUpdatedState(enabled)
    return remember(navigation, locks) { RootPageEdgeHandoff(navigation) { current.value && !locks.locked } }
}

/**
 * 横向货架（分页器、横向列表）已滑到尽头时，继续朝外滑直接带动根页面切到首页／我的，与空白处左右滑动同一手感。
 * 在 Initial 阶段抢在货架之前判断方向：只有货架在该方向滚不动、根页面在该方向还有页时才接手并消费，
 * 货架和根页面自身的检测都会因此放弃；其余情况完全不干预，货架照常滚动。
 */
@Composable
internal fun Modifier.rootPageEdgeSwipe(state: ScrollableState): Modifier {
    val handoff = LocalRootPageEdgeHandoff.current ?: return this
    // 根分页器铺满窗口宽度，货架自身可能更窄，跟手比例按窗口宽度算。
    val windowWidth = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val width by rememberUpdatedState(windowWidth)
    // 货架随根页面一起平移，本地坐标里手指位移会被抵消；拖动阶段一律换算到窗口坐标。
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun windowX(change: PointerInputChange): Float =
        coordinates[0]?.takeIf { it.isAttached }?.localToWindow(change.position)?.x ?: change.position.x
    return onGloballyPositioned { coordinates[0] = it }.pointerInput(state, handoff) {
        val navigation = handoff.navigation
        val flingThreshold = 600.dp.toPx()
        val last = MusicOnePage.entries.lastIndex.toFloat()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!handoff.enabled() || navigation.isNavigationDragBlocked()) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            var start: PointerInputChange? = null
            while (start == null) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                if (abs(dx) > slop) {
                    // 手指左滑：货架要向后滚、根页面向后翻（首页到我的）。
                    val forward = dx < 0f
                    val shelfAtEdge = if (forward) !state.canScrollForward else !state.canScrollBackward
                    val rootHasPage = if (forward) navigation.position < last - .001f else navigation.position > .001f
                    if (!shelfAtEdge || !rootHasPage || !handoff.enabled()) return@awaitEachGesture
                    change.consume()
                    start = change
                }
            }
            val first = start ?: return@awaitEachGesture
            navigation.beginNavigationInteraction()
            val pageWidth = width.coerceAtLeast(1f)
            val overSlop = dx - if (dx > 0f) slop else -slop
            val tracker = VelocityTracker()
            var lastX = windowX(first)
            tracker.addPosition(first.uptimeMillis, Offset(lastX, 0f))
            var position = (navigation.position - overSlop / pageWidth).coerceIn(0f, last)
            navigation.dragTo(position)
            var completed = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id } ?: break
                if (!change.pressed) { completed = true; break }
                val x = windowX(change)
                tracker.addPosition(change.uptimeMillis, Offset(x, 0f))
                position = (position - (x - lastX) / pageWidth).coerceIn(0f, last)
                lastX = x
                change.consume()
                navigation.dragTo(position)
            }
            val velocity = if (completed) tracker.calculateVelocity().x else 0f
            navigation.animateTo(rootPageSwipeTarget(position, velocity, flingThreshold))
        }
    }
}

/** 供 MusicOneApp 包住根页面分页器；enabled 每次触摸时读取最新状态。 */
@Composable
internal fun Modifier.rootPageSwipe(navigation: RootPageNavigation, enabled: Boolean): Modifier {
    val locks = LocalRootPageSwipeLocks.current
    val current = rememberUpdatedState(enabled)
    return rootPageSwipe(navigation) { current.value && !locks.locked }
}
