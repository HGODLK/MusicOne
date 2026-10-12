package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CompletableDeferred

/** 只有中间态 seek 放开的历史行淡入；原行透明度、模糊和位置继续各自的动画。 */
internal class LyricSeekHistoryReveal {
    private var boundary by mutableIntStateOf(0)
    private val rows = mutableMapOf<Int, LyricSeekHistoryRowReveal>()
    val active get() = boundary > 0 || rows.isNotEmpty()
    val animating get() = boundary > 0 || rows.values.any { it.alpha.isRunning ||
        (it.drawn.isCompleted && it.alpha.value < 1f) }

    fun revealBefore(index: Int) { boundary = index }

    fun row(index: Int): LyricSeekHistoryRowReveal? = rows[index] ?: if (index < boundary) {
        LyricSeekHistoryRowReveal().also { rows[index] = it }
    } else null

    fun finish() {
        boundary = 0
        // 未真正绘制的屏外行不把本次补位动画带入后续普通浏览。
        rows.entries.removeAll { !it.value.drawn.isCompleted }
    }

    fun clear() { boundary = 0; rows.clear() }
}

internal class LyricSeekHistoryRowReveal {
    val alpha = Animatable(0f)
    val drawn = CompletableDeferred<Unit>()
}

@Composable
internal fun lyricSeekHistoryRevealModifier(history: LyricSeekHistoryReveal?, index: Int, frozen: Boolean): Modifier {
    val reveal = history?.row(index) ?: return Modifier
    val row = remember(history, index, reveal) { reveal }
    LyricAnimationEffect(row, frozen) {
        if (!frozen) {
            // 从首次实际绘制开始计时，LazyColumn 屏外预取不能提前耗完淡入。
            row.drawn.await()
            row.alpha.animateTo(1f, musicMotion(200))
        }
    }
    return Modifier.drawWithContent {
        row.drawn.complete(Unit)
        drawContent()
    }.graphicsLayer { alpha = row.alpha.value }
}
