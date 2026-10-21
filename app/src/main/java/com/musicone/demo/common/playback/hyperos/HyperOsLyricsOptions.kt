package com.musicone.demo

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设备级开关，所有音源共用，不参与账号和音乐库同步。 */
internal object HyperOsLyricsOptions {
    private var store: SharedPreferences? = null
    private var application: Context? = null
    private val mutableEnabled = MutableStateFlow(true)
    private val mutableStandard = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    val standardTemplate = mutableStandard.asStateFlow()

    fun initialize(context: Context) {
        application = context.applicationContext
        if (store == null) {
            store = application!!.getSharedPreferences("hyperos_lyrics", Context.MODE_PRIVATE)
            // 设置入口已隐藏，统一启用，不再沿用旧版本保存的关闭状态。
            mutableEnabled.value = true
            mutableStandard.value = store!!.getBoolean("standard_template", false)
        }
        if (mutableEnabled.value) prepareChannel()
    }

    fun setEnabled(enabled: Boolean) {
        checkNotNull(store).edit().putBoolean("enabled", enabled).apply()
        mutableEnabled.value = enabled
        if (enabled) prepareChannel() else HyperOsIslandDiagnostics.waiting("未启用")
    }

    fun setStandardTemplate(standard: Boolean) {
        checkNotNull(store).edit().putBoolean("standard_template", standard).apply()
        mutableStandard.value = standard
    }

    private fun prepareChannel() {
        val context = application ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) ensureHyperOsIslandChannel(context)
            if (HyperOsIslandDiagnostics.state.value.message == "未启用")
                HyperOsIslandDiagnostics.waiting("等待播放")
        } catch (error: RuntimeException) {
            HyperOsIslandDiagnostics.failed("创建通知类别失败（${error.javaClass.simpleName}）")
        }
    }
}
