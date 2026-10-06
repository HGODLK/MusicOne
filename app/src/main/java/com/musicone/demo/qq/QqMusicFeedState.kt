package com.musicone.demo

internal data class QqMusicFeedState(
    val cards: List<QqMusicFeedCard> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val automaticLoading: Boolean = true,
    val refreshRequired: Boolean = false,
    val refreshing: Boolean = false,
    val generation: Long = 0,
    val settled: Boolean = false,
)

/** 首页只观察实际展示内容，分页请求的开始和结束由独立观察器处理。 */
internal data class QqMusicFeedContent(
    val cards: List<QqMusicFeedCard> = emptyList(),
    val message: String? = null,
    val refreshing: Boolean = false,
    val generation: Long = 0,
    val initialLoading: Boolean = true,
)

internal fun QqMusicFeedState.toContent() = QqMusicFeedContent(
    cards, message, refreshing, generation, cards.isEmpty() && !settled && message == null,
)
