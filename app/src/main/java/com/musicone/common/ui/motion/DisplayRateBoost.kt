package com.musicone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 跟手拖动期间明确向系统要高刷：部分系统（如华为智能刷新率）只认列表滚动和惯性滑动，
 * 自定义拖动会被压到低刷新率，看起来像掉帧。松手后撤回，空闲时仍交给系统省电。
 */
@Composable
internal fun HighRefreshRateWhile(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        if (!active) return@DisposableEffect onDispose { }
        val activity = view.context.findActivity() ?: return@DisposableEffect onDispose { }
        activity.applyRefreshBoost(view, true)
        onDispose { activity.applyRefreshBoost(view, false) }
    }
}

@Suppress("DEPRECATION")
private fun Activity.applyRefreshBoost(view: View, boosted: Boolean) {
    val screen = window.decorView.display ?: windowManager.defaultDisplay
    val current = screen.mode
    val preferred = screen.supportedModes.filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
    }.maxByOrNull { it.refreshRate } ?: current
    window.attributes = window.attributes.apply {
        // 每次起手重新提交最高刷新率的显示模式，让系统按新的窗口请求重新决策。
        preferredRefreshRate = preferred.refreshRate
        preferredDisplayModeId = preferred.modeId
    }
    // Android 15 起可按视图声明高帧率类别，系统据此在拖动期间保持高刷。
    if (Build.VERSION.SDK_INT >= 35) {
        view.requestedFrameRate = if (boosted) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    }
    Log.i("MusicDisplay", "拖动提帧=${boosted}，请求=${preferred.refreshRate}Hz，当前=${screen.refreshRate}Hz")
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
