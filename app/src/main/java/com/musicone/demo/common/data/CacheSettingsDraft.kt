package com.musicone.demo

/** 设置会话只暂存选择，真正离开整个设置页面后一次性移交给磁盘缓存。 */
internal class CacheSettingsDraft {
    @Volatile var pending: CacheLimits? = null
        private set

    @Synchronized fun select(applied: CacheLimits, independent: Boolean, capacity: CacheCapacity): CacheLimits {
        val previous = pending ?: applied
        return (if (independent) previous.copy(independent = capacity) else previous.copy(total = capacity))
            .also { pending = it }
    }

    @Synchronized fun consume(): CacheLimits? = pending.also { pending = null }
}
