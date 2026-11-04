package com.musicone.source.sdk

/** 可选账号缓存目录规则；由集合服务额外实现，不改变已有集合服务的二进制接口。 */
interface SourceCollectionCachePolicy {
    /** 仅筛选已取得的目录项，不发起网络请求；未实现时保留已有快照。 */
    fun includesInCacheLibrary(collection: SourceCollection): Boolean
}
