package com.musicone.demo

import android.app.Activity
import android.util.Log
import androidx.compose.ui.MotionDurationScale

/** 应用动效使用真实时间，不修改系统的动画倍率设置。 */
internal object MusicMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** 仅请求当前分辨率的最高刷新率，避免切换分辨率或操控系统设置。 */
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
