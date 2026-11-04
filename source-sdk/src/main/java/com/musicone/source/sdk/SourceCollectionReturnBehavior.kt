package com.musicone.source.sdk

/** 可选集合返回策略；部分服务返回专辑页也需要同步账号资料库。 */
interface SourceCollectionReturnBehavior {
    val refreshLibraryAfterAnyReturn: Boolean get() = false
}
