package com.musicone

import android.app.Activity
import android.content.res.Resources
import android.os.Build

/** 把系统启动窗口和 App 内的启动动画接起来。 */
internal class HomeLaunchWindow(private val activity: Activity) {
    private val gate = HomeLaunchGate.process
    private var appliedTheme: Int? = null

    fun prepare() {
        val systemSplash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val animated = gate.prepare(ExperiencePreferences.options.reduceMotion, systemSplash)
        // 两边都是同一位置的灰色 M，直接移除系统窗口，不做系统默认的退出动画。
        if (animated && systemSplash) activity.splashScreen.setOnExitAnimationListener {
            it.remove()
            gate.splashRemoved()
        }
    }

    /** 系统启动窗口读不到 App 设置，把“始终深色”写入系统，下次启动生效。Android 12 以下没有此接口。 */
    fun syncTheme() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val theme = homeLaunchSplashTheme(ExperiencePreferences.options.alwaysDark)
        if (appliedTheme == theme) return
        appliedTheme = theme
        activity.splashScreen.setSplashScreenTheme(theme)
    }
}

internal fun homeLaunchSplashTheme(alwaysDark: Boolean): Int =
    if (alwaysDark) R.style.Theme_MusicOne_Launch_Dark else Resources.ID_NULL
