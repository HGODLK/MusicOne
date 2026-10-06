package com.musicone.demo

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object PlaybackOptions {
    var keepScreenOn by mutableStateOf(false)
        private set
    var mobileQuality by mutableStateOf<AudioQuality?>(null)
        private set
    var usbLosslessOutput by mutableStateOf(false)
        private set
    private val mutableUsbLosslessOutput = MutableStateFlow(false)
    val usbLosslessOutputChanges = mutableUsbLosslessOutput.asStateFlow()
    private var store: android.content.SharedPreferences? = null
    fun initialize(context: Context) {
        if (store != null) return
        store = context.applicationContext.getSharedPreferences("playback_options", Context.MODE_PRIVATE)
        keepScreenOn = store!!.getBoolean("screen_on", false)
        mobileQuality = AudioQuality.entries.firstOrNull { it.name == store!!.getString("mobile_quality", null) }
        usbLosslessOutput = store!!.getBoolean("usb_lossless_output", false)
        mutableUsbLosslessOutput.value = usbLosslessOutput
    }
    fun screenOn(value: Boolean) { keepScreenOn = value; store?.edit()?.putBoolean("screen_on", value)?.apply() }
    fun mobile(value: AudioQuality?) { mobileQuality = value; store?.edit()?.putString("mobile_quality", value?.name)?.apply() }
    fun usbLossless(value: Boolean) {
        usbLosslessOutput = value
        mutableUsbLosslessOutput.value = value
        store?.edit()?.putBoolean("usb_lossless_output", value)?.apply()
        if (!value) UsbAudioOutputStatus.publish(UsbAudioOutputSnapshot(UsbAudioOutputRoute.DISABLED))
    }
    fun effective(context: Context, preferred: AudioQuality): AudioQuality {
        initialize(context)
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val mobile = manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        return if (mobile) mobileQuality?.takeIf { it.ordinal < preferred.ordinal } ?: preferred else preferred
    }
}

/** 定时到点暂停；本首结束在进入下一首前消费。 */
internal object PlaybackSleepTimer {
    var deadline by mutableLongStateOf(0L)
        private set
    var afterTrack by mutableStateOf(false)
        private set
    fun set(minutes: Int) { afterTrack = minutes == -1; deadline = if (minutes > 0) android.os.SystemClock.elapsedRealtime() + minutes * 60000L else 0L }
    fun expired(): Boolean = deadline > 0 && android.os.SystemClock.elapsedRealtime() >= deadline
    fun consumeTrackEnd(): Boolean = afterTrack.also { if (it) set(0) }
}
