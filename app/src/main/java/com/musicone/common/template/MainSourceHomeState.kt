package com.musicone

internal data class MainSourceHomeState(
    val cards: List<MainSourceHomeCard> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val automaticLoading: Boolean = true,
    val refreshRequired: Boolean = false,
    val refreshing: Boolean = false,
    val generation: Long = 0,
    val settled: Boolean = false,
    val refreshLoading: Boolean = false,
)

/** 首页只观察实际展示内容，分页请求的开始和结束由独立观察器处理。 */
internal data class MainSourceHomeContent(
    val cards: List<MainSourceHomeCard> = emptyList(),
    val message: String? = null,
    val refreshing: Boolean = false,
    val generation: Long = 0,
    val initialLoading: Boolean = true,
)

internal fun MainSourceHomeState.toContent() = MainSourceHomeContent(
    cards, message, refreshing, generation, cards.isEmpty() && !settled && message == null,
)
