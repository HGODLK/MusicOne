package com.musicone.demo

/** 首页刷新入口统一分发，手动强制更新，自动触发由各内容的成功日期判定。 */
internal class QqHomeRefresh(
    private val recommendedTracks: (Boolean) -> Unit,
    private val similarRecommendations: (Boolean) -> Unit,
    private val musicFeed: (Boolean) -> Unit,
) {
    fun refresh(force: Boolean) {
        recommendedTracks(force)
        similarRecommendations(force)
        musicFeed(force)
    }
}
