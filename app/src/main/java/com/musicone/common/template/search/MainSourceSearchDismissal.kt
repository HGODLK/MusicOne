package com.musicone

/** 彻底关掉搜索菜单时才清空输入草稿；从结果页退回输入框只关 full，保留输入词和联想。 */
internal fun MainSourceSearchState.withSearchMenuClosed(): MainSourceSearchState = copy(
    opened = false,
    full = false,
    singer = null,
    query = "",
    suggestions = emptyList(),
    suggesting = false,
    suggestionError = null,
)
