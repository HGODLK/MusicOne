package com.musicone.demo

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*

/** 触摸和已登记的页面滚动期间冻结字色，松手后仍保留短暂防抖。 */
internal class PageInteractionActivity {
    var pressed by mutableStateOf(false)
    var lastTouchEvent = 0L
    private val scrollSources = mutableMapOf<Any, () -> Boolean>()

    fun bindScroll(source: Any, isScrolling: () -> Boolean) { scrollSources[source] = isScrolling }
    fun unbindScroll(source: Any) { scrollSources.remove(source) }

    fun quiet(now: Long = SystemClock.uptimeMillis()) =
        !pressed && now - lastTouchEvent >= 48L && scrollSources.values.none { it() }
}
internal val LocalPageInteraction = staticCompositionLocalOf<PageInteractionActivity?> { null }

internal fun Modifier.observePageInteraction(activity: PageInteractionActivity): Modifier =
    pointerInput(activity) {
        try {
            awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                activity.pressed = event.changes.any { it.pressed }
                activity.lastTouchEvent = SystemClock.uptimeMillis()
            } }
        } finally { activity.pressed = false }
    }
