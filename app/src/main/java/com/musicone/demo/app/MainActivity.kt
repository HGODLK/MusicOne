package com.musicone.demo

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.lifecycle.withStarted

class MainActivity : ComponentActivity() {
    // ComponentActivity 将该平台回调标为库内 API，但 USB 独占音量键需要在 Compose 视图前优先处理。
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        UsbExclusiveVolumeKeys.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // 三键导航透出应用背景；此开关不改变手势导航的透明效果。
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ExperiencePreferences.initialize(this)
        val displayClock = checkNotNull(AndroidUiDispatcher.Main[MonotonicFrameClock])
        val uiClock = AnimationFrameClock(displayClock, { ExperiencePreferences.options.uiAnimationFrameRate })
        val lyricClock = AnimationFrameClock(displayClock,
            { ExperiencePreferences.options.lyricAnimationFrameRate }, { lifecycle.withStarted {} })
        val composition = window.decorView.createLifecycleAwareWindowRecomposer(
            MusicMotionDurationScale + uiClock, lifecycle)
        setContent(parent = composition) {
            CompositionLocalProvider(LocalLyricAnimationClock provides lyricClock) {
                MusicOneTheme { MusicOneApp() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // USB 独占绕过系统混音后，仍让实体音量键明确调整软件增益读取的媒体流。
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestMusicDisplayRate()
    }
}
