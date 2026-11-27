package com.musicone

/** 在设置页调缓存上限时先暂存草稿，退出整个设置页后再一次性生效并触发清理。 */
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
