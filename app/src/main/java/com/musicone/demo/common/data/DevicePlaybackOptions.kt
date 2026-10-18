package com.musicone.demo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 仅保存在本机，不参与账号或音乐库同步。 */
internal object DevicePlaybackOptions {
    private var store: SharedPreferences? = null
    private val mutableConcurrentPlayback = MutableStateFlow(false)
    val concurrentPlayback = mutableConcurrentPlayback.asStateFlow()

    fun initialize(context: Context) {
        if (store != null) return
        store = context.applicationContext.getSharedPreferences("device_playback_options", Context.MODE_PRIVATE)
        mutableConcurrentPlayback.value = store!!.getBoolean("concurrent_playback", false)
    }

    fun setConcurrentPlayback(enabled: Boolean) {
        checkNotNull(store) { "设备播放设置尚未初始化" }
            .edit().putBoolean("concurrent_playback", enabled).apply()
        mutableConcurrentPlayback.value = enabled
    }
}
