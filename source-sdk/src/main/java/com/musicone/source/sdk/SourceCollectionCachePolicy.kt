package com.musicone.source.sdk

/** 可选的账号缓存目录筛选规则，由集合服务按需实现。 */
interface SourceCollectionCachePolicy {
    /** 只筛选已拿到的目录项，不发网络请求；未实现时保留现有快照。 */
    fun includesInCacheLibrary(collection: SourceCollection): Boolean
}
