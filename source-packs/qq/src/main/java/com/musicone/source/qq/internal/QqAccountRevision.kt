package com.musicone.source.qq.internal

import java.util.concurrent.atomic.AtomicLong

/** 记录登录会话版本号：同账号正常续期不递增，只有换号、重登或退出时才递增，用来丢弃旧任务。 */
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
