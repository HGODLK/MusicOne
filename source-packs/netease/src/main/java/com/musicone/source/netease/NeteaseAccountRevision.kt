package com.musicone.source.netease

import java.util.concurrent.atomic.AtomicLong

/** 同账号资料刷新保留请求代次，显式登录、换号和过期恢复仍隔离旧任务。 */
internal class NeteaseAccountRevision {
    private val value = AtomicLong()
    fun get(): Long = value.get()
    fun incrementAndGet(): Long = value.incrementAndGet()
    fun saved(previousId: String?, currentId: String, refreshing: Boolean, wasExpired: Boolean) {
        if (!refreshing || wasExpired || previousId.isNullOrBlank() || previousId != currentId) value.incrementAndGet()
    }
}
