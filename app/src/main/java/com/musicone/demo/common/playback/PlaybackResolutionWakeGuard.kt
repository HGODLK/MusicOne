package com.musicone.demo

import android.content.Context
import android.os.PowerManager

/** 曲末到下一首准备之间播放器可能已释放锁，解析任务单独持有有界 CPU 锁。 */
internal class PlaybackResolutionWakeGuard(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun acquire(): () -> Unit {
        // 每轮单独持有，旧请求的 finally 不能释放新请求的锁。
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicOne:ResolveNext")
        lock.acquire(45_000L)
        return { if (lock.isHeld) lock.release() }
    }
}
