package com.musicone.demo

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/** 直通 USB 播放期间保持写入线程存活，暂停或退出直通后立即释放。 */
internal class UsbPlaybackWakeGuard(context: Context) {
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicOne:Uac2Playback")
        .apply { setReferenceCounted(false) }

    @SuppressLint("WakelockTimeout")
    @Synchronized
    fun setActive(active: Boolean) {
        if (active && !wakeLock.isHeld) wakeLock.acquire()
        else if (!active && wakeLock.isHeld) wakeLock.release()
    }

    fun release() = setActive(false)
}
