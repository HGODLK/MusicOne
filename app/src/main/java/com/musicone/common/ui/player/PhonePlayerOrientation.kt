package com.musicone

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class PhonePlayerOrientationState(val isPhone: Boolean, val rotationDegrees: Int,
    val native: PhonePlayerNativeOrientation)

internal fun isPhonePlayerConfiguration(configuration: Configuration): Boolean =
    PhonePlayerOrientationPolicy.isPhone(configuration.smallestScreenWidthDp)

/** 手机窗口始终竖屏；跨越折叠形态时恢复原有的大屏方向策略。 */
internal class PhoneActivityOrientationPolicy(private val activity: Activity) {
    private val largeScreenOrientation = activity.packageManager
        .getActivityInfo(activity.componentName, 0).screenOrientation

    fun apply(configuration: Configuration) {
        val orientation = if (isPhonePlayerConfiguration(configuration)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            // 读取清单原值，避免进程/Activity 重建后沿用手机阶段遗留的竖屏请求。
            largeScreenOrientation
        }
        if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
    }
}

/** 只旋转播放内容，传感器监听不会请求 Activity 横屏。 */
@Composable
internal fun rememberPhonePlayerOrientation(enabled: Boolean): PhonePlayerOrientationState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isPhone = isPhonePlayerConfiguration(LocalConfiguration.current)
    val ignoreRotationLock = PhonePlayerPreferences.allowLandscapeWhenRotationLocked
    var rotation by remember(isPhone) { mutableIntStateOf(0) }
    var nativeOrientation by remember(isPhone) { mutableStateOf(PhonePlayerNativeOrientation()) }

    DisposableEffect(context, lifecycle, enabled, isPhone, ignoreRotationLock) {
        if (!enabled || !isPhone) return@DisposableEffect onDispose { }

        val resolver = context.contentResolver
        var active = false
        var sensorEnabled = false
        val handler = Handler(Looper.getMainLooper())
        val nativeSensor = PhonePlayerNativeOrientationSensor(context, handler, nativeOrientation.rotationDegrees) {
            nativeOrientation = it
        }
        val stability = PhonePlayerOrientationStability(rotation)
        val settleDirection = Runnable {
            if (active && sensorEnabled) rotation = stability.settle(SystemClock.uptimeMillis())
        }
        val sensor = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (active && sensorEnabled) {
                    rotation = stability.update(orientation, SystemClock.uptimeMillis())
                    handler.removeCallbacks(settleDirection)
                    stability.decisionAtMs?.let { handler.postAtTime(settleDirection, it) }
                }
            }
        }

        fun refreshRotationPermission() {
            val systemAllowsRotation = Settings.System.getInt(
                resolver, Settings.System.ACCELEROMETER_ROTATION, 0,
            ) == 1
            val shouldListen = PhonePlayerOrientationPolicy.shouldListen(
                active, systemAllowsRotation, ignoreRotationLock, sensor.canDetectOrientation(),
            )
            if (shouldListen != sensorEnabled) {
                sensorEnabled = shouldListen
                if (shouldListen) {
                    sensor.enable()
                    nativeSensor.start()
                } else sensor.disable()
            }
            if (!shouldListen) {
                handler.removeCallbacks(settleDirection)
                stability.reset(0)
                rotation = 0
                nativeSensor.stop(resetRotation = true)
            }
        }

        val settingsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                refreshRotationPermission()
            }
        }

        fun start() {
            if (active) return
            active = true
            resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settingsObserver,
            )
            // 每次恢复前台都重读，补上后台期间的系统快捷开关变化。
            refreshRotationPermission()
        }

        fun stop() {
            if (!active) return
            active = false
            resolver.unregisterContentObserver(settingsObserver)
            sensor.disable()
            nativeSensor.stop()
            sensorEnabled = false
            handler.removeCallbacks(settleDirection)
            stability.reset(rotation)
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(lifecycleObserver)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            stop()
        }
    }
    return PhonePlayerOrientationState(isPhone, rotation, nativeOrientation)
}
