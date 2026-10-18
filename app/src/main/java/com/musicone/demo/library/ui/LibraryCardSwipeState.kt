package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 再次按下接住当前画面，旧收尾任务不能抢回拖动状态。 */
internal class LibraryCardSwipeState(initial: Int, private val scope: CoroutineScope, private val onSelected: (Int) -> Unit) {
    val animation = Animatable(initial.toFloat())
    private var drag by mutableFloatStateOf(Float.NaN)
    private var settling by mutableStateOf(false)
    private var job: Job? = null
    private var generation = 0L
    val value get() = drag.takeUnless { it.isNaN() } ?: animation.value
    val moving by derivedStateOf { !drag.isNaN() || settling }
    private var start = initial.toFloat()

    fun begin() {
        val current = value
        generation++
        job?.cancel()
        settling = false
        start = current
        drag = current
    }
    fun move(distance: Float, width: Float) { drag = (start - distance / width.coerceAtLeast(1f)).coerceIn(0f, 1f) }
    fun select(index: Int, speed: Float = 0f) {
        val current = value
        generation++
        job?.cancel()
        drag = current
        settling = true
        onSelected(index)
        val epoch = generation
        job = scope.launch {
            animation.snapTo(current)
            drag = Float.NaN
            try { animation.animateTo(index.toFloat(), musicMotion(360), initialVelocity = speed.coerceIn(-6f, 6f)) }
            finally { if (generation == epoch) settling = false }
        }
    }
}

internal fun libraryCardSwipeTarget(position: Float, distance: Float, velocity: Float, width: Float, cancelled: Boolean, selected: Int): Int {
    if (cancelled) return selected
    // 明确位置优先，短划才由释放方向补充；反向回勾不沿用整段距离的方向。
    if (position <= .2f) return 0
    if (position >= .8f) return 1
    if (abs(distance) > width * .025f && abs(velocity) > width * .65f) return if (velocity < 0f) 1 else 0
    // 慢划越过约四分之一宽度即可切换，两个方向使用相同的离开距离。
    return if (position >= if (selected == 0) .28f else .72f) 1 else 0
}
