package com.musicone.source.webdav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.staticCompositionLocalOf

/** 与宿主 musicMotion 同一条曲线；降低动效时直接到位。 */
internal class WebDavMotion(val reduce: Boolean) {
    fun <T> spec(millis: Int): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(millis, easing = STANDARD_EASING)

    /** 与宿主设置层级切换（settingsPageTransform）同一组时长和位移。 */
    fun pageTransform(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return ContentTransform(
            targetContentEnter = fadeIn(spec(320)) + slideInHorizontally(spec(360)) { direction * it / 16 },
            initialContentExit = fadeOut(spec(220)) + slideOutHorizontally(spec(280)) { -direction * it / 16 },
            sizeTransform = null,
        )
    }

    private companion object {
        val STANDARD_EASING = CubicBezierEasing(.2f, 0f, 0f, 1f)
    }
}

internal val LocalWebDavMotion = staticCompositionLocalOf { WebDavMotion(reduce = false) }
