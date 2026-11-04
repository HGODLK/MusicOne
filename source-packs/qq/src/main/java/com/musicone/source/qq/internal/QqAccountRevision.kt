package com.musicone.source.qq.internal

import java.util.concurrent.atomic.AtomicLong

/** 同账号的正常续期不改变登录代次，换号、重登、退出仍令旧任务失效。 */
internal class QqAccountRevision {
    private val value = AtomicLong()
    fun get(): Long = value.get()
    fun incrementAndGet(): Long = value.incrementAndGet()
    fun saved(previous: MusicAccount?, current: MusicAccount, refreshing: Boolean, wasExpired: Boolean) {
        val sameAccount = previous != null && previous.source == current.source &&
            previous.userId.isNotBlank() && previous.userId == current.userId
        if (!refreshing || wasExpired || !sameAccount) value.incrementAndGet()
    }
}
