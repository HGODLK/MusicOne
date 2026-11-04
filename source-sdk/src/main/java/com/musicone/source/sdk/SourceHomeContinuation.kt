package com.musicone.source.sdk

/** 可选的首页暂存提示；只读取已获取的卡池，不发起请求或准备卡片。 */
interface SourceHomeContinuation {
    /** 游标对应的下一批已在包内暂存时返回 true；未知、过期或仅有远端下一页时返回 false。 */
    fun hasBufferedHomeItems(cursor: String): Boolean
}
