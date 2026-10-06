package com.musicone.demo

/** 关闭输入框才清理草稿；结果页返回只改变 full，继续保留当前输入和联想。 */
internal fun QqSearchState.withSearchMenuClosed(): QqSearchState = copy(
    opened = false,
    full = false,
    singer = null,
    query = "",
    suggestions = emptyList(),
    suggesting = false,
    suggestionError = null,
)
