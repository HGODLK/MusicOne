package com.musicone.source.netease

import java.util.concurrent.atomic.AtomicLong

/** 记录账号切换版本号：仅刷新同账号资料时不递增，只有重新登录、换号或过期恢复后才递增，用来丢弃旧账号的在途请求。 */
internal class NeteaseAccountRevision {
    private val value = AtomicLong()
    fun get(): Long = value.get()
    fun incrementAndGet(): Long = value.incrementAndGet()
    fun saved(previousId: String?, currentId: String, refreshing: Boolean, wasExpired: Boolean) {
        if (!refreshing || wasExpired || previousId.isNullOrBlank() || previousId != currentId) value.incrementAndGet()
    }
}
