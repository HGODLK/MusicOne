package com.musicone.demo

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** 曲末播放器会释放网络保护，解析任务单独持有有界 CPU 和网络锁。 */
internal class PlaybackResolutionWakeGuard(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val handler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun acquire(): () -> Unit {
        // 每轮单独持有，旧请求的 finally 不能释放新请求的锁。
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
        // Wi-Fi 锁没有系统超时参数，沿用解析 CPU 锁的兜底期限，取消和失败也立即释放。
        handler.postDelayed(release, 45_000L)
        return { handler.removeCallbacks(release); release.run() }
    }
}
