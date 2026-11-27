package com.musicone.source.sdk

/** 可选的首页缓存提示；只查本地已拿到的卡片，不发请求也不预生成卡片。 */
interface SourceHomeContinuation {
    /** 游标对应的下一批数据已在本地缓存时返回 true；未知、过期或需联网拉取时返回 false。 */
    fun hasBufferedHomeItems(cursor: String): Boolean
}
