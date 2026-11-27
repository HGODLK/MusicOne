package com.musicone

/** 统一触发首页各区块刷新：手动刷新强制重拉，自动刷新按各自上次成功日期判断是否过期。 */
internal class MainSourceHomeRefresh(
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
