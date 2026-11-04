package com.musicone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 只有手机播放页内部横屏时隐藏状态栏，离开或退后台恢复进入前的可见性。 */
@Composable
internal fun PhonePlayerSystemBars(landscape: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, landscape) {
        val window = view.context.phonePlayerActivity()?.window
        if (!landscape || window == null) return@DisposableEffect onDispose { }
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val type = WindowInsetsCompat.Type.statusBars()
        val previousVisible = ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(type) != false
        val previousBehavior = bars.systemBarsBehavior
        fun showPrevious() {
            if (previousVisible) bars.show(type) else bars.hide(type)
        }
        fun hide() {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(type)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> hide()
                Lifecycle.Event.ON_PAUSE -> showPrevious()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) hide()
        onDispose {
            lifecycle.removeObserver(observer)
            bars.systemBarsBehavior = previousBehavior
            showPrevious()
        }
    }
}

private fun Context.phonePlayerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.phonePlayerActivity()
    else -> null
}
