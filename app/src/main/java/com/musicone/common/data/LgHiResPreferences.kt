package com.musicone

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** LG 设备本地偏好，不进入音源或账号同步。 */
internal object LgHiResPreferences {
    private var store: android.content.SharedPreferences? = null
    private val mutable = MutableStateFlow(false)
    val enabled = mutable.asStateFlow()

    fun initialize(context: Context) {
        if (store != null) return
        store = context.applicationContext.getSharedPreferences("lg_audio_output", Context.MODE_PRIVATE)
        mutable.value = store!!.getBoolean("enabled", false)
    }

    fun setEnabled(value: Boolean) {
        checkNotNull(store).edit().putBoolean("enabled", value).apply()
        mutable.value = value
    }
}
