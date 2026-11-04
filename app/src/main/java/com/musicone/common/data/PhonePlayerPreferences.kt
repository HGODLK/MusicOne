package com.musicone

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 手机播放页的方向偏好独立保存，不改变系统自动旋转开关。 */
internal object PhonePlayerPreferences {
    var allowLandscapeWhenRotationLocked by mutableStateOf(false)
        private set
    var disableLandscapePlayer by mutableStateOf(false)
        private set
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        if (preferences != null) return
        val store = context.applicationContext.getSharedPreferences("phone_player", Context.MODE_PRIVATE)
        preferences = store
        allowLandscapeWhenRotationLocked = store.getBoolean("allow_landscape_when_rotation_locked", false)
        disableLandscapePlayer = store.getBoolean("disable_landscape_player", false)
    }

    fun updateAllowLandscapeWhenRotationLocked(value: Boolean) {
        allowLandscapeWhenRotationLocked = value
        preferences?.edit()?.putBoolean("allow_landscape_when_rotation_locked", value)?.apply()
    }

    fun updateDisableLandscapePlayer(value: Boolean) {
        disableLandscapePlayer = value
        preferences?.edit()?.putBoolean("disable_landscape_player", value)?.apply()
    }
}
