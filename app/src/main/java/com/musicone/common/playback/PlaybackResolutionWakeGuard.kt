package com.musicone

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** 一首歌播完时播放器会释放网络锁，这里在解析下一首期间单独申请带超时的 CPU 和 Wi-Fi 锁。 */
internal class PlaybackResolutionWakeGuard(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val handler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun acquire(): () -> Unit {
        // 每次请求各自持有独立的锁，防止旧请求结束时把新请求的锁给放了。
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicOne:ResolveNext")
        lock.acquire(45_000L)
        var networkLock: WifiManager.WifiLock? = null
        try {
            // 高性能锁可覆盖息屏解析；低延迟模式仅在屏幕亮起且应用前台时生效。
            networkLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MusicOne:ResolveNextNetwork")
                ?.apply { setReferenceCounted(false) }
            networkLock?.acquire()
        } catch (error: RuntimeException) {
            android.util.Log.w("MusicPlayback", "解析网络锁不可用：${error::class.java.simpleName}")
        }
        val release = Runnable {
            networkLock?.let { if (it.isHeld) it.release() }
            if (lock.isHeld) lock.release()
        }
        // Wi-Fi 锁没有自带超时，用 Handler 做 45 秒兜底释放，取消或失败时也会提前释放。
        handler.postDelayed(release, 45_000L)
        return { handler.removeCallbacks(release); release.run() }
    }
}
