package com.musicone

import android.app.Activity
import android.util.Log
import androidx.compose.ui.MotionDurationScale

/** 应用内动效固定按 1x 速度播放，不受系统开发者选项里的动画倍率影响。 */
internal object MusicMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** 只在当前分辨率下选最高刷新率，不改分辨率也不动系统设置。 */
@Suppress("DEPRECATION")
internal fun Activity.requestMusicDisplayRate() {
    val screen = window.decorView.display ?: windowManager.defaultDisplay
    val current = screen.mode
    val maximum = screen.supportedModes.maxOfOrNull { it.refreshRate } ?: screen.refreshRate
    val preferred = screen.supportedModes.filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
    }.maxByOrNull { it.refreshRate } ?: current
    window.attributes = window.attributes.apply {
        preferredRefreshRate = preferred.refreshRate
        preferredDisplayModeId = preferred.modeId
    }
    Log.i("MusicDisplay", "屏幕最高=${maximum}Hz，请求=${preferred.refreshRate}Hz，当前=${screen.refreshRate}Hz")
}
